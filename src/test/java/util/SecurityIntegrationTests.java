package util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import kr.or.oti.domain.MemberAuthVO;
import kr.or.oti.domain.MemberRole;
import kr.or.oti.domain.MemberVO;
import kr.or.oti.mapper.MemberMapper;
import kr.or.oti.security.CustomLoginSuccessHandler;
import kr.or.oti.security.CustomUserDetailsService;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(
    classes = SecurityIntegrationTests.TestConfig.class
)
@DirtiesContext(
    classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD
)
public class SecurityIntegrationTests {

    private static final String PASSWORD = "testPassword123";

    // 기존 보안 XML의 /** → hasRole('USER') 규칙이 적용되는 주소
    private static final String CHECK_URL = "/test/security/me";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        String encodedPassword = passwordEncoder.encode(PASSWORD);

        when(memberMapper.selectOne("user1"))
            .thenReturn(member("user1", encodedPassword));

        when(memberMapper.selectOne("user2"))
            .thenReturn(member("user2", encodedPassword));

        mockMvc = MockMvcBuilders
            .webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void 같은_계정의_새_로그인은_기존_세션을_만료시킨다()
            throws Exception {

        // 첫 번째 브라우저 로그인
        MockHttpSession firstSession = login("user1");
        assertAccessible(firstSession, "user1");

        // 두 번째 브라우저에서 같은 계정으로 로그인
        MockHttpSession secondSession = login("user1");

        // 기존 브라우저의 다음 요청에서 만료 처리
        mockMvc.perform(get(CHECK_URL).session(firstSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(
                redirectedUrl("/member/login?expired")
            )
            .andExpect(unauthenticated());

        assertTrue(firstSession.isInvalid());

        // 새 브라우저는 로그인 상태 유지
        assertAccessible(secondSession, "user1");
    }

    @Test
    void 서로_다른_계정의_로그인은_함께_유지된다()
            throws Exception {

        MockHttpSession firstSession = login("user1");
        MockHttpSession secondSession = login("user2");

        assertAccessible(firstSession, "user1");
        assertAccessible(secondSession, "user2");
    }

    @Test
    void 두번째_로그인의_비밀번호가_틀리면_기존_세션은_유지된다()
            throws Exception {

        MockHttpSession firstSession = login("user1");

        mockMvc.perform(
                formLogin("/login")
                    .user("user1")
                    .password("wrongPassword")
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(
                redirectedUrl("/member/login?error")
            )
            .andExpect(unauthenticated());

        assertAccessible(firstSession, "user1");
    }

    @Test
    void 로그아웃하면_세션이_무효화되고_다시_로그인할_수_있다()
            throws Exception {

        MockHttpSession firstSession = login("user1");

        mockMvc.perform(
                post("/logout")
                    .session(firstSession)
                    .with(csrf())
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(
                redirectedUrl("/member/login?logout")
            )
            .andExpect(unauthenticated());

        assertTrue(firstSession.isInvalid());

        MockHttpSession newSession = login("user1");
        assertAccessible(newSession, "user1");
    }

    // 로그인 요청마다 독립된 세션을 생성한다.
    private MockHttpSession login(String username)
            throws Exception {

        MvcResult result = mockMvc.perform(
                formLogin("/login")
                    .user(username)
                    .password(PASSWORD)
            )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/cafe"))
            .andExpect(authenticated().withUsername(username))
            .andReturn();

        MockHttpSession session =
            (MockHttpSession) result.getRequest().getSession(false);

        assertNotNull(session);

        return session;
    }

    // 해당 세션으로 보호된 주소에 접근 가능한지 확인한다.
    private void assertAccessible(
            MockHttpSession session,
            String username) throws Exception {

        mockMvc.perform(get(CHECK_URL).session(session))
            .andExpect(status().isOk())
            .andExpect(authenticated().withUsername(username))
            .andExpect(content().string(username));
    }

    // 실제 DB 대신 반환할 회원 데이터
    private MemberVO member(
            String username,
            String encodedPassword) {

        MemberAuthVO authority = MemberAuthVO.builder()
            .username(username)
            .authority(MemberRole.ROLE_USER)
            .build();

        return MemberVO.builder()
            .username(username)
            .password(encodedPassword)
            .enabled(true)
            .authList(Collections.singletonList(authority))
            .build();
    }

    @Configuration
    @EnableWebMvc
    @ImportResource(
        "file:src/main/webapp/WEB-INF/spring/security-context.xml"
    )
    public static class TestConfig {

        @Bean
        public MemberMapper memberMapper() {
            return mock(MemberMapper.class);
        }

        @Bean
        public CustomUserDetailsService customUserDetailsService(
                MemberMapper memberMapper) {

            return new CustomUserDetailsService(memberMapper);
        }

        @Bean
        public CustomLoginSuccessHandler customLoginSuccessHandler() {
            return new CustomLoginSuccessHandler();
        }

        @Bean
        public LoginCheckController loginCheckController() {
            return new LoginCheckController();
        }
    }

    // 인증 상태 확인만을 위한 테스트용 컨트롤러
    @RestController
    public static class LoginCheckController {

        @GetMapping(CHECK_URL)
        public String me(Principal principal) {
            return principal.getName();
        }
    }
}