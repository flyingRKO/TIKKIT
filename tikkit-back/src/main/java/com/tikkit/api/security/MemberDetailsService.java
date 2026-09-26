package com.tikkit.api.security;

import com.tikkit.api.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일(로그인 ID)로 회원을 조회해 인증 주체를 만든다.
 * 존재하지 않는 이메일이면 UsernameNotFoundException을 던지는데, DaoAuthenticationProvider가
 * 기본 설정(hideUserNotFoundExceptions=true)으로 이를 BadCredentialsException으로 감춰준다 —
 * "이메일이 없다"와 "비밀번호가 틀렸다"를 구분해서 응답하지 않기 위함(계정 존재 여부 노출 방지).
 */
@Service
@RequiredArgsConstructor
public class MemberDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return memberRepository.findByEmail(email.toLowerCase())
                .map(MemberDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException(email));
    }
}
