package controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

import kr.or.oti.controller.BoardController;
import kr.or.oti.domain.CafeBoardAccessLevel;
import kr.or.oti.dto.BoardDTO;
import kr.or.oti.dto.CafeBoardDTO;
import kr.or.oti.dto.PageRequestDTO;
import kr.or.oti.exception.BoardNotFoundException;
import kr.or.oti.service.*;

@ExtendWith(MockitoExtension.class)
class BoardControllerTests {
    @Mock private BoardService boards;
    @Mock private BoardAttachService attachments;
    @Mock private BoardWriteService writes;
    @Mock private CafeService cafes;
    @Mock private CafeBoardService cafeBoards;
    @Mock private CafeBoardAccessService access;
    private BoardController controller;
    private final Principal member = () -> "member";

    @BeforeEach
    void setUp() {
        controller = new BoardController(boards, attachments, writes, cafes, cafeBoards, access);
    }

    private void prepareBoard(String writer, Long boardId) {
        when(boards.get(10L)).thenReturn(BoardDTO.builder()
                .bno(10L).cafeBoardId(boardId).writer(writer).build());
        when(cafeBoards.get(1L, 2L)).thenReturn(CafeBoardDTO.builder()
                .cafeId(1L).cafeBoardId(2L).writeRole(CafeBoardAccessLevel.MEMBER).build());
    }

    private String modify(BoardDTO input) {
        return controller.modify(1L, 2L, 10L, input,
                new BeanPropertyBindingResult(input, "board"), new PageRequestDTO(),
                new ExtendedModelMap(), member, null, null, null, null);
    }

    @Test
    void 작성자가_아니면_수정과_삭제를_거부한다() {
        prepareBoard("other", 2L);
        when(access.canAccess(1L, "member", CafeBoardAccessLevel.MEMBER)).thenReturn(true);

        assertThrows(AccessDeniedException.class, () -> modify(new BoardDTO()));
        assertThrows(AccessDeniedException.class, () -> controller.remove(1L, 2L, 10L, member));

        verifyNoInteractions(writes);
        verify(boards, never()).remove(anyLong());
    }

    @Test
    void 작성자라도_게시판_쓰기_권한이_없으면_변경할_수_없다() {
        prepareBoard("member", 2L);
        when(access.canAccess(1L, "member", CafeBoardAccessLevel.MEMBER)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> modify(new BoardDTO()));
        assertThrows(AccessDeniedException.class, () -> controller.remove(1L, 2L, 10L, member));

        verifyNoInteractions(writes);
        verify(boards, never()).remove(anyLong());
    }

    @Test
    void URL에_다른_게시판의_글_번호를_넣으면_변경할_수_없다() {
        prepareBoard("member", 99L);

        assertThrows(BoardNotFoundException.class, () -> modify(new BoardDTO()));
        assertThrows(BoardNotFoundException.class, () -> controller.remove(1L, 2L, 10L, member));

        verifyNoInteractions(access, writes);
        verify(boards, never()).remove(anyLong());
    }

    @Test
    void 정상_수정은_요청의_작성자와_게시판번호_조작을_무시한다() {
        prepareBoard("member", 2L);
        when(access.canAccess(1L, "member", CafeBoardAccessLevel.MEMBER)).thenReturn(true);
        BoardDTO input = BoardDTO.builder().bno(999L).cafeBoardId(99L)
                .writer("forged").title("title").content("content").build();

        assertTrue(modify(input).startsWith("redirect:/cafe/1/board/2/post/10?"));

        verify(writes).modify(same(input), isNull(), isNull(), isNull(), isNull());
        assertEquals(10L, input.getBno());
        assertEquals(2L, input.getCafeBoardId());
        assertEquals("member", input.getWriter());
        assertEquals("redirect:/cafe/1/board/2/post",
                controller.remove(1L, 2L, 10L, member));
        verify(boards).remove(10L);
    }
}
