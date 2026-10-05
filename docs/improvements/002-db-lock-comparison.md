# 002. DB 락 전략 비교와 조건부 UPDATE 채택 (Task 019)

| | |
|---|---|
| 관련 Task | Task 019 (Phase 5: 동시성 제어 고도화) |
| 대상 코드 | `ReservationService.create()/pay()/cancel()`, `TicketGradeRepository`, `ReservationRepository` |
| before 기준선 | [001. 초과 판매 재현](./001-overselling-reproduction.md) |
| 결론 | **조건부 UPDATE 채택** (비관적 락·낙관적 락은 측정 후 제거) |
| 작성 시점 | 2026-10-04 |

## 1. 배경

[001 문서](./001-overselling-reproduction.md)에서 초과 판매와 결제-만료 경쟁을 재현했다. 재고 10석에
100명이 동시에 선점하면 **100건 전원이 성공**했고, 결제가 만료 처리를 덮어써서 "팔린 좌석인데 재고에 있는"
상태가 만들어졌다.

이 문서는 그걸 고친 기록이다. 네 가지 방식을 같은 조건에서 재고 비교한 뒤 하나를 골랐다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| 조건 | 재고 10석, 서로 다른 회원 100명, 1매씩 동시 요청 (001과 동일) |
| DB | PostgreSQL 15 (Testcontainers), READ COMMITTED |
| HikariCP | `maximum-pool-size=30`, `minimum-idle=30` (커넥션 선생성) |
| 동시 진입 | `ExecutorService`(100) + 출발선 래치 3개(`ready`/`start`/`done`) |
| 반복 | 워밍업 1회(결과 폐기) + 3회 측정 |
| 측정 코드 | `ReservationLockStrategyComparisonTest` (채택 후 삭제 — 아래 9절) |

네 방식을 런타임에 갈아끼우기 위해 `SeatHoldStrategy` 인터페이스와 구현체 4개, 그리고 현재 전략을 들고
있는 홀더를 **비교 기간 한정으로** 두었다. 스프링 컨텍스트를 하나만 쓰면서 `@ParameterizedTest`로
네 방식을 순회해야 측정 조건(워밍업 상태, 커넥션 풀)이 같아진다.

## 3. 네 가지 방식

### 미보장 (기준선)

Task 012~018의 동작. 읽은 값을 메모리에서 빼고 조건 없이 그 절대값을 쓴다.

```sql
UPDATE ticket_grades SET remaining_quantity = :계산된값 WHERE id = :id
```

> [!note]
> 기준선 구현이 더티체킹이 아니라 명시적 UPDATE인 이유는 6절 함정 3에 있다.

### 비관적 락

재고 행에 `SELECT ... FOR UPDATE`를 걸어 다른 트랜잭션을 줄 세운다.

```java
entityManager.refresh(grade, LockModeType.PESSIMISTIC_WRITE);
grade.decreaseRemaining(quantity);
```

### 낙관적 락

`ticket_grades.version`(V3)으로 충돌을 감지하고, 충돌하면 트랜잭션을 처음부터 다시 시작한다.
더티체킹 UPDATE에 `WHERE id = ? AND version = ?`이 붙는다.

### 조건부 UPDATE

현재 재고를 WHERE 절에 넣어 DB가 원자적으로 검증·차감하게 한다.

```sql
UPDATE ticket_grades SET remaining_quantity = remaining_quantity - :qty, updated_at = :now
WHERE id = :id AND remaining_quantity >= :qty
-- 영향받은 행이 0이면 재고 부족
```

## 4. 측정 결과

각 3회 측정의 범위다.

| 방식 | 201 성공 | 409 거절 | 판매 + 잔여 | 불변식 | 총 소요 | 요청 평균 | 요청 최대 | 재시도 |
|---|---|---|---|---|---|---|---|---|
| 미보장 | 99~100 | 0~1 | 100 + 6~7 | **깨짐 (103~108)** | 287~322ms | 148~174ms | 279~317ms | 0 |
| 비관적 락 | 10 | 90 | 10 + 0 | 10 ✅ | 100~121ms | 64~78ms | 90~114ms | 0 |
| 낙관적 락 | 10 | 90 | 10 + 0 | 10 ✅ | 120~140ms | 97~113ms | 120~139ms | **114~138** |
| **조건부 UPDATE** | **10** | **90** | **10 + 0** | **10 ✅** | **60~72ms** | **40~46ms** | **57~64ms** | **0** |

