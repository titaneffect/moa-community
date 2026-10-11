package controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import kr.or.oti.controller.BoardAttachController;
import kr.or.oti.domain.AttachUsage;
import kr.or.oti.domain.CafeBoardAccessLevel;
import kr.or.oti.dto.*;
import kr.or.oti.service.*;

@ExtendWith(MockitoExtension.class)
class BoardAttachControllerTests {
    @Mock private BoardService boards;
    @Mock private CafeBoardService cafeBoards;
    @Mock private CafeBoardAccessService access;
    @Mock private BoardAttachService attachments;
    @Mock private FileStorageService storage;
    private BoardAttachController controller;

    @BeforeEach
    void setUp() {
        controller = new BoardAttachController(boards, cafeBoards, access, attachments, storage);
    }

    private BoardAttachDTO prepareAttachment(AttachUsage usage, String type) {
        BoardAttachDTO attach = BoardAttachDTO.builder().ano(10L).bno(20L)
                .fileName("sample.txt").attachUsage(usage).contentType(type).build();
        when(attachments.get(10L)).thenReturn(attach);
        when(boards.get(20L)).thenReturn(BoardDTO.builder().cafeBoardId(2L).build());
        when(cafeBoards.getById(2L)).thenReturn(CafeBoardDTO.builder().cafeId(1L)
                .readRole(CafeBoardAccessLevel.MEMBER).build());
        return attach;
    }

    @Test
    void 읽기_권한이_없으면_파일_주소를_알아도_조회할_수_없다() {
        prepareAttachment(AttachUsage.FILE, "text/plain");
        when(access.canAccess(1L, "guest", CafeBoardAccessLevel.MEMBER)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> controller.view(10L, () -> "guest"));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @CsvSource({
        "CONTENT_IMAGE, image/png, inline",
        "CONTENT_IMAGE, text/html, attachment",
        "CONTENT_IMAGE, image/svg+xml, attachment",
        "FILE, image/png, attachment"
    })
    void 허용된_본문_이미지만_화면에_표시하고_나머지는_다운로드한다(
            AttachUsage usage, String type, String disposition) {
        BoardAttachDTO attach = prepareAttachment(usage, type);
        when(access.canAccess(1L, "member", CafeBoardAccessLevel.MEMBER)).thenReturn(true);
        Resource file = new ByteArrayResource(new byte[] {1, 2, 3});
        when(storage.load(attach)).thenReturn(file);

        ResponseEntity<Resource> response = controller.view(10L, () -> "member");

        assertEquals(200, response.getStatusCodeValue());
        assertSame(file, response.getBody());
        assertEquals(disposition, response.getHeaders().getContentDisposition().getType());
        assertEquals("inline".equals(disposition) ? MediaType.IMAGE_PNG : MediaType.APPLICATION_OCTET_STREAM,
                response.getHeaders().getContentType());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }
}
