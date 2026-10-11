package service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.or.oti.domain.CafeBoardAccessLevel;
import kr.or.oti.domain.CafeMemberStatus;
import kr.or.oti.domain.CafeRole;
import kr.or.oti.dto.CafeMemberDTO;
import kr.or.oti.service.CafeBoardAccessService;
import kr.or.oti.service.CafeMemberService;

@ExtendWith(MockitoExtension.class)
class CafeBoardAccessServiceTests {
    @Mock private CafeMemberService members;
    private CafeBoardAccessService access;

    @BeforeEach
    void setUp() {
        access = new CafeBoardAccessService(members);
    }

    @Test
    void 공개_게시판은_비로그인_사용자도_접근할_수_있다() {
        assertTrue(access.canAccess(1L, null, CafeBoardAccessLevel.GUEST));
        verifyNoInteractions(members);
    }

    @ParameterizedTest
    @CsvSource({"MEMBER", "MANAGER", "OWNER"})
    void 로그인하지_않으면_회원_전용_권한을_얻을_수_없다(CafeBoardAccessLevel level) {
        assertFalse(access.canAccess(1L, null, level));
        verifyNoInteractions(members);
    }

    @Test
    void 활동_회원이_조회되지_않으면_접근을_거부한다() {
        // get은 ACTIVE 회원만 반환한다. 실제 SQL의 상태 필터는 DB 통합 테스트 범위이다.
        when(members.get(1L, "guest")).thenReturn(null);
        for (CafeBoardAccessLevel level : new CafeBoardAccessLevel[] {
                CafeBoardAccessLevel.MEMBER, CafeBoardAccessLevel.MANAGER, CafeBoardAccessLevel.OWNER}) {
            assertFalse(access.canAccess(1L, "guest", level));
        }
    }

    @ParameterizedTest(name = "{0} 역할의 {1} 접근 허용: {2}")
    @CsvSource({
        "MEMBER, MEMBER, true", "MEMBER, MANAGER, false", "MEMBER, OWNER, false",
        "MANAGER, MEMBER, true", "MANAGER, MANAGER, true", "MANAGER, OWNER, false",
        "OWNER, MEMBER, true", "OWNER, MANAGER, true", "OWNER, OWNER, true"
    })
    void 활동_회원의_역할에_따라_권한을_구분한다(
            CafeRole role, CafeBoardAccessLevel required, boolean expected) {
        when(members.get(1L, "member")).thenReturn(CafeMemberDTO.builder()
                .cafeId(1L).memberUsername("member").status(CafeMemberStatus.ACTIVE)
                .cafeRole(role).build());

        assertEquals(expected, access.canAccess(1L, "member", required));
    }
}
