package com.tikkit.api.integration.member;

import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.support.AbstractRedisSessionContainerTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션을 Redis에 두는 전략을 검증한다 (Task 026).
 *
 * <p><b>MockHttpSession이 아니라 쿠키를 들고 다닌다.</b> {@code AuthIntegrationTest}는
 * {@code .session(mockHttpSession)}으로 세션 객체를 직접 넘기는데, 그러면 Spring Session의
 * {@code SessionRepositoryFilter}를 거치지 않아서 Redis에 저장되는지를 검증할 수 없다.
 * 로그인 응답의 {@code Set-Cookie}를 받아 다음 요청에 실어 보내는 것이 FE가 실제로 하는 일이고
 * ({@code tikkit-front/lib/api/client.ts}의 쿠키 중계), 그 경로가 Redis까지 닿는다.
 *
 * <p><b>쿠키 "이름"은 여기서 검증할 수 없다.</b> Boot의 {@code SessionAutoConfiguration}은
 * {@code server.servlet.session.cookie.*}를 {@code DefaultCookieSerializer}로 매핑하는 코드를
 * {@code EmbeddedWebServerConfiguration}(= {@code @ConditionalOnNotWarDeployment})에만 두고,
 * WAR 배포 분기는 대신 {@code ServletContext.getSessionCookieConfig()}를 읽는다.
 * {@code @SpringBootTest}의 MOCK 환경은 임베디드 서버가 없어 WAR 분기를 타므로,
 * {@code MockServletContext}의 빈 설정이 쓰이고 쿠키명이 Spring Session 기본값인
 * {@code SESSION}으로 나온다. 실제 컨테이너에서는 정상이다 — 실측:
 * {@code Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Lax}.
 * 쿠키명 회귀는 FE 로그인을 그대로 태우는 Playwright e2e가 잡는다.
 */
@AutoConfigureMockMvc
@Transactional
class RedisSessionAuthIntegrationTest extends AbstractRedisSessionContainerTest {

    private static final String PASSWORD = "password1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    /**
     * indexed 리포지토리일 때만 주입된다. default(RedisSessionRepository)는 이 타입이 아니므로
     * 이 빈이 주입되는 것 자체가 "repository-type: indexed가 실제로 먹었다"는 검증이다.
     */
    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Test
    @DisplayName("로그인하면 세션 쿠키를 정확히 하나 내려준다")
    void 로그인_세션쿠키_발급() throws Exception {
        signup("redis-cookie@tikkit.com");

        Cookie cookie = loginCookie("redis-cookie@tikkit.com");

        // 이름은 검증하지 않는다(클래스 주석 참조). FE의 Set-Cookie 파싱은
        // "쿠키가 하나뿐"이라는 전제에 기대고 있으므로 그 전제만 고정한다.
        assertThat(cookie).isNotNull();
    }

    @Test
    @DisplayName("로그인 쿠키로 내 정보를 조회할 수 있다 — 세션이 Redis에 저장되고 복원된다")
    void 쿠키로_내정보조회() throws Exception {
        signup("redis-me@tikkit.com");
        Cookie cookie = loginCookie("redis-me@tikkit.com");

        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("redis-me@tikkit.com"));
    }

    @Test
    @DisplayName("회원 식별자로 세션을 찾아 지우면 즉시 401이 된다 — 강제 로그아웃(권한 회수)이 된다")
    void 강제_로그아웃() throws Exception {
        // given — 로그인해서 Redis에 세션을 만든다
        signup("redis-revoke@tikkit.com");
        Cookie cookie = loginCookie("redis-revoke@tikkit.com");
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie)).andExpect(status().isOk());

        // when — principal 색인으로 그 회원의 세션을 찾는다.
        // 이 API(findByPrincipalName)는 indexed 리포지토리 전용이고, 비교표의
        // "강제 로그아웃 가능" 칸이 여기에 걸려 있다. default 리포지토리에는 이 색인이 없다.
        Map<String, ? extends Session> sessions = sessionRepository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "redis-revoke@tikkit.com");
        assertThat(sessions).hasSize(1);

        sessions.keySet().forEach(sessionRepository::deleteById);

        // then — 사용자가 들고 있는 쿠키는 그대로인데 서버가 세션을 지웠으므로 통하지 않는다.
        // JWT는 이게 불가능하다(서버가 상태를 안 들고 있어서 토큰을 만료 전에 무효화할 방법이 없다).
        mockMvc.perform(get("/api/v1/members/me").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    private void signup(String email) throws Exception {
        SignupRequest request = new SignupRequest(email, PASSWORD, "홍길동", "010-1234-5678");
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    /**
     * 쿠키를 이름으로 찾지 않는다 — MOCK 환경에서는 이름이 SESSION이고 실제 컨테이너에서는
     * JSESSIONID다(클래스 주석). 로그인 응답의 쿠키는 세션 쿠키 하나뿐이라 그걸 그대로 쓴다.
     */
    private Cookie loginCookie(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie[] cookies = result.getResponse().getCookies();
        return cookies.length == 1 ? cookies[0] : null;
    }
}
