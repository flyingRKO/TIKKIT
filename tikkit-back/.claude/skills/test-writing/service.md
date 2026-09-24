# 서비스 레이어 유닛 테스트

Repository 등 외부 의존성은 Mockito로 목업하고, 서비스가 가진 비즈니스 로직·검증·예외 처리만 검증한다.

## 기본 구조

```java
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private TicketGradeRepository ticketGradeRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("잔여 수량이 부족하면 예매에 실패한다")
    void 잔여_수량_부족_시_예외() {
        // given
        TicketGrade grade = TicketGrade.builder()
            .remainingQuantity(1)
            .build();
        given(ticketGradeRepository.findById(any())).willReturn(Optional.of(grade));

        // when & then
        assertThatThrownBy(() -> reservationService.reserve(request(2)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.SOLD_OUT);
    }
}
```

## 규칙

- Given-When-Then 구조를 유지하고, 각 블록을 주석으로 구분한다(주석은 한국어).
- `@DisplayName`은 한국어로, "무엇을 검증하는지"가 아니라 "어떤 상황에서 어떤 결과가 나오는지"를 적는다. (예: "잔여 수량이 부족하면 예매에 실패한다" — ✅ / "예매 테스트" — ❌)
- **성공 케이스와 실패/예외 케이스를 함께 작성한다.** 실패 케이스는 `docs/PRD.md` 부록 B의 에러 코드(`SOLD_OUT`, `RESERVATION_EXPIRED`, `BOOKING_NOT_OPEN`, `INVALID_STATUS_TRANSITION` 등)를 기준으로 빠짐없이 다룬다.
- `total_amount = unit_price * quantity` 같은 순수 계산 로직은 목업 없이 실제 값으로 검증한다 — 목업으로 감싸면 계산 버그를 못 잡는다.
- Repository는 항상 Mockito로 목업한다. 서비스 테스트에서 실제 DB에 접근하지 않는다(그건 `repository.md`/`integration.md`의 몫).
- 시간에 의존하는 로직(선점 만료 등)은 `Clock`을 주입받아 고정 시각으로 테스트한다. `Instant.now()`를 직접 호출하는 코드는 테스트하기 어렵다.