### 미보장이 가장 느리다

처음엔 의외였는데 당연한 결과다. 미보장은 100건 전부 성공해서 `reservations`에 **100번 INSERT**하고,
나머지 셋은 90건을 재고 부족으로 빨리 끊어낸다. 느린 이유가 락이 아니라 **일을 10배 더 하기 때문**이다.
버그가 성능 지표를 좋게 보이게 만들지 않는다.

### 낙관적 락이 비관적 락보다 느리다

재고 한 칸을 100명이 다투는 상황은 충돌률이 거의 100%라서 "충돌이 드물다"는 낙관적 락의 전제가 깨진다.
**재시도 비용이 락 대기 비용보다 커졌다.**

재시도 횟수가 요청 수를 넘는다(100요청에 114~138회). 한 요청이 평균 1회 이상 되돌아간다는 뜻이다.
재시도 상한을 5로 뒀는데도 10매가 정확히 다 팔린 건 운이 좋은 쪽이고, 상한이 낮거나 경쟁이 더 심하면
**재고가 남았는데도 매진으로 거절**하는 상황이 생긴다.

### 비관적 락은 요청 최대 지연이 2배

총 소요시간은 조건부 UPDATE와 큰 차이가 없어 보이지만, 요청 최대 지연이 90~114ms로 조건부 UPDATE(57~64ms)의
약 2배다. 마지막 요청이 앞선 요청들의 락 해제를 기다린 결과다 — 줄서기의 비용은 평균보다 **꼬리**에서 드러난다.

## 5. 채택: 조건부 UPDATE

정확성은 세 방식 모두 동일하다(판매 10매, 불변식 유지). 그래서 나머지 기준으로 골랐다.

| 기준 | 비관적 락 | 낙관적 락 | 조건부 UPDATE |
|---|---|---|---|
| 소요시간 | 보통 | 가장 느림 | **가장 빠름** |
| 요청 최대 지연 | 2배 (줄서기) | 2배 (재시도) | **기준** |
| 재시도 | 없음 | 요청 수 이상 | **없음** |
| 재고 남은 채 거절 | 없음 | **가능** (재시도 소진) | 없음 |
| 추가 스키마 | 없음 | `version` 컬럼 | 없음 |
| 데드락 위험 | 있음 (여러 행 잠글 때) | 없음 | 없음 |
| 전역 규약 필요 | 없음 | **있음** (6절 함정 2) | 없음 |

조건부 UPDATE가 모든 축에서 같거나 낫다. 애플리케이션이 읽은 값을 다시 쓰지 않으니 lost update가
**구조적으로 불가능**하고, 락을 잡지 않아 대기도 없고, 충돌을 되돌릴 일이 없으니 재시도도 없다.

예약 상태 전이도 같은 방식으로 강화했다 (7절).

## 6. 구현에서 걸린 함정 4개

### 함정 1: 더티체킹이 조건부 UPDATE를 덮어쓴다

조건부 UPDATE를 날려도 엔티티가 같은 트랜잭션의 **영속 상태로 남아 있으면**, flush 시점에 Hibernate가
`UPDATE ticket_grades SET remaining_quantity = <메모리값> WHERE id = ?`를 **또** 발행해서 효과를 지운다.

해결: **엔티티 필드를 아예 건드리지 않는다.** 재고 검증도 메모리 값이 아니라 영향받은 행 수로 한다.
`clearAutomatically`로 영속성 컨텍스트를 비우는 방법도 있지만, `cancel()`에서 아직 flush되지 않은
`payment.refund(now)` 변경이 버려지고 `Reservation`의 지연 연관이 끊기는 부작용이 있어 쓰지 않았다.

최종적으로 `TicketGrade`에서 `decreaseRemaining`/`increaseRemaining`을 **삭제**했고,
`Reservation.confirm`/`cancel`은 필드 대입을 걷어내고 `validateConfirmable`/`validateCancellable`로
검증만 남겼다. 메서드를 남겨두면 누가 호출하는 순간 같은 문제가 재발하므로, **실수할 경로를 없앴다.**

