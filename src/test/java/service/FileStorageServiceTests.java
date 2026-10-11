package service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import kr.or.oti.domain.BoardAttachVO;
import kr.or.oti.dto.BoardAttachDTO;
import kr.or.oti.dto.BoardDTO;
import kr.or.oti.mapper.BoardAttachMapper;
import kr.or.oti.service.*;

class FileStorageServiceTests {
    @TempDir Path temporaryDirectory;
    private Path uploadRoot;
    private FileStorageService storage;
    private LocalTransactionManager manager;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws IOException {
        uploadRoot = Files.createDirectory(temporaryDirectory.resolve("uploads"));
        storage = new FileStorageService();
        ReflectionTestUtils.setField(storage, "uploadRoot", uploadRoot.toString());
        manager = new LocalTransactionManager();
        transaction = new TransactionTemplate(manager);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("uploadFiles", "sample.txt", "text/plain",
                "file contents".getBytes(StandardCharsets.UTF_8));
    }

    private Path pathOf(BoardAttachVO attach) {
        return uploadRoot.resolve(attach.getUploadPath())
                .resolve(attach.getUuid() + "_" + attach.getFileName());
    }

    private BoardAttachDTO dto(BoardAttachVO attach) {
        return BoardAttachDTO.builder().uuid(attach.getUuid()).fileName(attach.getFileName())
                .uploadPath(attach.getUploadPath()).build();
    }

    @Test
    void 트랜잭션_없는_파일_저장과_삭제는_거부한다() {
        assertThrows(IllegalStateException.class, () -> storage.store(file()));
        assertThrows(IllegalStateException.class, () -> storage.delete(
                BoardAttachDTO.builder().uuid("uuid").fileName("sample.txt").uploadPath("").build()));
    }

    @Test
    void 빈_업로드는_파일을_만들지_않는다() {
        transaction.execute(status -> {
            assertThrows(IllegalArgumentException.class, () -> storage.store(null));
            assertThrows(IllegalArgumentException.class, () -> storage.store(
                    new MockMultipartFile("uploadFiles", "empty.txt", "text/plain", new byte[0])));
            return null;
        });
        try (java.util.stream.Stream<Path> paths = Files.walk(uploadRoot)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void 같은_이름의_파일도_서로_덮어쓰지_않고_커밋_후_읽을_수_있다() throws IOException {
        BoardAttachVO first = transaction.execute(status -> storage.store(file()));
        BoardAttachVO second = transaction.execute(status -> storage.store(file()));

        assertNotEquals(first.getUuid(), second.getUuid());
        assertTrue(first.getUploadPath().matches("[0-9]{4}/[0-9]{2}/[0-9]{2}"));
        assertArrayEquals(file().getBytes(), Files.readAllBytes(pathOf(first)));
        assertArrayEquals(file().getBytes(), Files.readAllBytes(pathOf(second)));
        assertTrue(storage.load(dto(first)).exists());
        assertEquals(file().getSize(), first.getFileSize().longValue());
    }

    @Test
    void 첨부파일_DB_저장_실패는_게시글_트랜잭션을_롤백하고_새_파일을_정리한다()
            throws IOException {
        BoardService boards = mock(BoardService.class);
        BoardAttachMapper mapper = mock(BoardAttachMapper.class);
        when(boards.register(any(BoardDTO.class))).thenReturn(10L);
        doAnswer(invocation -> {
            BoardAttachVO attach = invocation.getArgument(0);
            // 실패 직전에는 파일이 존재하고 실제 Spring 트랜잭션이 활성화되어 있어야 한다.
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(Files.exists(pathOf(attach)));
            throw new DataIntegrityViolationException("test metadata insert failure");
        }).when(mapper).insertOne(any(BoardAttachVO.class));
        BoardAttachService attachments = new BoardAttachServiceImpl(mapper, storage);
        BoardWriteService target = new BoardWriteService(boards, attachments);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        BoardWriteService writer = (BoardWriteService) proxy.getProxy();

        assertThrows(DataIntegrityViolationException.class, () -> writer.register(
                BoardDTO.builder().title("title").content("content").writer("member").build(),
                Collections.singletonList(file()), null, null));

        assertTrue(manager.rolledBack);
        assertFalse(manager.committed);
        try (java.util.stream.Stream<Path> paths = Files.walk(uploadRoot)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @ParameterizedTest(name = "삭제 트랜잭션 롤백 여부: {0}")
    @ValueSource(booleans = {false, true})
    void 기존_파일은_커밋_후에만_삭제하고_롤백되면_유지한다(boolean rollback) {
        BoardAttachVO attach = transaction.execute(status -> storage.store(file()));
        Path saved = pathOf(attach);

        transaction.execute(status -> {
            storage.delete(dto(attach));
            assertTrue(Files.exists(saved), "커밋 전에 기존 파일을 지우면 안 된다.");
            if (rollback) {
                status.setRollbackOnly();
            }
            return null;
        });

        assertEquals(rollback, Files.exists(saved));
    }

    @Test
    void 업로드_폴더_밖의_파일은_조회도_삭제도_할_수_없다() throws IOException {
        Path outside = temporaryDirectory.resolve("uuid_secret.txt");
        Files.write(outside, "outside".getBytes(StandardCharsets.UTF_8));
        BoardAttachDTO traversal = BoardAttachDTO.builder().uuid("uuid")
                .fileName("secret.txt").uploadPath("..").build();

        assertThrows(IllegalStateException.class, () -> storage.load(traversal));
        transaction.execute(status -> {
            assertThrows(IllegalStateException.class, () -> storage.delete(traversal));
            return null;
        });
        assertArrayEquals("outside".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outside));
    }

    @Test
    void 파일_쓰기_도중_실패하면_불완전한_파일을_정리한다() throws IOException {
        MockMultipartFile failingFile = new MockMultipartFile(
                "uploadFiles", "partial.txt", "text/plain", new byte[] {1}) {
            @Override
            public void transferTo(File target) throws IOException {
                Files.write(target.toPath(), new byte[] {1});
                throw new IOException("test disk write failure");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> transaction.execute(status -> storage.store(failingFile)));
        try (java.util.stream.Stream<Path> paths = Files.walk(uploadRoot)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    /**
     * DB 연결 없이 Spring의 트랜잭션 시작/커밋/롤백 및 파일 콜백을 실행한다.
     * Oracle의 실제 행 롤백은 검증하지 않는다.
     */
    private static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;
        private boolean committed;
        private boolean rolledBack;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
