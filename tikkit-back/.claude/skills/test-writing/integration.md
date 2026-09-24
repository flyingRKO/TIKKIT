# 통합 테스트 (컨트롤러 포함)

전체 스프링 컨텍스트 + Testcontainers로 실제 `/api/v1/...` 엔드포인트를 호출해, 서비스·레포지토리·컨트롤러가 함께 맞물려 동작하는지 확인한다. **이 계층이 컨트롤러를 실제로 태우기 때문에, 별도의 `@WebMvcTest` 컨트롤러 단독 테스트는 만들지 않는다.**

## 언제 작성하는가

같은 기능의 서비스 유닛 테스트(`service.md`)와 레포지토리 테스트(`repository.md`)가 각각 잘 작성되어 있다면, 그 기능의 API 엔드포인트에 통합 테스트 1세트를 추가한다. 서비스/레포지토리 테스트가 아직 없는 상태에서 통합 테스트부터 쓰지 않는다 — 실패 원인을 좁히기 어려워진다.

## 기본 구조

```java
class ReservationApiIntegrationTest extends AbstractContainerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("판매 기간이 아니면 예매 요청은 400과 BOOKING_NOT_OPEN을 반환한다")
    void 판매_기간_아님() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(requestJson(scheduleId, ticketGradeId, 2)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_OPEN"));
    }
}
```

## 규칙

- `AbstractContainerTest`가 이미 `@SpringBootTest` + Testcontainers PostgreSQL을 갖추고 있으므로, `MockMvc`만 `@Autowired`로 주입받아 쓴다. `repository.md`와 같은 베이스 클래스를 그대로 재사용한다.
- 이 계층에서 함께 확인해야 컨트롤러 단독 테스트가 필요 없어지는 항목:
  - 요청 검증(`@Valid`)이 400을 반환하는지
  - 인증/인가(`Authorization` 헤더 누락·타인 리소스 접근)가 401/403/404로 매핑되는지
  - `GlobalExceptionHandler`가 `BusinessException`을 `ApiResponse` 포맷(`{success, code, message, errors}`)으로 변환하는지
- **동시성 재현 테스트도 이 계층에 속한다.** `ExecutorService` + `CountDownLatch`로 여러 스레드가 동시에 같은 엔드포인트를 호출하게 만들고, 실제 DB 락 전략(조건부 UPDATE, 낙관적/비관적 락)이 초과 판매를 막는지 검증한다. Task 018의 "100 스레드 vs 10석" 재현 테스트가 이 패턴의 기준 예시다.
- 통합 테스트는 느리다. 모든 조합을 여기서 테스트하지 말고, "계층 간 연결"과 "동시성"처럼 유닛 테스트로는 확인할 수 없는 것만 다룬다. 세부 분기 로직은 이미 `service.md`의 유닛 테스트가 다뤘다고 가정한다.