부수 효과: 벌크 UPDATE에는 JPA Auditing이 걸리지 않아 모든 조건부 UPDATE에 `updatedAt = :now`를
직접 넣었다. 만료 배치 CTE가 이미 같은 이유로 그렇게 하고 있어 일관된다.

### 함정 2: 낙관적 락은 전역 규약을 요구한다

`@Version`은 **JPA를 거치는 쓰기에만** 적용된다. 만료 배치(`expirePendingReservations`)는 네이티브
data-modifying CTE라 `version`을 올려주지 않는다. 즉 낙관적 락을 걸어도 이 경로는 충돌을 감지하지 못한다.

쿼리에 `version = version + 1` 한 줄을 넣으면 해결되지만, 그게 바로 문제다 — **version을 올려야 하는
책임이 애플리케이션 전체에 흩어지고 아무것도 그걸 강제하지 않는다.** 네이티브 CTE 하나를 찾는 데도
쓰기 경로를 전수 조사해야 했고, 앞으로 추가될 MyBatis 배치(Phase 8)·DBA 스크립트·데이터 패치마다
같은 함정이 반복된다.

조건부 UPDATE는 각 문장이 자기 조건을 들고 있어서 이 전역 규약이 아예 필요 없다. **채택의 핵심 근거다.**

### 함정 3: `@Version`은 엔티티 단위로 전역이다

`@Version`을 붙이는 순간 그 테이블에 대한 **모든** 더티체킹 UPDATE에 `WHERE version = ?`이 붙는다.
"낙관적 락을 쓰는 경로만 골라서 적용"이 안 된다.

그래서 비교 기준선(미보장)이 저절로 낙관적 락으로 변해버렸고, 그 상태로는 "미보장" 행을 측정할 수 없었다.
기준선을 **조건 없는 절대값 UPDATE**로 재현해서 해결했다 — 발행되는 SQL이 기존 더티체킹과 같으므로
경쟁 재현은 그대로다.

### 함정 4: 비관적 락 + 1차 캐시 = 조용한 오동작

`create()`는 판매 기간 검증과 가격 스냅샷 때문에 등급을 이미 영속성 컨텍스트에 올려둔 상태다.
여기서 `@Lock(PESSIMISTIC_WRITE)` 쿼리 메서드를 호출하면 **SQL은 `FOR UPDATE`로 나가 행 락은 잡히지만,
Hibernate는 이미 관리 중인 인스턴스의 필드를 DB 값으로 덮어쓰지 않는다.** 락을 걸고도 1차 캐시의 낡은
잔여 수량으로 계산하게 되어, 비관적 락인데도 초과 판매가 난다.

락을 걸었다는 사실이 오히려 안심시켜서 더 위험한 종류의 버그다.
`em.refresh(entity, LockModeType.PESSIMISTIC_WRITE)`는 행 락 획득과 DB 재조회를 한 문장으로 처리하므로
이 함정이 없다 — JPA가 이 조합을 위해 둔 오버로드다.

## 7. 결제 ↔ 만료 배치 경쟁 해결

001 문서의 시나리오 B다. 원인은 세 가지가 겹친 것이었다.

1. 결제가 PG 응답을 기다리는 동안 조회만 한 상태라 **아무 행 락도 잡고 있지 않다**
2. `confirm()`의 더티체킹 UPDATE가 `WHERE id = ?`만 걸어서, 메모리의 PENDING으로 가드를 통과하고
   DB의 EXPIRED를 덮어쓴다
3. 결제는 `ticket_grades`를 건드리지 않아 배치가 복원한 재고가 그대로 남는다

### 상태 전이를 조건부 UPDATE로

```sql
UPDATE reservations SET status = 'CONFIRMED', confirmed_at = :now, updated_at = :now
WHERE id = :id AND status = 'PENDING' AND expires_at > :now
```

`expires_at > :now`를 넣은 이유: 만료 배치가 60초 주기라 "홀드는 끝났는데 아직 PENDING"인 창이 항상
존재한다. 그 창의 결제를 DB가 직접 거절하므로 메모리 가드(`isExpired`)와 판정 규칙이 일치한다.

