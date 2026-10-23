package com.tikkit.api.integration.queue;

import com.tikkit.api.support.AbstractRedisQueueContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 입장권 검증이 실제 요청 경로에서 동작하는지 (Task 031).
 *
 * <p>로드맵의 "좌석·예매 API에 토큰 검증 인터셉터 적용"을 실증하는 테스트다. 두 경로가 서로
 * 다른 수단을 쓰기 때문에 양쪽을 다 본다 — 좌석 조회는 인터셉터, 예매 선점은
 * {@code ReservationService}의 가드 호출({@code scheduleId}가 요청 본문에 있어 인터셉터가
 * 읽을 수 없다).
 */
class QueuePassInterceptorTest extends AbstractRedisQueueContainerTest {

    @Autowired
    private MockMvc mockMvc;

    private long scheduleId;
    private long gradeId;
    private List<Long> seatIds;
    private MockHttpSession session;
    private long memberId;

    @BeforeEach
    void setUpQueue() {
        GradeWithSeats fixture = createOnSaleGradeWithSeats(10);
        scheduleId = fixture.scheduleId();
        gradeId = fixture.gradeId();
        seatIds = fixture.scheduleSeatIds();
        session = createMembersWithSession(1).get(0);
        memberId = memberIdOf(session);
        deleteQueue(scheduleId);
    }

    @Test
    @DisplayName("플래그가 꺼진 회차는 입장권 없이도 좌석을 조회할 수 있다 — 기존 동작이 그대로 유지된다")
    void 꺼진_회차는_입장권_없이_통과한다() throws Exception {
        disableQueue(scheduleId);

        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId).session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("플래그가 꺼진 회차는 비로그인으로도 좌석을 조회할 수 있다 — Task 022의 공개 조회가 보존된다")
    void 꺼진_회차는_비로그인도_통과한다() throws Exception {
        disableQueue(scheduleId);

        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("입장권 없이 좌석을 조회하면 403 QUEUE_PASS_REQUIRED이고, 응답이 ApiResponse 포맷이다")
    void 입장권_없이_좌석_조회는_403이다() throws Exception {
        enableQueue(scheduleId);

        // 응답 바디를 단정하는 게 핵심이다. 인터셉터가 던진 BusinessException이
        // GlobalExceptionHandler를 탔다는 증거다 — 안 탔으면 500 + 스택트레이스가 나간다.
        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId).session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("QUEUE_PASS_REQUIRED"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("대기열을 켠 회차에 비로그인으로 접근하면 401이다 — 할 일이 줄 서기가 아니라 로그인이다")
    void 켜진_회차에_비로그인_접근은_401이다() throws Exception {
        enableQueue(scheduleId);

        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("입장권이 있으면 좌석을 조회할 수 있다")
    void 입장권이_있으면_통과한다() throws Exception {
        enableQueue(scheduleId);
        admitNow();

        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId).session(session))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("만료된 입장권으로 좌석을 조회하면 403 QUEUE_PASS_EXPIRED다")
    void 만료된_입장권은_403이다() throws Exception {
        enableQueue(scheduleId);
        queueRepository.admit(scheduleId, List.of(memberId), Instant.now().minusSeconds(1));

        mockMvc.perform(get("/api/v1/schedules/{id}/seats", scheduleId).session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_PASS_EXPIRED"));
    }

    @Test
    @DisplayName("등급 조회는 보호하지 않는다 — 대기열 화면이 공연·가격을 보여줘야 한다")
    void 등급_조회는_보호하지_않는다() throws Exception {
        enableQueue(scheduleId);

        mockMvc.perform(get("/api/v1/schedules/{id}/ticket-grades", scheduleId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("입장권 없이 예매 선점을 요청하면 403이다 — scheduleId가 본문에 있어 서비스가 막는다")
    void 입장권_없이_예매_선점은_403이다() throws Exception {
        enableQueue(scheduleId);

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUEUE_PASS_REQUIRED"));
    }

    @Test
    @DisplayName("입장권이 있으면 예매 선점이 된다")
    void 입장권이_있으면_예매_선점이_된다() throws Exception {
        enableQueue(scheduleId);
        admitNow();

        mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequest()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("입장권이 만료돼도 이미 잡은 예약은 결제할 수 있다 — 결제는 보호 대상이 아니다")
    void 입장권이_만료돼도_자기_예약은_결제할_수_있다() throws Exception {
        enableQueue(scheduleId);
        admitNow();

        String reservationBody = mockMvc.perform(post("/api/v1/reservations")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequest()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reservationId = reservationIdOf(reservationBody);

        // 입장권을 만료시킨다. 선점 홀드는 10분이고 입장권은 15분이라 정상 흐름에서는 안 겹치지만,
        // 겹치는 순간 사용자가 돈을 낼 수도 좌석을 돌려받을 수도 없는 상태가 되면 안 된다.
        queueRepository.admit(scheduleId, List.of(memberId), Instant.now().minusSeconds(1));

        mockMvc.perform(post("/api/v1/reservations/{id}/payments", reservationId)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"CARD\"}"))
                .andExpect(status().isOk());
    }

    private void admitNow() {
        queueRepository.admit(scheduleId, List.of(memberId), Instant.now().plusSeconds(900));
    }

    private String reservationRequest() {
        return """
                {"scheduleId":%d,"ticketGradeId":%d,"quantity":1,"seatIds":[%d]}
                """.formatted(scheduleId, gradeId, seatIds.get(0));
    }

    /** 응답 JSON에서 예약 id만 뽑는다. 파서를 들이지 않고 정규식으로 끊는다. */
    private static long reservationIdOf(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("예약 id를 찾을 수 없다: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }
}
