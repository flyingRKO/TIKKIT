package com.tikkit.api.security.login;

import com.tikkit.api.security.MemberDetails;
import com.tikkit.api.security.jwt.JwtCookieNames;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;

/**
 * 로그인 성공을 서명된 토큰으로 만들어 쿠키에 담는다 (Task 026).
 *
 * <p>세션 방식과 달라지는 지점이 로그아웃이다. 세션은 서버가 들고 있으니 지울 수 있는데,
 * 토큰은 서버에 아무것도 없어서 <b>쿠키를 지우는 것 말고 할 수 있는 일이 없다.</b>
 * 사용자가 쿠키 값을 미리 복사해 뒀다면 만료까지 계속 통한다. 이게 "강제 로그아웃 불가"의
 * 실체이고, 막으려면 폐기 목록(블랙리스트)을 서버에 둬야 하는데 그러면 stateless가 아니게 된다.
 */
@RequiredArgsConstructor
public class JwtLoginCredentialWriter implements LoginCredentialWriter {

    private final JwtEncoder jwtEncoder;
    private final Duration tokenTtl;

    @Override
    public void write(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        MemberDetails member = (MemberDetails) authentication.getPrincipal();

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                // sub에 memberId를 넣는다. 컨트롤러들이 memberDetails.getMemberId()만 쓰므로
                // 이 셋(sub/email/role)이면 DB 조회 없이 인증 주체를 복원할 수 있다.
                .subject(String.valueOf(member.getMemberId()))
                .claim("email", member.getUsername())
                // authorities는 "ROLE_USER" 형태다. MemberRole로 되돌리기 위해 접두사를 뗀다.
                .claim("role", member.getAuthorities().iterator().next().getAuthority().replaceFirst("^ROLE_", ""))
                .issuedAt(now)
                // 세션 타임아웃(30m)과 같은 값을 주지만 **의미가 다르다.**
                // 세션은 요청마다 만료가 밀리는 슬라이딩이고 exp는 발급 기준 절대 시각이다.
                // 즉 JWT 사용자는 활동 중에도 30분이 되면 끊긴다 — 비교표에 적을 차이다.
                .expiresAt(now.plus(tokenTtl))
                .build();

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)
        ).getTokenValue();

        SecurityContextHolder.getContext().setAuthentication(authentication);
        response.addCookie(tokenCookie(token, (int) tokenTtl.toSeconds()));
    }

    @Override
    public void clear(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        // maxAge 0이 "지금 지워라"다. 서버에는 지울 것이 없다.
        response.addCookie(tokenCookie("", 0));
    }

    private Cookie tokenCookie(String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(JwtCookieNames.ACCESS_TOKEN, value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Lax");
        // secure는 켜지 않는다 — 평문 HTTP 로컬 스택이라 켜면 쿠키가 아예 안 내려간다(Task 034).
        cookie.setMaxAge(maxAgeSeconds);
        return cookie;
    }
}