0행이면 DB 상태를 다시 읽어 에러 코드를 정한다. EXPIRED 또는 PENDING(만료 시각 경과)이면
`RESERVATION_EXPIRED`, CONFIRMED/CANCELLED면 `INVALID_STATUS_TRANSITION` — **기존 에러 계약과 같아서
새 에러 코드를 만들지 않았다.**

### 승인된 결제의 보상 환불

0행인 시점에는 **PG 승인이 이미 떨어진 상태**다. 돈은 나갔는데 예약은 만료된 셈이라 환불로 되돌린다.

```java
if (reservationRepository.confirmIfPending(id, now) == 0) {
    compensateApprovedPayment(transactionKey);
    throw new BusinessException(resolveConfirmConflict(id));
}
```

예외를 던지면 트랜잭션이 롤백되어 `payments` 행은 남지 않는다. 환불은 외부 호출이라 롤백되지 않는 게 의도다.

### 취소 ↔ 만료 이중 복원도 같이 해결됐다

`cancel()`도 읽은 상태를 WHERE에 넣는다. **1행을 바꾼 트랜잭션만 환불과 재고 복원을 수행할 권리를 얻는다.**
만료 배치는 PENDING인 행만 집어가므로 우리가 먼저 CANCELLED로 바꾸면 배치가 이 행을 보지 못하고,
반대로 배치가 먼저 EXPIRED로 바꿨으면 0행이 되어 복원을 건너뛴다 — **이중 복원이 구조적으로 불가능해진다.**

환불을 상태 전이 **뒤로** 옮긴 것도 중요하다. 기존 코드는 환불을 먼저 했는데, 전이가 0행이면 돈만
돌려주고 취소는 안 된 상태가 된다. 전이 성공 후 환불이 실패하면 트랜잭션이 롤백돼 전이도 되돌아간다.

재고 복원에도 상한 조건(`remaining + :qty <= total`)을 넣어서, 이중 복원이 일어나도
`ck_ticket_grades_remaining_range` CHECK 위반(500)이 아니라 0행으로 드러난다.

### before / after

| 항목 | before (001) | after |
|---|---|---|
| 결제 응답 | 200 | **409 `RESERVATION_EXPIRED`** |
| 예약 상태 | CONFIRMED (만료를 덮어씀) | **EXPIRED** |
| `payments` 행 | PAID | **없음** (롤백) |
| PG 보상 환불 | 없음 | **1회** |
| 잔여 / 총재고 | 10 / 10 (팔렸는데 복원됨) | 10 / 10 (정상 만료 복원) |
| 불변식 (판매 + 잔여) | **11 > 10** | **10 = 10** ✅ |

## 8. 시나리오 A before / after

| 항목 | before (001) | after |
|---|---|---|
| 201 성공 | 99~100건 | **10건** |
| 409 `SOLD_OUT` | 0~1건 | **90건** |
| 판매 수량 합 | 100 | **10** |
| 잔여 수량 | 4~7 | **0** |
| 불변식 (판매 + 잔여) | **103~108 > 10** | **10 = 10** ✅ |

Task 018의 재현 테스트(`ReservationConcurrencyTest`)는 단정을 뒤집어 그대로 남겼다. 이제
"초과 판매가 난다"가 아니라 "정확히 10매만 팔린다"를 지키는 회귀 테스트다.

성공·거절 건수까지 단정한 이유: 판매 수량 합만 보면 "10건 성공 + 90건 거절"과 "5건 성공 + 95건 거절"을
구분할 수 없다. 조건부 UPDATE는 재고가 남아 있으면 반드시 성공해야 하므로 정확히 10/90이어야 하고,
이 단정이 **"재고가 남았는데 거절"하는 회귀**를 잡는다.

## 9. 버린 것과 그 이유

