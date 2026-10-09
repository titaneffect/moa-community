package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import kr.or.oti.domain.MemberAuthVO;
import kr.or.oti.domain.MemberRole;
import kr.or.oti.domain.MemberVO;
import kr.or.oti.dto.MemberDTO;
import kr.or.oti.exception.DuplicateUsernameException;
import kr.or.oti.exception.PasswordMismatchException;
import kr.or.oti.mapper.MemberMapper;
import kr.or.oti.service.MemberService;
import kr.or.oti.service.MemberServiceImpl;

@ExtendWith(MockitoExtension.class)
public class MemberServiceTests {

	@Mock
	private MemberMapper mapper;
	private MemberService service;
	
	@BeforeEach
	void setUp() {
		service = new MemberServiceImpl(mapper, new BCryptPasswordEncoder());
	}
	
	@Test
	void 정상적으로_회원과_ROLE_USER_권한이_저장된다() {
		// given: 중복되지 않은 아이디와 일치하는 비밀번호.
		MemberDTO member = MemberDTO.builder()
								.username("hong")
								.password("kosa1004")
								.passwordConfirm("kosa1004")
								.build();
		
		when(mapper.selectUsernameCount("hong")).thenReturn(0);
		
		// when
		service.register(member);
		
		// then
		ArgumentCaptor<MemberVO> memberCaptor =
				ArgumentCaptor.forClass(MemberVO.class);
		ArgumentCaptor<MemberAuthVO> authCaptor =
				ArgumentCaptor.forClass(MemberAuthVO.class);
		
		verify(mapper).insertOne(memberCaptor.capture());
		verify(mapper).insertAuthority(authCaptor.capture());
		
		assertEquals("hong", memberCaptor.getValue().getUsername());
		assertEquals("hong", authCaptor.getValue().getUsername());
		assertEquals(MemberRole.ROLE_USER,
				authCaptor.getValue().getAuthority());
	}
	
	@Test
	void 중복된_아이디로_가입할_수_없다() {
		// given: hong이라는 아이디가 이미 존재한다.
		MemberDTO member = MemberDTO.builder()
								.username("hong")
								.password("kosa1004")
								.passwordConfirm("kosa1004")
								.build();
		
		when(mapper.selectUsernameCount("hong")).thenReturn(1);
		
		// when & then: 가입은 거부되고 회원, 권한은 저장되지 않는다.
		assertThrows(DuplicateUsernameException.class,
				() -> service.register(member));
		
		verify(mapper).selectUsernameCount("hong");
		verify(mapper, never()).insertOne(any(MemberVO.class));
		verify(mapper, never()).insertAuthority(any(MemberAuthVO.class));
	
	}
	
	@Test
	void 비밀번호와_비밀번호확인이_다르면_가입할_수_없다() {
		// given: 중복되지 않은 아이디지만 비밀번호 두 값이 다르다.
		MemberDTO member = MemberDTO.builder()
								.username("hong")
								.password("kosa1004")
								.passwordConfirm("kosa2004")
								.build();
		
		when(mapper.selectUsernameCount("hong")).thenReturn(0);
		
		// when & then: 비밀번호 불일치로 가입이 거부된다.
		assertThrows(PasswordMismatchException.class,
				() -> service.register(member));
		
		verify(mapper, never()).insertOne(any(MemberVO.class));
		verify(mapper, never()).insertAuthority(any(MemberAuthVO.class));
	
	}
	
	@Test
	void 회원가입시_비밀번호가_BCrypt로_암호화된다() {
		// given: 중복되지 않은 아이디이며 비밀번호 두 값이 일치한다.
		MemberDTO member = MemberDTO.builder()
								.username("hong")
								.password("kosa1004")
								.passwordConfirm("kosa1004")
								.build();
		
		when(mapper.selectUsernameCount("hong")).thenReturn(0);		
		
		// when
		service.register(member);
		
		// then
		ArgumentCaptor<MemberVO> captor =
				ArgumentCaptor.forClass(MemberVO.class);
		
		verify(mapper).insertOne(captor.capture());
		
		String savedPassword = captor.getValue().getPassword();
		
		assertNotEquals("kosa1004", savedPassword);
		assertTrue(new BCryptPasswordEncoder().matches("kosa1004", savedPassword));
		
	}
	
}
