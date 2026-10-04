# 001. 초과 판매 재현 (Task 018)

| | |
|---|---|
| 관련 Task | Task 018 (Phase 5: 동시성 제어 고도화) |
| 대상 코드 | `ReservationService.create()`, `ReservationService.pay()`, `TicketGrade.decreaseRemaining()` |
| 해결 | **이번 Task에서는 하지 않는다** — Task 019(DB 락 전략 적용 및 비교) |
| 작성 시점 | 2026-10-03 (`v0.1.0-mvp` 직후) |

## 1. 배경과 목표

MVP는 예매 선점의 동시성을 **의도적으로 보장하지 않은 채** 출시했다. Task 012에서 재고 차감을
`remaining_quantity`를 읽어 빼고 더티체킹으로 반영하는 가장 단순한 방식으로 구현하고, 그 사실을
코드 주석(`ReservationService.java:41-45`, `TicketGrade.java:61-65`)과 README "알려진 한계"에 남겼다.

Task 018의 목표는 **고치는 것이 아니라 증명하는 것**이다. 초과 판매가 실제로 발생하는지, 어느 규모로
발생하는지를 테스트로 재현하고 수치를 남긴다. 이 수치가 Task 019에서 비관적 락 / 낙관적 락 /
조건부 UPDATE 세 가지를 비교할 before 기준선이 된다. 락을 먼저 걸면 문제가 있었다는 증거도,
비교 기준선도 사라진다.

재현 대상은 README가 "알려진 한계"로 기록한 두 가지다.

| 대상 | README 섹션 | 재현 여부 |
|---|---|---|
| 재고 차감 lost update | 알려진 한계: 동시성 | ✅ 재현 |
| 결제 ↔ 만료 배치 경쟁 | 알려진 한계: 결제-만료 배치 경쟁 | ✅ 재현 |
| PG 호출이 트랜잭션 안에 있음 | 알려진 한계: 결제 게이트웨이 호출이 트랜잭션 안에 있음 | ❌ 재현 대상 아님 (아래 6절) |

## 2. 재현 환경

| 항목 | 값 |
|---|---|
| DB | PostgreSQL 15 (Testcontainers), 격리 수준 READ COMMITTED (Postgres 기본값) |
| HikariCP 풀 | `maximum-pool-size=30`, `minimum-idle=30` (커넥션 선생성) |
| 스케줄러 | 끔 (`SchedulingConfig`가 `@Profile("!test")`) — 만료 배치는 리포지토리 메서드를 직접 호출 |
| 테스트 | `tikkit-back/src/test/java/com/tikkit/api/integration/reservation/ReservationConcurrencyTest.java` |
| 실행 | `./gradlew test --tests '*ReservationConcurrencyTest*'` |

테스트는 `@Transactional`을 쓰지 않는다. 테스트 스레드가 열어둔 트랜잭션은 커밋 전까지 워커 스레드에서
보이지 않아서, 롤백 격리 방식으로는 여러 스레드가 같은 재고 행을 두고 경쟁하는 상황 자체를 만들 수 없다.
그래서 데이터를 실제로 커밋하고 `TRUNCATE ... RESTART IDENTITY CASCADE`로 직접 정리한다
(`AbstractConcurrencyTest.java`).

### 재현을 막던 장애물: 중복 선점 가드

`ReservationService.java:62-65`가 `(memberId, ticketGradeId, PENDING)` 단위로 중복 선점을 막는다.
**같은 회원으로 100번 요청하면 1건만 성공하고 99건이 `DUPLICATE_PENDING_RESERVATION`(409)으로 떨어져
초과 판매가 재현되지 않는다.** 그래서 테스트는 서로 다른 회원 100명을 미리 만든다.

회원 100명을 signup + login API로 만들면 BCrypt가 200번(해싱 100 + 검증 100) 돌아 재현과 무관한 시간이
수십 초 늘어난다. 비밀번호 인코딩을 1회로 줄이고 세션에 `SecurityContext`를 직접 심어 해결했다.

## 3. 시나리오 A — 10석에 100명 동시 선점

### 재현 결과

재고 10석 등급에 서로 다른 회원 100명이 `POST /api/v1/reservations`로 1매씩 동시 요청했다.
4회 반복 실측치다.

| 회차 | 요청 | 201 성공 | 409 SOLD_OUT | 판매 수량 합 | 잔여 | 총재고 |
|---|---|---|---|---|---|---|
| 1 | 100 | 100 | 0 | **100** | 7 | 10 |
| 2 | 100 | 100 | 0 | **100** | 9 | 10 |
| 3 | 100 | 100 | 0 | **100** | 6 | 10 |
| 4 | 100 | 100 | 0 | **100** | 5 | 10 |