| 버린 것 | 이유 |
|---|---|
| `SeatHoldStrategy` + 구현체 4개 + 홀더 | 재고 차감 방식을 런타임에 고르는 요구사항이 없다. 비교가 끝난 추상화를 남기면 유지 부담만 생긴다 |
| `RetryMetrics` | 낙관적 락 전용 집계. 운영 지표가 필요해지면 Micrometer로 가는 게 맞다 (Task 025) |
| `ticket_grades.version` (`V3_1`로 DROP) | 조건부 UPDATE 채택으로 미사용. 안 쓰는 컬럼을 스키마에 남기지 않는다 |
| `ReservationLockStrategyComparisonTest` | 측정 하네스. 수치는 이 문서와 커밋 메시지에 남고, 조건부 UPDATE의 회귀는 `ReservationConcurrencyTest`가 지킨다 |
| `spring-retry` 도입 | 재시도는 트랜잭션 **밖**이어야 하는데 `@Retryable`을 `@Transactional`과 같은 메서드에 붙이면 트랜잭션 안에서 재시도되는 함정이 있다. 지울 실험 코드에 의존성 2개를 넣고 빼지 않으려고 수동 루프를 썼다 |

`V3` → `V3_1` 번호를 쓴 이유: ERD 마이그레이션 이력에 V4~V8이 이미 미래 계획으로 잡혀 있어서,
DROP에 V4를 쓰면 ERD와 ROADMAP 7곳의 번호가 밀린다. Flyway는 `V3_1`을 3.1로 해석해 V3와 V4 사이에 끼운다.

## 10. 남은 한계

- **중복 선점 가드는 아직 동시 요청에 취약하다.** `ReservationService.create()`의
  `existsByMemberIdAndTicketGradeIdAndStatus` 체크와 INSERT 사이에 락이 없어서, 같은 회원의 동시 요청
  2건이 둘 다 통과할 수 있다. 정답은 `UNIQUE(member_id, ticket_grade_id) WHERE status = 'PENDING'`
  부분 유니크 인덱스인데, 마이그레이션 번호를 또 소모하므로 범위 밖으로 뒀다. **→ Task 020에서 `V3_2`로 해결** ([003](./003-redis-distributed-lock.md)).
- **보상 환불은 최선 노력(best effort)이다.** 환불 호출이 실패하면 로그만 남긴다. 그 트랜잭션은 곧
  롤백되므로 실패 사실을 DB에 남길 수 없다 — 영속적 보상에는 아웃박스 테이블 + 정산 배치 + PG 멱등키가
  필요하고, 이건 Phase 7(운영 기반) 규모의 작업이다. `MockPaymentGateway`는 환불이 실패하지 않으므로
  당장의 실질 위험은 없다.
- **PG 호출이 여전히 트랜잭션 안에 있다.** 실제 PG로 교체하면 네트워크 I/O를 기다리는 동안 커넥션을
  잡고 있게 된다. 조건부 UPDATE는 락을 잡지 않으므로 락 점유 문제는 없지만 커넥션 풀 고갈은 남는다.
  실제 PG 연동 시점의 과제다.
- **`DataIntegrityViolationException`은 여전히 500이다.** 조건부 UPDATE 채택으로 재고 CHECK 위반 경로는
  사라졌지만, 예약번호 시퀀스 한 바퀴(`uk_reservations_reservation_no`)나 동시 이중 결제
  (`uk_payments_reservation_id`) 같은 제약 위반은 클라이언트가 재시도로 해결할 409가 맞다.
  Task 019와 무관한 제약까지 손대는 범위라 후속 과제로 둔다.
- 측정은 로컬 Docker 기준이다. 절대 수치는 환경에 따라 달라지므로 **방식 간 상대 비교**로만 읽어야 한다.

## 11. 다음 단계

[Task 020: Redis 분산 락 비교 실험](./003-redis-distributed-lock.md) — DB 락으로 충분한지,
분산 락이 단일 DB 환경에서 필요한지 비교한다. "커밋 전 락 해제" 함정도 함께 정리한다.

**결과**: 단일 DB에서는 불필요하다는 결론이 나왔다. 재고 경쟁은 정확성이 동일한데 분산 락이 6~7배
느렸고, 위의 "중복 선점 가드" 한계는 부분 유니크 인덱스(`V3_2`)로 해결했다.

## 재현 방법

```bash
cd tikkit-back
docker ps                   # Docker 데몬이 떠 있어야 한다 (Testcontainers)
./gradlew test --tests '*ReservationConcurrencyTest*'
```

수치는 테스트 로그에 `[초과 판매 차단]`, `[결제-만료 경쟁 차단]`으로 출력된다.
네 방식 비교 측정은 측정 후 삭제했으므로, 재현하려면 이 PR의 측정 커밋을 `git show`로 꺼내야 한다.
