package service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import kr.or.oti.domain.CafeMemberStatus;
import kr.or.oti.domain.CafeMemberVO;
import kr.or.oti.domain.CafeRole;
import kr.or.oti.mapper.CafeMemberMapper;
import kr.or.oti.service.CafeMemberServiceImpl;

@ExtendWith(MockitoExtension.class)
class CafeMemberServiceTests {
    @Mock private CafeMemberMapper mapper;
    private CafeMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CafeMemberServiceImpl(mapper);
    }

    @Test
    void 첫_가입은_활동_회원이_아닌_승인_대기로_저장한다() {
        when(mapper.insertOne(any(CafeMemberVO.class))).thenReturn(1);

        service.join(1L, "member");

        ArgumentCaptor<CafeMemberVO> saved = ArgumentCaptor.forClass(CafeMemberVO.class);
        verify(mapper).insertOne(saved.capture());
        assertEquals(1L, saved.getValue().getCafeId());
        assertEquals("member", saved.getValue().getMemberUsername());
        assertEquals(CafeRole.MEMBER, saved.getValue().getCafeRole());
        assertEquals(CafeMemberStatus.PENDING, saved.getValue().getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = CafeMemberStatus.class, names = {"ACTIVE", "PENDING", "BANNED"})
    void 활동_대기_강퇴_상태에서는_가입_신청을_중복_저장하지_않는다(CafeMemberStatus status) {
        when(mapper.selectOneAllStatus(1L, "member")).thenReturn(CafeMemberVO.builder()
                .cafeId(1L).memberUsername("member").status(status).build());

        if (status == CafeMemberStatus.BANNED) {
            assertThrows(AccessDeniedException.class, () -> service.join(1L, "member"));
        } else {
            assertThrows(IllegalStateException.class, () -> service.join(1L, "member"));
        }
        verify(mapper, never()).insertOne(any());
        verify(mapper, never()).reactivate(any(), any());
    }
}