**10석에 100매가 팔렸다. 거부된 요청은 0건이다.** 재고 불변식
`remaining_quantity = total_quantity - SUM(활성 예약 수량)`도 `7 ≠ 10 - 100`으로 완전히 깨졌다.

### 원인: lost update

`ReservationService.java:47`이 `findById()`로 재고를 읽고(일반 SELECT, 락 없음),
`:67`이 `TicketGrade.decreaseRemaining()`을 호출한다.

```java
// TicketGrade.java:66-71
public void decreaseRemaining(int quantity) {
    if (remainingQuantity < quantity) {   // 메모리에서 검증
        throw new BusinessException(ErrorCode.SOLD_OUT);
    }
    this.remainingQuantity -= quantity;   // 메모리에서 차감
}
```

커밋 시점에 더티체킹이 `UPDATE ticket_grades SET remaining_quantity = ? WHERE id = ?`를 날린다.
**조건이 `id`뿐이고 값은 절대값**이라는 점이 핵심이다.

```
시각   트랜잭션 A                      트랜잭션 B
t1    remaining=10 읽음
t2                                  remaining=10 읽음
t3    10 >= 1 통과, 9로 계산
t4                                  10 >= 1 통과, 9로 계산
t5    UPDATE ... SET remaining=9, 커밋
t6                                  UPDATE ... SET remaining=9, 커밋  ← A의 쓰기가 유실됨
      → 예약 2건이 생겼는데 재고는 1만 줄었다
```

UPDATE 자체는 행 쓰기 락 때문에 직렬화되지만, **값을 다시 읽지 않으므로** 뒤에 온 트랜잭션도 그냥 9를 쓴다.

### 왜 `SOLD_OUT`이 한 건도 안 났는가

이 버그의 성격을 그대로 보여주는 지점이다. 동시에 들어온 트랜잭션들이 같은 값을 읽고 전부 `값 - 1`을
쓰기 때문에 **재고는 "동시 진입 세대당 1"씩만 줄어든다.** 커넥션 풀이 30이면 30개가 10을 읽고 전부 9를
쓰고, 다음 30개가 9를 읽고 8을 쓴다. 100개를 처리하는 동안 재고가 5~9까지만 내려가므로
`remainingQuantity < quantity` 검증이 아예 발동하지 않는다.

잔여 수량이 회차마다 5~9로 흔들리는 것은 쓰기가 유실된 횟수가 타이밍에 따라 달라지기 때문이다.
판매 수량 합(100)은 매번 같다.

### 왜 DB CHECK 제약이 안전망이 못 되는가

`V1__init_schema.sql:64`에 제약이 있다.

```sql
CONSTRAINT ck_ticket_grades_remaining_range
    CHECK (0 <= remaining_quantity AND remaining_quantity <= total_quantity)
```

4회 모두 이 제약을 위반하지 않았다. 각 트랜잭션이 쓰는 값은 "자기가 읽은 값 - 수량"이고, 재고가 모자라면
그 전에 `SOLD_OUT`으로 끊기므로 **언제나 `[0, total]` 범위 안**이다. DB 제약은 "산술적으로 말이 되는 값인가"만
검사하고 "그 값이 최신 값에서 계산됐는가"는 알지 못한다. 즉 **초과 판매는 재고 음수로 나타나지 않고,
예약 수량 합이 총재고를 넘는 형태로만 드러난다.** 검증 지표를 재고가 아니라
`SUM(reservations.quantity)` vs `total_quantity`로 잡아야 하는 이유다.

## 4. 시나리오 B — 결제 ↔ 만료 배치 경쟁

### 재현 결과

3회 모두 동일했다.

| 항목 | 값 |
|---|---|
| 만료 배치가 복원한 등급 | 1건 |
| 결제 HTTP 응답 | 200 |
| 예약 상태 | **CONFIRMED** (배치가 쓴 EXPIRED를 덮어씀) |
| 결제 상태 | **PAID** |
| 잔여 / 총재고 | **10 / 10** (팔렸는데 재고가 복원됨) |
| 활성 판매 수량 | 1 → 불변식 `1 + 10 = 11 > 10` |

**결제한 티켓이 CONFIRMED로 살아 있는데 그 좌석은 재고로 돌아갔다.** 그 자리는 다시 팔릴 수 있다.

### 재현 방법: PG 지연을 모사

순서를 래치로 강제하지 않았다. 테스트가 순서를 만들어내면 "실제로 일어나는가"에 답하지 못한다.
운영에서 이 경쟁이 벌어지는 실제 원인은 **PG 승인 호출이 트랜잭션 안에서 블로킹한다**는 것이므로,
테스트용 `PaymentGateway`가 실제 PG처럼 5초 걸리게 하고 홀드가 그 사이에 끝나도록 두었다
(`ConcurrencyTestConfig.java`).

