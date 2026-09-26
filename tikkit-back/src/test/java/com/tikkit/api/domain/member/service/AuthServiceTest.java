package com.tikkit.api.domain.member.service;

import com.tikkit.api.common.exception.BusinessException;
import com.tikkit.api.common.exception.ErrorCode;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.dto.SignupResponse;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("이메일이 중복되지 않으면 비밀번호를 암호화해 회원을 저장한다")
    void 회원가입_성공() {
        // given
        SignupRequest request = new SignupRequest("User@Tikkit.com", "password1234", "홍길동", "010-1111-2222");
        given(memberRepository.existsByEmail("user@tikkit.com")).willReturn(false);
        given(passwordEncoder.encode("password1234")).willReturn("encoded-password");
        given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        SignupResponse response = authService.signup(request);

        // then
        assertThat(response.email()).isEqualTo("user@tikkit.com");
        assertThat(response.name()).isEqualTo("홍길동");

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getPassword()).isEqualTo("encoded-password");
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL 예외를 던진다")
    void 이메일_중복_시_예외() {
        // given
        SignupRequest request = new SignupRequest("user@tikkit.com", "password1234", "홍길동", "010-1111-2222");
        given(memberRepository.existsByEmail("user@tikkit.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }
}
