package service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import kr.or.oti.domain.BoardAttachVO;
import kr.or.oti.mapper.BoardAttachMapper;
import kr.or.oti.service.BoardAttachServiceImpl;
import kr.or.oti.service.FileStorageService;

class BoardAttachServiceTests {
    @Test
    void 다른_게시글의_첨부파일_번호를_넘겨도_삭제되지_않는다() {
        BoardAttachMapper mapper = mock(BoardAttachMapper.class);
        FileStorageService storage = mock(FileStorageService.class);
        BoardAttachServiceImpl service = new BoardAttachServiceImpl(mapper, storage);
        when(mapper.selectOne(10L)).thenReturn(BoardAttachVO.builder()
                .ano(10L).bno(2L).build());

        assertThrows(AccessDeniedException.class, () -> service.remove(1L, 10L));

        verify(mapper, never()).deleteOne(10L);
        verifyNoInteractions(storage);
    }
}
