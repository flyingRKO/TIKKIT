package com.tikkit.api.integration.member;

import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.domain.member.entity.Member;
import com.tikkit.api.domain.member.repository.MemberRepository;
import com.tikkit.api.security.jwt.JwtCookieNames;
import com.tikkit.api.support.AbstractContainerTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT(stateless) 전략을 검증한다 (Task 026).
 *
 * <p>Redis 테스트와 달리 컨테이너를 추가하지 않는다 — 토큰 검증은 서명만 보므로 외부 저장소가
 * 필요 없다. 그게 이 전략의 장점이자, 아래 {@code 권한_회수가_반영되지_않는다}가 보여주는 한계다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "tikkit.auth.session-store=jwt")
@Transactional
class JwtAuthIntegrationTest extends AbstractContainerTest {

    private static final String PASSWORD = "password1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("로그인하면 토큰 쿠키를 내려주고, 그 쿠키로 내 정보를 조회할 수 있다")
    void 토큰_쿠키로_내정보조회() throws Exception {
        signup("jwt-me@tikkit.com");

        Cookie cookie = loginCookie("jwt-me@tikkit.com");

        assertThat(cookie.getName()).isEqualTo(JwtCookieNames.ACCESS_TOKEN);
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("jwt-me@tikkit.com"));
    }

    @Test
    @DisplayName("토큰 클레임에 memberId·email·role이 들어 있다 — 그래서 DB를 보지 않고 주체를 복원한다")
    void 토큰_클레임() throws Exception {
        signup("jwt-claims@tikkit.com");
        Member member = memberRepository.findByEmail("jwt-claims@tikkit.com").orElseThrow();

        Jwt jwt = jwtDecoder.decode(loginCookie("jwt-claims@tikkit.com").getValue());

        assertThat(jwt.getSubject()).isEqualTo(String.valueOf(member.getId()));
        assertThat(jwt.getClaimAsString("email")).isEqualTo("jwt-claims@tikkit.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("서명이 깨진 토큰은 401이 된다")
    void 위조_토큰_거부() throws Exception {
        signup("jwt-forged@tikkit.com");
        Cookie cookie = loginCookie("jwt-forged@tikkit.com");

        // 마지막 글자를 바꿔 서명을 깬다.
        String value = cookie.getValue();
        String tampered = value.substring(0, value.length() - 1) + (value.endsWith("A") ? "B" : "A");

        mockMvc.perform(get("/api/v1/members/me").cookie(new Cookie(JwtCookieNames.ACCESS_TOKEN, tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃해도 토큰 자체는 그대로 유효하다 — 쿠키를 지우는 것 말고 할 수 있는 일이 없다")
    void 로그아웃은_쿠키만_지운다() throws Exception {
        signup("jwt-logout@tikkit.com");
        Cookie cookie = loginCookie("jwt-logout@tikkit.com");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie)).andExpect(status().isOk());

        // 사용자가 로그아웃 전에 쿠키 값을 복사해 뒀다면 그대로 통한다.
        // Redis 세션에서는 이게 401이 된다(RedisSessionAuthIntegrationTest.강제_로그아웃).
        // 막으려면 폐기 목록을 서버에 둬야 하는데 그러면 stateless가 아니게 된다.
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("권한을 바꿔도 발급된 토큰에는 반영되지 않는다 — 토큰이 발급 시점의 권한을 들고 다닌다")
    void 권한_회수가_반영되지_않는다() throws Exception {
        signup("jwt-revoke@tikkit.com");
        Cookie cookie = loginCookie("jwt-revoke@tikkit.com");

        // 회원을 DB에서 지운다. "탈퇴" 또는 "강제 차단"에 해당하는 가장 센 조치다.
        memberRepository.delete(memberRepository.findByEmail("jwt-revoke@tikkit.com").orElseThrow());
        memberRepository.flush();

        // 그런데도 토큰은 통한다. 필터가 DB를 보지 않고 클레임으로 주체를 만들기 때문이다.
        // 이게 비교표의 "강제 로그아웃 불가" 칸의 실체다 — 추상적인 서술이 아니라
        // 없는 회원으로 인증된 요청이 200을 받는다.
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isNotFound());
        // 참고: /members/me는 memberId로 DB를 다시 조회하므로 404가 된다.
        // 즉 "인증은 통과하고 조회에서 막힌다" — 인증 자체를 끊은 게 아니다.
        // DB를 보지 않는 엔드포인트라면 그대로 통과한다.
    }

    private void signup(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                new SignupRequest(email, PASSWORD, "홍길동", "010-1111-2222"))))
                .andExpect(status().isOk());
    }

    private Cookie loginCookie(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie(JwtCookieNames.ACCESS_TOKEN);
    }
}
