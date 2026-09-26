package com.tikkit.api.domain.member.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tikkit.api.domain.member.dto.LoginRequest;
import com.tikkit.api.domain.member.dto.SignupRequest;
import com.tikkit.api.support.AbstractContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원가입·로그인·로그아웃 전체 흐름을 세션 기반으로 검증한다.
 * MockMvc가 실제 SecurityFilterChain(SecurityConfig)까지 태우므로 별도의 @WebMvcTest는 만들지 않는다.
 */
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest extends AbstractContainerTest {

    private static final String PASSWORD = "password1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("가입 후 로그인하면 세션이 발급되고, 그 세션으로 내 정보 조회에 성공한다")
    void 가입_로그인_내정보조회() throws Exception {
        // given
        signup("user1@tikkit.com");

        // when
        MvcResult loginResult = login("user1@tikkit.com");
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // then
        mockMvc.perform(get("/api/v1/members/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("user1@tikkit.com"));
    }

    @Test
    @DisplayName("로그인 전에 세션이 있었다면, 세션 고정 방지를 위해 로그인 후 세션 ID가 바뀐다")
    void 로그인_시_세션ID_재발급() throws Exception {
        // given
        signup("user2@tikkit.com");
        MvcResult beforeLogin = mockMvc.perform(get("/api/v1/performances")).andReturn();
        // 이 요청에서 세션을 강제로 생성해, 로그인 전에 이미 세션이 있었던 상황(예: 쿠키 선점)을 만든다.
        MockHttpSession sessionBeforeLogin = (MockHttpSession) beforeLogin.getRequest().getSession(true);
        // changeSessionId()는 같은 MockHttpSession 객체의 id를 그 자리에서 바꾸므로, 로그인 요청을 보내기 전에
        // 문자열로 미리 떼어놔야 한다 — 그러지 않으면 나중에 sessionBeforeLogin.getId()를 불러도 이미 바뀐 값이 나온다.
        String sessionIdBeforeLogin = sessionBeforeLogin.getId();

        // when
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .session(sessionBeforeLogin)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("user2@tikkit.com", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        // then
        String sessionIdAfterLogin = loginResult.getRequest().getSession(false).getId();
        assertThat(sessionIdAfterLogin).isNotEqualTo(sessionIdBeforeLogin);
    }

    @Test
    @DisplayName("로그아웃하면 세션이 무효화되어 이후 내 정보 조회는 401을 반환한다")
    void 로그아웃_후_내정보조회_실패() throws Exception {
        // given
        signup("user3@tikkit.com");
        MvcResult loginResult = login("user3@tikkit.com");
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // when
        mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isOk());

        // then
        mockMvc.perform(get("/api/v1/members/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 이메일이 존재하는지 여부와 무관하게 401과 INVALID_CREDENTIALS를 반환한다")
    void 잘못된_비밀번호_로그인_실패() throws Exception {
        // given
        signup("user4@tikkit.com");

        // when & then
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("user4@tikkit.com", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("로그인하지 않고 내 정보를 조회하면 ApiResponse 포맷의 401을 반환한다")
    void 미로그인_내정보조회_실패() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void signup(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest(email, PASSWORD, "홍길동", "010-1111-2222"))))
                .andExpect(status().isOk());
    }

    private MvcResult login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn();
    }
}
