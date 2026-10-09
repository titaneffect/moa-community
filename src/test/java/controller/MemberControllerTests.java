package controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.or.oti.controller.MemberController;
import kr.or.oti.dto.MemberDTO;
import kr.or.oti.exception.DuplicateUsernameException;
import kr.or.oti.exception.PasswordMismatchException;
import kr.or.oti.service.MemberService;

@ExtendWith(MockitoExtension.class)
public class MemberControllerTests {

	@Mock
	private MemberService memberService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new MemberController(memberService)).build();
	}

	@Test
	void 유효한_회원가입_요청은_로그인_화면으로_이동한다() throws Exception {
		// when : 회원가입 양식을 POST로 제출한다.
		mockMvc.perform(post("/member/register").param("username", "hong").param("password", "kosa1004")
				.param("passwordConfirm", "kosa1004"))
				// then : 가입 완료 후 로그인 화면으로 이동한다.
				.andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/member/login?registered"));

		// 전달된 입력값으로 서비스가 호출됐는지도 확인한다.
		ArgumentCaptor<MemberDTO> captor = ArgumentCaptor.forClass(MemberDTO.class);

		verify(memberService).register(captor.capture());
		assertEquals("hong", captor.getValue().getUsername());
	}

	@Test
	void 입력값이_잘못되면_회원가입_화면을_다시_보여준다() throws Exception {
		mockMvc.perform(post("/member/register").param("username", "").param("password", "kosa1004")
				.param("passwordConfirm", "kosa1004")).andExpect(status().isOk())
				.andExpect(view().name("member/register"))
				.andExpect(model().attributeHasFieldErrors("member", "username"));

		verifyNoInteractions(memberService);
	}

	@Test
	void 중복_아이디_오류는_username_필드에_표시한다() throws Exception {
	    // given: 서비스가 중복 아이디 예외를 던진다
	    doThrow(new DuplicateUsernameException("이미 사용 중인 아이디입니다."))
	            .when(memberService)
	            .register(any(MemberDTO.class));

	    // when & then
	    mockMvc.perform(post("/member/register")
	            .param("username", "hong")
	            .param("password", "kosa1004")
	            .param("passwordConfirm", "kosa1004"))
	        .andExpect(status().isOk())
	        .andExpect(view().name("member/register"))
	        .andExpect(model().attributeHasFieldErrors("member", "username"));

	    verify(memberService).register(any(MemberDTO.class));
	}


	// @Test
	void 비밀번호_불일치_오류는_passwordConfirm_필드에_표시한다()
	        throws Exception {
	    // given: 서비스가 비밀번호 불일치 예외를 던진다
	    doThrow(new PasswordMismatchException("비밀번호가 일치하지 않습니다."))
	            .when(memberService)
	            .register(any(MemberDTO.class));

	    // when & then
	    mockMvc.perform(post("/member/register")
	            .param("username", "hong")
	            .param("password", "kosa1004")
	            .param("passwordConfirm", "kosa2004"))
	        .andExpect(status().isOk())
	        .andExpect(view().name("member/register"))
	        .andExpect(model().attributeHasFieldErrors(
	                "member", "passwordConfirm"));

	    verify(memberService).register(any(MemberDTO.class));
	}

	@Test
	void 안전한_returnUrl은_로그인_후_복귀_주소로_저장한다() {

	}

	@Test
	void 외부_사이트_returnUrl은_복귀_주소로_사용하지_않는다() {

	}

}