```
(사전) 다른 등급으로 선점+결제 1건을 지연 0으로 워밍업
       — MockMvc·JPA 첫 호출이 느려서 결제 스레드가 만료 전에 조회까지 못 가는 것을 방지
t=0s     결제 스레드: PENDING 읽음, isExpired=false (만료 전) → PG 승인 진입, 5초 블로킹
t=1.5s   expires_at 경과
t=1.6s   만료 배치: status='EXPIRED' + remaining 복원, 커밋 (아무것도 기다리지 않는다)
t=5s     결제 스레드: PG 응답 → confirm() → CONFIRMED로 UPDATE, 커밋 (EXPIRED를 덮어씀)
```

마진 3.5초라 CI 러너가 느려도 조건이 깨지지 않는다.

### 원인: 세 가지가 겹친다

**(1) 결제가 PG를 기다리는 동안 아무 행 락도 잡고 있지 않다.**
`ReservationService.java:111`의 `findMineWithDetails()`는 순수 SELECT다. `:122-123`에서 승인을 기다리는
5초 동안 `reservations`·`ticket_grades`에 쓰기가 없으므로 락도 없다. 그래서 만료 배치가 전혀 블로킹되지
않고 들어와 커밋한다. 조회 시점에 `SELECT ... FOR UPDATE`였다면 배치가 막혀 이 경쟁은 생기지 않는다.

**(2) 상태 전이 UPDATE에 상태 조건이 없다.**

```java
// Reservation.java:128-134
public void confirm(Instant now) {
    if (status != ReservationStatus.PENDING) {        // 메모리 상태로만 검사
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
    }
    this.status = ReservationStatus.CONFIRMED;
}
```

JPA 더티체킹은 "내가 읽은 상태"를 WHERE에 넣지 않는다. `UPDATE reservations SET status='CONFIRMED',
... WHERE id = ?`가 나가므로, 메모리의 PENDING 기준으로 가드를 통과하고 DB에 이미 들어간 EXPIRED를
덮어쓴다. `@Version`이나 `WHERE status = 'PENDING'`이 있으면 여기서 막힌다.

**(3) 결제는 `ticket_grades`를 건드리지 않는다.**
배치가 복원한 `remaining_quantity = 10`이 그대로 남는다. 결제가 재고를 다시 깎지 않으므로
"팔렸는데 재고에 있는" 상태가 확정된다.

만료 배치 자체(`ReservationRepository.java:37-48`)는 data-modifying CTE 한 문장이라 **그 자체로는
원자적**이다. 문제는 배치 내부가 아니라 결제 쪽 틈이다.

### 인접 위험: 재고 이중 복원 시 CHECK 위반

`TicketGrade.increaseRemaining()`(`:78-80`)에는 상한 검증이 없다.

```java
public void increaseRemaining(int quantity) {
    this.remainingQuantity += quantity;   // total_quantity 상한 검사 없음
}
```

취소(`ReservationService.cancel()`)와 만료 배치가 같은 예약을 복원하면 `remaining > total`이 되어
`ck_ticket_grades_remaining_range` 위반으로 500이 날 수 있다. 시나리오 B는 1건만 복원해 10을 넘지 않아
재현되지 않았다. Task 018 범위 밖이지만 Task 019에서 함께 막아야 한다.

## 5. 해결안 비교

Task 019에서 네 가지를 같은 조건으로 측정했다. 전체 수치와 구현 함정은
[002. DB 락 전략 비교](./002-db-lock-comparison.md)에 있고, 요약만 옮긴다.

| 방식 | 판매 (총재고 10) | 총 소요 | 요청 최대 | 재시도 | 채택 |
|---|---|---|---|---|---|
| 미보장 (이 문서의 before) | **100** | 287~322ms | 279~317ms | 0 | |
| 비관적 락 | 10 | 100~121ms | 90~114ms | 0 | |
| 낙관적 락 | 10 | 120~140ms | 120~139ms | **114~138** | |
| 조건부 UPDATE | 10 | **60~72ms** | **57~64ms** | 0 | ✅ |

정확성은 세 방식 모두 같았고, 조건부 UPDATE가 소요시간·꼬리 지연·재시도 모든 축에서 같거나 나았다.
결정적인 근거는 성능이 아니라 구조였다 — **낙관적 락은 "모든 쓰기가 `version`을 올려야 한다"는 전역 규약을
요구하고 아무것도 그걸 강제하지 않는다**(만료 배치의 네이티브 CTE가 실제로 그 규약을 어기고 있었다).
조건부 UPDATE는 각 문장이 자기 조건을 들고 있어서 그런 규약이 필요 없다.

예상대로 시나리오 B는 재고 락만으로는 해결되지 않았다. **예약 상태 전이도 조건부 UPDATE로 바꿨고**,
`WHERE status = 'PENDING' AND expires_at > :now`로 0행이면 결제를 `RESERVATION_EXPIRED`로 실패시킨다.
그 시점에 PG 승인은 이미 떨어져 있으므로 보상 환불까지 구현했다.

