package com.tikkit.api.security;

import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.entity.MemberRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 인증 주체. SecurityContext에서 로그인한 회원의 memberId를 바로 꺼내 쓰기 위한 래퍼.
 */
@Getter
public class MemberDetails implements UserDetails {

    private final Long memberId;
    private final String email;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public MemberDetails(Member member) {
        this.memberId = member.getId();
        this.email = member.getEmail();
        this.password = member.getPassword();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));
    }

    /**
     * 토큰 클레임에서 복원할 때 쓴다 (Task 026 JWT 전략).
     *
     * <p>DB를 보지 않는 것이 요점이다 — 매 요청 회원을 조회하면 "stateless"의 이점이 사라지고
     * 세션 방식보다 느려진다. 대가는 <b>토큰이 발급 시점의 권한을 그대로 들고 다닌다</b>는 것이고,
     * 그래서 권한을 회수해도 만료까지 반영되지 않는다. 이 트레이드오프가 비교 측정의 핵심이다.
     *
     * <p>{@code password}는 null이다. 인증이 이미 끝난 뒤 복원하는 용도라 비밀번호가 필요 없다.
     */
    public MemberDetails(Long memberId, String email, MemberRole role) {
        this.memberId = memberId;
        this.email = email;
        this.password = null;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }
}