4절의 "인접 위험"(취소 ↔ 만료 이중 복원)도 같이 해결됐다 — 상태 전이에 성공한 트랜잭션만 재고를
복원하므로 이중 복원 경로가 구조적으로 사라졌다.

## 6. 재현 대상에서 제외한 것

README "알려진 한계: 결제 게이트웨이 호출이 트랜잭션 안에 있음"은 재현 대상이 아니다.
이것은 **버그가 아니라 실제 PG 연동 시점의 과제**다. 지금은 `MockPaymentGateway`가 즉시 응답해서
커넥션을 오래 잡는 문제가 없고, 실제 PG로 바꿀 때 승인 호출을 트랜잭션 밖으로 빼는 구조 변경이 필요하다.

다만 시나리오 B가 이 구조에 의존해서 재현된다는 점은 짚어둘 만하다. PG 지연이 길어질수록 경쟁 창이
넓어지므로, 실제 PG 연동은 이 경쟁의 발생 확률을 **높인다.**

## 7. 테스트 전략: 버그를 단정한다

로드맵 Task 018은 "테스트가 실패하며 초과 판매를 재현"이라고 적혀 있다. 하지만 그대로 두면
Task 019에서 고칠 때까지 CI `backend` 잡이 영구 red가 되어 다른 회귀를 못 잡는다.

그래서 **현재의 잘못된 동작을 단정해서 테스트를 통과시킨다.** Task 019에서 락을 적용하면
단정만 뒤집으므로, PR diff에 개선이 그대로 드러난다.

```java
// 지금 (Task 018)
assertThat(soldQuantity).as("팔린 수량이 총재고를 넘었다 = 초과 판매").isGreaterThan(TOTAL_QUANTITY);

// Task 019에서
assertThat(soldQuantity).isEqualTo(TOTAL_QUANTITY);
```

뒤집을 지점은 테스트 코드에 `↓↓↓ ~ ↑↑↑` 주석으로 감싸 표시했다.

| 위치 | 현재 단정 | Task 019에서 |
|---|---|---|
| 시나리오 A | `soldQuantity > 10` | `soldQuantity == 10` |
| 시나리오 A | `soldQuantity + remaining > 10` | `== 10` |
| 시나리오 B | `status == "CONFIRMED"` | `status == "EXPIRED"` (결제는 `RESERVATION_EXPIRED` 실패) |
| 시나리오 B | `remaining == 10` (복원됨) | `remaining == 10` (만료가 정상 처리되므로 동일) |
| 시나리오 B | `soldQuantity + remaining > 10` | `== 10` |

`@Disabled`나 `@Tag` 제외는 쓰지 않았다. 꺼진 테스트는 아무것도 막지 못하고, 시간이 지나면 썩는다.

## 8. 한계

- 재현이 **커넥션 풀 크기에 의존한다.** 풀을 1로 줄이면 완전히 직렬 실행되어 초과 판매가 나지 않는다.
  현재 설정(30)에서는 4회 모두 100건이 성공했고 단정 임계값은 11건이라 여유가 크지만, CI 러너에서
  분포가 달라질 수 있다. 한 번이라도 깨지면 수치를 재측정해 임계값을 다시 잡아야 한다.
- 시나리오 B는 `expires_at`을 SQL로 당겨서 "10분이 지난 상황"을 만든다. 실제로 10분을 기다릴 수 없어
  택한 방법이고, 선점 자체는 정상 API 경로로 만들어 재고 차감까지 실제와 같게 했다.
- 만료 배치를 `@Scheduled`로 돌리지 않고 리포지토리 메서드를 직접 호출한다. test 프로필에서 스케줄링이
  꺼져 있기 때문이다(`SchedulingConfig`). 배치 본체는 같으므로 재현 내용에는 영향이 없다.
- 취소 ↔ 만료 이중 복원(4절 "인접 위험")은 재현하지 않았다.

## 9. 다음 단계

[Task 019: DB 락 전략 적용 및 비교](./002-db-lock-comparison.md) — 비관적 락, 낙관적 락, 조건부 UPDATE를
각각 적용해 정확성·소요시간·재시도 횟수를 비교하고, 이 문서의 수치를 before로 삼아 after를 기록한다.

## 재현 방법

```bash
cd tikkit-back
docker ps                   # Docker 데몬이 떠 있어야 한다. Testcontainers가 테스트용 Postgres를 직접 띄우므로
                            # docker-compose(dev용 DB)는 필요 없다
./gradlew test --tests '*ReservationConcurrencyTest*'
```

수치는 테스트 로그에 `[초과 판매 재현]`, `[결제-만료 경쟁 재현]`으로 출력된다.
