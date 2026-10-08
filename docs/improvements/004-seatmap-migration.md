# 004. 수량 재고에서 좌석 재고로 — expand/backfill/contract 무중단 전환 (Task 021~022)

| | |
|---|---|
| 관련 Task | Task 021~022 (Phase 6: 지정석 전환) |
| 대상 코드 | `V4`~`V6` 마이그레이션, `ReservationService.create/pay/cancel`, `ScheduleSeatRepository`, `ReservationRepository.expirePendingReservations`, `createReservationAction` |
| before 기준선 | [002. DB 락 전략 비교](./002-db-lock-comparison.md) |
| 결론 | 조건부 UPDATE 전략은 다중 행으로 확장됐다. **데드락 0건 / 부분 선점 0건**, `schedule_seats` HOT 업데이트 100%. 컬럼 제거(contract)는 expand와 달리 코드와 같은 배포에 묶인다 |
| 작성 시점 | 2026-10-08 |

## 1. 배경

MVP의 재고는 `ticket_grades.remaining_quantity` **숫자 한 칸**이었다. 동시성은
`WHERE remaining_quantity >= :quantity` 조건부 UPDATE로 막았고, 그 비교와 채택 과정이
[002](./002-db-lock-comparison.md)다.

지정석이 되면 재고가 `schedule_seats` **행 N개**로 쪼개진다. "몇 석 남았나"가 아니라 "그 자리가
비었나"가 질문이 되고, 같은 조건부 UPDATE가 한 행이 아니라 여러 행을 한 번에 다룬다.

```sql
-- before: 한 행의 숫자를 깎는다
UPDATE ticket_grades SET remaining_quantity = remaining_quantity - :quantity
WHERE id = :id AND remaining_quantity >= :quantity;

-- after: 여러 행의 상태를 바꾼다
UPDATE schedule_seats SET status = 'HELD', reservation_id = :reservationId
WHERE id IN (:sortedSeatIds) AND status = 'AVAILABLE' AND ticket_grade_id = :ticketGradeId;
```

이 전환이 두 종류의 문제를 새로 만든다.

| | 한 행 경쟁 (before) | N행 경쟁 (after) |
|---|---|---|
| 실패 판정 | 0행이면 재고 부족 | **"요청한 만큼 안 바뀜"도 실패** — 4석 중 2석만 성공이 가능하다 |
| 락 순서 | 행이 하나라 순서가 없다 | 요청마다 다른 순서로 잡으면 **데드락** |
| 경쟁 집중도 | 모두가 같은 행을 다툰다 | 좌석별로 분산된다 (이게 전환의 이득) |

여기에 운영 중 스키마를 바꿔야 한다는 제약이 겹친다. 재고의 원천을 바꾸는 건
"컬럼 하나 추가"가 아니라 **읽는 곳과 쓰는 곳이 전부 옮겨가는** 변경이다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| DB | PostgreSQL 15 (Docker), 동시성 테스트는 Testcontainers 싱글턴 |
| dev 데이터 | `seats` 7,120행 / `schedule_seats` 26,894행 (4,616 kB) / 구역 12종 |
| 최대 회차 | 고척스카이돔 3,200석 (VIP 200 / R 600 / S 1,200 / A 1,200) |
| 커넥션 풀 | 동시성 테스트만 `maximum-pool-size=30`, `minimum-idle=30` |
| 격리 수준 | READ COMMITTED (PostgreSQL 기본) |

절대 수치는 로컬 Docker 기준이라 **상대 비교와 성립/불성립 판정**으로만 읽어야 한다.

## 3. 왜 세 단계로 나눴나 — 그리고 contract만 다른 이유

[db-design 스킬](../../tikkit-back/.claude/skills/db-design/migration.md)의 expand → backfill →
contract를 따랐다.

| 단계 | 파일 | 하는 일 | 코드와의 관계 |
|---|---|---|---|
| expand | `V4__create_seat_tables.sql` | 좌석 세 테이블 생성 | **독립** — 애플리케이션은 테이블이 생긴 걸 모른다 |
| backfill | `V5__backfill_seats.sql` | 기존 재고·예약을 좌석으로 옮긴다 | **독립** — 일회성 데이터 이동 |
| contract | `V6__drop_quantity_columns.sql` | 수량 컬럼 제거 | **같은 커밋에 묶인다** |

expand 단계가 독립적이라는 건 Task 021에서 실측으로 확인했다 — 새 테이블 세 개가 추가된 상태에서
기존 테스트 107개가 **한 줄도 안 고치고** 통과했다. `ddl-auto: validate`가 매핑되지 않은 테이블을
문제 삼지 않기 때문이다.

**contract는 반대다.** 같은 `ddl-auto: validate` 때문에 컬럼만 지우면 Hibernate가 기동을 거부한다.

```
Schema-validation: missing column [total_quantity] in table [ticket_grades]
```

그래서 `V6`와 `TicketGrade` 엔티티의 필드 제거가 한 커밋에 들어간다. "스키마 먼저, 코드 나중"이
가능한 건 expand뿐이고, contract는 둘이 원자적이어야 한다. 무중단 배포에서 흔히
"expand/contract 패턴"이라고 묶어 부르지만 **두 단계의 제약은 대칭이 아니다.**

### 재고 원천이 뒤집히는 순간

V6 전까지는 `total_quantity`(숫자)가 원천이고 좌석이 파생이다. V6 이후에는 반대가 된다.

문제는 그 사이 구간이다. 쓰기를 좌석으로 바꾸면 `remaining_quantity`는 더 이상 갱신되지 않고
**거짓 값으로 남는다.** FE는 그 컬럼을 "잔여 N석"으로 그리고 있다.

선택지가 셋이었다.

| 방식 | 문제 |
|---|---|
| 이중 갱신(dual write) | `ticket_grades` 한 행 경쟁이 그대로 남아 **좌석으로 쪼갠 효과가 상쇄된다** |
| 컬럼을 stale로 방치 | 틀린 숫자를 화면에 띄우는 기간이 생긴다 |
| **파생 COUNT + 즉시 contract** | 채택 |

좌석 AVAILABLE 건수를 세서 `remainingQuantity`로 내려주면 API 응답 형식이 그대로다. FE는 재고의
원천이 바뀐 걸 모른다. 그러면 컬럼은 즉시 아무도 안 읽는 상태가 되고, V6는 "이미 미사용인 컬럼
정리"가 된다 — 이중 갱신 구간도, 거짓 데이터 구간도 없다.

```java
// ScheduleService.getTicketGrades
List<TicketGradeRow> rows = ticketGradeRepository.findRowsByScheduleId(scheduleId);
Map<Long, Integer> available = scheduleSeatRepository.countAvailableByScheduleId(scheduleId);

return rows.stream()
        .map(row -> new TicketGradeResponse(row.id(), row.grade(), row.price(),
                available.getOrDefault(row.id(), 0)))
        .toList();
```

한 쿼리로 `LEFT JOIN` + `GROUP BY` 집계를 하려 했지만 두 가지에 걸렸다. QueryDSL(JPQL)이
`count(...) FILTER (...)`를 못 쓰고, 좌석이 없는 등급은 집계가 `null`이 되어 FE의 매진 판정
(`remainingQuantity === 0`)이 빗나간다. 쿼리를 둘로 나누면 둘 다 사라지고 `getOrDefault(id, 0)`
한 줄로 끝난다. 그 "0으로 채운다"가 테스트 하나를 받았다 —
`좌석_없는_등급은_잔여수량_0`.

## 4. 데드락 — 정렬이 효과를 갖는 진짜 이유

여러 행을 잠글 때 순서가 요청마다 다르면 서로를 기다리는 순환이 생긴다. A가 1→2 순으로,
B가 2→1 순으로 잡으면 둘 다 멈춘다.

ERD와 `db-design` 스킬은 "좌석 ID를 정렬해서 `IN` 절에 넣어 데드락을 방지한다"고 적어뒀다.
계획 단계에서 이 근거를 의심했다 — **PostgreSQL은 `IN` 절 리터럴의 순서로 락을 잡지 않는다.**
플래너가 고른 스캔 순서로 잡는다. 좌석 4개짜리 작은 목록이면 Bitmap Heap Scan이 물리 순서(ctid)로
갈 수도 있고, 그건 ID 순서와 다르다.

`EXPLAIN`으로 실제 플랜을 봤다.

```
Insert on reservation_seats
  CTE held
    ->  Update on schedule_seats
          ->  Index Scan using schedule_seats_pkey on schedule_seats
                Index Cond: (id = ANY ('{1,2,3}'::bigint[]))
                Filter: ((status)::text = 'AVAILABLE' AND ticket_grade_id = 1)
  ->  CTE Scan on held
```

**PK Index Scan**이 선택됐다. btree의 `ScalarArrayOpExpr` 스캔은 배열 값을 정렬해 인덱스 순서로
훑으므로, 락 획득 순서가 `IN` 절에 적힌 순서와 무관하게 **ID 오름차순**이 된다.

그러니까 정렬의 효과는 이렇게 읽어야 맞다.

- 플래너가 PK Index Scan을 고르는 한 **DB가 이미 순서를 하나로 맞춰준다**
- 애플리케이션 정렬은 그 플래너 선택에 기대지 않기 위한 **방어**다 (비용 0)
- 플랜이 바뀌면(예: 좌석 목록이 아주 길어져 Bitmap Scan으로 전환) 정렬이 유일한 보장이 된다

```java
// ReservationService.normalizeSeatIds
List<Long> seatIds = request.seatIds().stream().distinct().sorted().toList();
```

실측에서 데드락은 0건이었다(8절). 서로 한 자리씩 겹치는 집합 세 개를 21개 스레드가 동시에
때렸는데 `40P01`이 한 건도 안 나왔다. 조건부 UPDATE는 재시도를 하지 않으므로, 데드락이 났으면
500으로 그대로 응답까지 올라와 테스트의 "예상 밖 응답" 집계에 잡힌다 — 조용히 넘어가지 않는다.

## 5. 부분 선점 — 데이터 변경 CTE 한 문장

한 행 경쟁에서는 "0행이면 실패"로 끝났다. N행에서는 **4석 중 2석만 성공**이 가능하다. 그 상태를
남기면 사용자는 돈을 내고 두 자리만 받는다.

막는 방법은 DB 제약이 아니라 **애플리케이션 검사**다. 바뀐 행 수가 요청 좌석 수와 다르면 예외를
던져 롤백시킨다.

```java
int held = scheduleSeatRepository.holdSeats(
        seatIds, reservation.getId(), ticketGrade.getId(), reservation.getUnitPrice(), now);
if (held != seatIds.size()) {
    throw new BusinessException(ErrorCode.SOLD_OUT);
}
```

여기에 두 번째 문제가 붙는다. 좌석을 HELD로 바꾸는 것과 `reservation_seats`에 이력을 남기는 것이
따로 실행되면 한쪽만 성공한 상태가 생길 수 있다. 데이터 변경 CTE로 한 문장에 묶었다.

```sql
WITH held AS (
    UPDATE schedule_seats
    SET status = 'HELD', reservation_id = :reservationId, updated_at = :now
    WHERE id IN (:scheduleSeatIds)
      AND status = 'AVAILABLE'
      AND ticket_grade_id = :ticketGradeId
    RETURNING id
)
INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price, created_at, updated_at)
SELECT :reservationId, id, :price, :now, :now FROM held
```

반환값이 최종 INSERT의 행 수이므로, **"선점에 성공한 좌석 수"와 "이력을 남긴 좌석 수"가 같은 값**이
된다. 둘을 따로 세서 비교할 필요가 없다.

### `AS MATERIALIZED`를 붙이지 않은 이유

Task 021의 V5 백필은 `matched` CTE를 두 번 참조해서, PostgreSQL 12+가 다중 참조 CTE를 자동
materialize하는 동작에 기댔다. 한 번만 참조되면 인라인되어 각자 재계산될 수 있고, 그러면
`ROW_NUMBER()` 기반 배정이 어긋날 여지가 생긴다. 백필은 일회성이라 결과를 데이터로 확인할 수
있었지만 **선점은 사용자 요청 경로라 그럴 수 없다** — 그래서 ROADMAP에 "두 번 이상 참조하면
키워드를 명시한다"를 숙제로 남겼다.

이번 쿼리는 `held`를 **한 번만** 참조한다. 참조가 하나면 인라인되어도 결과가 달라지지 않으므로
키워드가 필요 없다. 다만 나중에 참조가 늘면 필요해지므로, 그 판단 근거를 메서드 Javadoc에 적어
신호를 남겼다. 같은 이유로 만료 배치의 `expired` CTE도 키워드 없이 뒀다(한 번 참조).

## 6. 실행 순서가 뒤집힌 지점

Task 021에서 `schedule_seats`에 이 제약을 걸었다.

```sql
CONSTRAINT ck_schedule_seats_status_holder CHECK (
    (status = 'AVAILABLE' AND reservation_id IS NULL)
    OR (status IN ('HELD', 'SOLD') AND reservation_id IS NOT NULL))
```

"SOLD인데 점유자가 없는" 썩은 행을 막는 제약인데, 이게 코드의 **실행 순서를 결정했다.**
`HELD`가 되려면 `reservation_id`가 있어야 하고 그 컬럼은 `reservations` FK다. 즉 예약 행이
먼저 INSERT돼야 좌석을 잡을 수 있다.

```
before: 재고 차감 → 예약 INSERT       (차감 실패 시 save가 아예 호출되지 않는다)
after:  예약 INSERT → 좌석 선점        (선점 실패 시 save는 이미 됐고 롤백이 되돌린다)
```

사소해 보이지만 테스트 단정이 뒤집힌다. 전환 전 `재고_부족` 테스트는
`verify(reservationRepository, never()).save(any())`였는데, 지금은 `verify(...).save(any())`다.
그 뒤바뀜을 주석으로 못 박아 뒀다 — 다음에 누가 보면 "왜 실패했는데 save를 했지?"를 먼저 묻게 된다.

`ck_schedule_seats_status_holder`를 걸 때 ERD에 "Task 022에서 2단계 상태 전이가 필요해지면
다시 봐야 한다"고 적어뒀는데, 실제로는 2단계 전이가 필요하지 않았다. 대신 **호출 순서**가
제약에 맞춰졌다. 제약이 코드를 제한한 게 아니라 코드의 모양을 정해준 셈이다.

## 7. 좌석 해제를 `reservation_seats`로 경유하는 이유

Task 021은 `schedule_seats`에 `idx(schedule_id, status)`와 `idx(reservation_id)`를 **일부러
만들지 않았다.** 모든 선점/해제가 그 두 컬럼을 갱신하므로, 인덱스가 있으면 매번 인덱스 엔트리를
다시 써야 해 HOT 업데이트가 깨진다.

대가는 "예약으로 좌석을 찾는 경로"가 없어진다는 것이다. 그래서 확정·해제를 `reservation_seats`를
경유해 짰다.

```sql
UPDATE schedule_seats ss
SET status = 'AVAILABLE', reservation_id = NULL, updated_at = :now
WHERE ss.id IN (SELECT rs.schedule_seat_id FROM reservation_seats rs
                WHERE rs.reservation_id = :reservationId)
  AND ss.reservation_id = :reservationId
```

플랜을 확인했다.

```
Update on schedule_seats ss
  ->  Nested Loop
        ->  Bitmap Heap Scan on reservation_seats rs
              Bitmap Index Scan on uk_reservation_seats_reservation_seat  (reservation_id = 1)
        ->  Memoize -> Index Scan using schedule_seats_pkey on schedule_seats ss
```

`uk_reservation_seats_reservation_seat UNIQUE (reservation_id, schedule_seat_id)`의 선두 컬럼을
타고 들어가 PK로 좁힌다. 인덱스를 추가하지 않고도 전체 스캔이 안 난다.

`ss.reservation_id = :reservationId` 조건이 또 필요한 이유가 있다. `reservation_seats`는
append-only라 **과거에 그 예약이 받았던 좌석까지 남아 있고**, 그 좌석은 이미 다른 사람에게
넘어갔을 수 있다. 현재 점유자가 이 예약인 좌석만 되돌려야 한다. 이 조건이 대상 행과 소스 행을
1:1로 묶어주는 역할도 한다.

### 만료 배치가 좌석을 반환하지 않던 문제

V5 백필은 과거 PENDING 예약을 HELD로 만들었다. 건너뛰면 "SOLD+HELD == total - remaining"
불변식이 깨지므로 건너뛸 수도 없었다. 그런데 `ReservationExpiryScheduler`는 좌석을 몰랐다 —
**만료된 예약의 좌석이 HELD로 영구 고착**된다. Task 021이 "필수 후속"으로 남긴 항목이다.

배치 쿼리를 좌석 반환으로 바꿨다. 등급별 수량 복원이 사라지고 좌석 UPDATE가 그 자리에 들어간다.

```sql
WITH expired AS (
    UPDATE reservations SET status = 'EXPIRED', updated_at = :now
    WHERE status = 'PENDING' AND expires_at <= :now
    RETURNING id
)
UPDATE schedule_seats ss
SET status = 'AVAILABLE', reservation_id = NULL, updated_at = :now
FROM reservation_seats rs
WHERE rs.schedule_seat_id = ss.id
  AND rs.reservation_id IN (SELECT id FROM expired)
  AND ss.reservation_id = rs.reservation_id
```

before 쿼리에는 `restored` CTE가 있었다. 같은 등급에서 여러 건이 동시에 만료되면
`UPDATE ... FROM`이 대상 행 하나에 여러 소스 행을 매칭시키고 PostgreSQL이 그중 하나만 반영하므로,
등급 단위 `SUM(quantity)`로 합산해야 했다. **좌석은 1:1이라 그 합산이 필요 없어졌다** — 한 좌석에
매칭되는 활성 이력은 하나뿐이다. 조건 하나가 집계 한 단을 지웠다.

반환값의 의미도 바뀐다. before는 "재고가 복원된 등급 수"였고 now는 "반환된 좌석 수"다. 로그 문구를
같이 고쳤다.

## 8. 측정 결과

### 동시성 — 데드락 0건, 부분 선점 0건

`ReservationConcurrencyTest` 실측(로그 출력 그대로).

| 시나리오 | 요청 | 201 | 409 | 그 외 |
|---|---|---|---|---|
| 초과 판매 차단 (좌석 10석, 좌석당 10명) | 100 | **10** | 90 `SOLD_OUT` | **0** |
| 좌석당 한 명만 선점 (좌석 1석) | 100 | **1** | 99 `SOLD_OUT` | **0** |
| 겹치는 좌석 집합 `A{0,1,2} B{2,3,4} C{1,4,5}` | 21 | **1** | 20 `SOLD_OUT` | **0** |
| 중복 선점 차단 (같은 회원, 서로 다른 좌석) | 20 | **1** | 19 `DUPLICATE_PENDING_RESERVATION` | **0** |

"그 외 0건"이 데드락 부재의 증거다. 추가로 두 쿼리가 부분 선점을 직접 센다 — 점유 중인데
`reservation_seats` 이력이 없는 좌석 0건, 매수보다 적은 좌석을 받은 활성 예약 0건.

재현 조건을 만드는 방법이 바뀐 게 중요하다. before는 **스레드 수**가 경쟁을 만들었지만 now는
**어느 좌석을 고르는지**가 만든다. 100명이 각자 다른 좌석을 고르면 전원 성공하고 경쟁이 없다.
그래서 `초과_판매_차단`을 "i번째 스레드가 `i % 10`번 좌석을 고른다"로 바꿨다 — 좌석마다 정확히
10명이 붙어 성공이 결정적으로 10건이 된다. 무작위로 고르게 하면 "선택된 서로 다른 좌석 수"가
매번 달라져 성공 건수를 단정할 수 없다.

### HOT 업데이트 — 100%

Task 021이 `fillfactor = 90`을 걸면서 "효과는 미측정"으로 남겼던 항목이다.

| 테이블 | `n_tup_upd` | `n_tup_hot_upd` | HOT 비율 |
|---|---|---|---|
| `schedule_seats` | 6 | 6 | **100.0%** |
| `reservations` | 4 | 0 | 0.0% |

같은 표의 두 줄이 HOT의 **두 전제 조건**을 각각 보여준다. `schedule_seats`는 페이지에 빈 공간이
있고(`fillfactor=90`) 갱신 컬럼에 인덱스가 없다 → 100%. `reservations`는 `status`를 바꾸는데
`idx_reservations_pending_expires_at`이 그 컬럼을 덮고 있다 → 0%. 둘 중 하나만으로는 성립하지
않는다.

표본이 6건이라 경향만 본 것이다. 부하 테스트(Task 027)에서 다시 재야 한다.

### 조회 비용 — 걱정한 쪽이 반대였다

`idx(schedule_id, status)`를 두지 않았으니 잔여석 집계가 전체 스캔이 될까 걱정했다. 아니었다.

| 쿼리 | 실행 시간 | 플랜 |
|---|---|---|
| 등급별 예매 가능 좌석 COUNT | **0.82 ms** | `uk_schedule_seats_schedule_seat` Bitmap Index Scan → HashAggregate |
| 좌석맵 조회 (3,200석) | **10.70 ms** | 같은 인덱스 + `seats` **Seq Scan**(7,120행) + Sort(397 kB) |

`UNIQUE(schedule_id, seat_id)`의 선두 컬럼이 `schedule_id`라서 회차 범위가 그 인덱스로 좁혀진다.
HOT을 지킨 결정이 조회 비용을 물지 않았다.

오히려 좌석맵 조회가 13배 비싸고, 원인은 `schedule_seats`가 아니라 `seats` 전체 스캔과 3,200행
정렬이다. 3,200/7,120을 쓰므로 플래너가 Seq Scan을 고른 건 합리적이다. **Task 027에서 볼 건
`idx(schedule_id, status)`가 아니라 좌석맵의 조인·정렬 전략이다.**

### 응답 크기

```
GET /api/v1/schedules/17/seats   (고척스카이돔 3,200석)
HTTP 200 / 408,705 bytes
```

계획 단계 추정은 250~300 KB였고 실제는 약 400 KB다. 평면 배열이라 좌석마다 `section`/`rowLabel`
문자열이 반복되는 비용이다. 구역별로 중첩하면 줄겠지만 배치도 화면(Task 023)이 없는 상태에서
어떤 구조가 쓰기 좋은지 모르므로 단순한 쪽을 먼저 냈다.

## 9. FE 계약을 깨지 않고 넘긴 방법

BE가 `seatIds`를 필수로 받게 되면 FE 요청 형식이 어긋난다. 그런데 좌석 배치도 화면은 Task 023이다.
그 구간을 어떻게 넘기는가가 문제였다.

`ticket-selector.tsx`에 임시 좌석 선택을 넣으려다 **Server Action에서 흡수하는 쪽으로 바꿨다.**
`createReservationAction`은 이미 서버에서 돌기 때문에, 거기서 좌석을 조회해 앞자리를 고르면
컴포넌트를 건드릴 필요가 없다.

```ts
// lib/actions/reservation.ts — 한시적 코드. Task 023에서 폼이 보낸 seatIds로 교체된다.
async function pickAvailableSeatIds(
  scheduleId: number, ticketGradeId: number, quantity: number
): Promise<number[] | null> {
  const seats = await getScheduleSeats(scheduleId);
  const picked = seats
    .filter((seat) => seat.ticketGradeId === ticketGradeId && seat.status === "AVAILABLE")
    .slice(0, quantity)
    .map((seat) => seat.id);

  return picked.length === quantity ? picked : null;
}
```

BE 응답이 이미 배치도 순서(`ORDER BY pos_y, pos_x`)로 정렬돼 있어서 `slice`만으로 앞자리가 잡힌다.
결과적으로 **컴포넌트와 e2e 스펙을 한 줄도 고치지 않고** 예매가 좌석 단위로 돌았다.

```
reservation_no  |  status   | quantity | reservation_seats |       seats
----------------+-----------+----------+-------------------+--------------------
TK261008-000001 | CANCELLED |        1 |                 1 | VIP석 좌측 1열 1번
```

e2e가 만든 예약이다. 앞자리가 배정됐고, 이력 1행이 매수와 일치하고, 취소 후 좌석 26,894석이 전부
`AVAILABLE`로 돌아왔다. 이력 행은 append-only라 취소 뒤에도 어느 좌석이었는지 조회된다.

API 계약이 깨졌는데 UI 계약은 안 깨진 셈이다. Server Action이 폼 핸들러가 아니라 **FE/BE 경계의
변환 지점**으로 쓰였다. 같은 일을 클라이언트에서 하려면 좌석 400 KB를 브라우저로 내려보냈다가
ID 몇 개만 다시 올려보내야 한다.

## 10. 구현에서 걸린 함정

### 함정 1 — `posX`가 `posx`로 매핑된다

엔티티를 만들고 `ddl-auto: validate`가 기동을 막았다.

```
Schema-validation: missing column [posx] in table [seats]
```

`CamelCaseToUnderscoresNamingStrategy`의 `isUnderscoreRequired(before, current, after)`는 세
조건을 모두 요구한다 — 앞이 소문자, 현재가 대문자, **뒤가 소문자**. `posX`의 `X`는 뒤가 문자열
끝이라 세 번째가 깨지고 언더스코어가 안 들어간다. `@Column(name = "pos_x")`로 명시했다.

`validate`가 없었다면 Hibernate가 조용히 `posx`를 찾다가 **런타임 쿼리에서** 터졌을 것이다.
contract를 코드와 묶는 제약과 이 안전장치가 같은 설정에서 나온다.

### 함정 2 — `V6`가 백필 검증 테스트를 못 돌게 만든다

`V5__backfill_seats.sql`은 `tg.total_quantity`를 세 군데에서 읽는다. `SeatBackfillMigrationTest`는
그 파일을 `ScriptUtils`로 **재실행**해서 검증했는데, V6가 컬럼을 지우면 재실행 자체가 불가능해진다.

일회성 백필의 검증 수명이 V6에서 끝난다고 보고 테스트를 삭제했다. 다만 10개 단정 중 다섯 개는
백필이 아니라 **선점 경로가 계속 지켜야 하는 불변식**이라 `ReservationApiIntegrationTest.좌석_불변식`
으로 옮겼다.

| 불변식 | DB가 못 막는 이유 |
|---|---|
| 좌석의 공연장 = 회차 공연의 공연장 | 3홉이라 복합 FK로 표현 불가 |
| 활성 예약 둘이 한 좌석을 함께 점유하지 않음 | `reservation_seats`가 append-only |
| 예약별 좌석 수 = 매수 | 집계 조건 |
| 좌석 가격 합 = 결제 금액 | 집계 조건 |
| 한 예약의 좌석은 모두 같은 등급 | 여러 테이블 교차 |

### 함정 3 — `DROP COLUMN`이 CHECK 제약도 가져간다

`ck_ticket_grades_remaining_range CHECK (0 <= remaining_quantity AND remaining_quantity <= total_quantity)`
가 두 컬럼을 모두 참조한다. 명시적 `DROP CONSTRAINT`가 필요한지 확실하지 않았다.

롤백되는 트랜잭션에서 미리 시험했다.

```sql
BEGIN;
ALTER TABLE ticket_grades DROP COLUMN remaining_quantity;
ALTER TABLE ticket_grades DROP COLUMN total_quantity;
SELECT conname FROM pg_constraint WHERE conrelid = 'ticket_grades'::regclass AND contype = 'c';
ROLLBACK;
```

CHECK가 자동으로 사라졌고, `schedule_seats` 복합 FK가 의존하는
`uk_ticket_grades_id_schedule UNIQUE (id, schedule_id)`는 살아남았다. `EXPLAIN`과 마찬가지로,
**운영 데이터가 든 DB에서도 안전하게 미리 확인할 수 있는 수단**이다.

### 함정 4 — e2e가 프로덕션 빌드를 본다

`seatIds`를 추가하고 e2e를 돌렸더니 BE 로그에 `rejected value [null]`이 찍혔다. 코드에는 분명히
있는데 요청에는 없었다.

`playwright.config.ts`의 `webServer.command`가 `npm run start`다 — **프로덕션 서버**라 `.next`
빌드 산출물을 그대로 서비스한다. `next build`를 안 해서 예전 번들이 돌고 있었다. dev 서버라면
요청마다 재컴파일돼서 안 생기는 문제다.

의도된 설정이다. 프로덕션 빌드로 돌려야 Server Component 캐싱 같은 빌드 타임 동작까지 검증된다.
대가로 "e2e 전에 빌드한다"는 규칙이 생기고, 그걸 잊으면 **에러 없이 틀린 결과**가 나온다.

### 함정 5 — 중복 좌석을 조용히 제거하면 에러가 거짓말을 한다

`seatIds`를 `distinct()`로 정규화하는데, 처음에는 중복을 그냥 제거하려 했다. 그러면 `[1, 1]`이
`[1]`이 되어 선점 좌석 수(1)가 매수(2)보다 적어지고 **`SOLD_OUT`(409)으로 떨어진다.** 실제 원인은
잘못된 요청인데 사용자에게는 "자리가 없다"고 말하는 셈이다.

중복이 있으면 400으로 끊는다. 정규화와 검증을 섞으면 실패 원인이 숨는다.

## 11. 버린 것과 그 이유

- **`seatIds`가 없으면 BE가 자동 배정하는 폴백.** FE 무변경이라 매력적이었지만, 자동 배정은 모두가
  같은 좌석을 노리므로 조건부 UPDATE로는 "10석에 100명 → 1명만 성공"이 된다. `SELECT ... FOR UPDATE
  SKIP LOCKED`라는 **두 번째 동시성 전략**이 필요해져 Task가 두 배로 커진다. Server Action에서
  흡수하는 쪽이 전략을 하나로 유지한다.
- **`idx(schedule_id, status)` 추가.** 8절에서 집계가 0.82 ms로 측정돼 근거가 없어졌다. 넣으면
  `fillfactor = 90`과 HOT 100%를 포기하는 거래가 된다. V7(Task 027)에 측정값과 함께 넘겼다.
- **`uk_schedule_seats_schedule_seat` 위반을 409로 변환.** ROADMAP Task 022에 적혀 있던 항목인데
  **도달 불가**로 판명됐다. 선점은 UPDATE라 그 UNIQUE를 때릴 수 없고, `reservation_seats`의 UNIQUE는
  `distinct()` 정규화가 애초에 막는다. 영원히 안 타는 `catch`를 넣지 않고 ROADMAP을 고쳤다.
- **좌석 전용 `ErrorCode` 신설.** 등급에 없는 좌석은 기존 `create()`의 회차·등급 불일치와 같은 404,
  이미 잡힌 좌석은 `SOLD_OUT`(메시지가 이미 "잔여 좌석이 없습니다.")으로 충분했다. 좌석을 직접 고르는
  UX에서는 "몇 석 남았나"가 아니라 "내가 고른 자리를 누가 먼저 잡았나"가 전부다.
- **`ScheduleSeat.reservationId`를 `Reservation` 연관으로 매핑.** `domain/performance`가
  `domain/reservation`을 import하게 되어 한 방향이던 의존이 순환이 된다. 상태 전이가 전부 네이티브
  UPDATE라 연관을 타고 갈 일이 없다.

## 12. 남은 한계

- **HOT 측정 표본이 6건이다.** 경향은 분명하지만 절대 비율로 읽을 수는 없다. Task 027 부하
  테스트(`schedule_seats` 약 600만 행)에서 다시 재야 한다.
- **좌석맵 조회가 10.7 ms / 400 KB다.** dev 규모에서 그렇고, Task 027 목표 규모에서는 `seats`
  테이블도 훨씬 커진다. 좌표는 공연장마다 고정이라 상태와 분리해 캐시할 수 있지만, 배치도 화면이
  없는 상태에서 미리 쪼개면 추측이 된다.
- **`pickAvailableSeatIds`는 한시적 코드다.** Task 023에서 사라져야 한다. 남아 있으면 "사용자가
  고른 좌석"과 "서버가 고른 좌석" 두 경로가 공존하게 된다. 선점 직전에 좌석 400 KB를 한 번 더
  조회하는 왕복도 그때 없어진다.
- **`schedule_seats.reservation_id` FK의 multixact 오버헤드를 못 봤다.** 선점 UPDATE마다 부모
  `reservations` 행에 `FOR KEY SHARE` 락이 잡히고, 좌석 4개면 같은 부모에 네 번 걸린다. 이번 실측
  규모에서는 드러나지 않았다 — Task 027에서 봐야 한다.
- **`DataIntegrityViolationException`은 여전히 500이다.** 002의 10절, 003의 11절에서 후속 과제로
  남긴 것이 그대로다. 좌석 전환이 이 항목을 바꾸지 않았다.
- **새 공연장 좌석을 만드는 경로가 시드/수동 SQL뿐이다.** V6 이후 좌석 수의 원천이
  `seats` + `schedule_seats`인데 PRD "MVP 제외 범위"에 관리자 기능이 빠져 있다. `V1_1`·`V4_1`·`V5_1`이
  공연장 좌석 정의의 유일한 소스다.
- **열당 좌석 수(소규모 20 / 대규모 40)와 "VIP가 무대에 가깝다"는 자체 판단이다.** PRD·ERD에 근거가
  없다. Task 023 화면을 보고 조정할 수 있다.

## 13. 다음 단계

[Task 023: 좌석 배치도 화면](../ROADMAP.md) — `GET /schedules/{id}/seats`가 내려주는
`posX`/`posY`로 배치도를 그리고, 사용자가 고른 `seatIds`를 폼으로 보낸다. `pickAvailableSeatIds`가
그때 사라진다. 응답 크기(400 KB)와 평면 배열 형식이 실제로 쓰기 좋은지도 그때 판정된다.

[Task 027: 부하 테스트](../ROADMAP.md) — `schedule_seats` 약 600만 행에서 HOT 비율, 좌석맵 조회,
multixact 오버헤드를 다시 잰다. `V7` 인덱스 추가 여부가 그 측정으로 결정된다. 이번 문서의 8절이
그 before 기준선이다.

## 재현 방법

```bash
# 1. 깨끗한 DB에서 V1~V6 전체 체인
cd tikkit-back
docker compose down -v && docker compose up -d
./gradlew bootRun
#    -> flyway_schema_history에 1, 1.1, 2, 3, 3.1, 3.2, 4, 4.1, 5, 5.1, 6이 순서대로 찍힌다
#    -> seats 7,120행 / schedule_seats 26,894행 / 구역 12종

# 2. 동시성 수치 (8절)
./gradlew test --tests '*ReservationConcurrencyTest*'
#    -> 로그에 [초과 판매 차단], [좌석당 한 명], [겹치는 좌석 집합], [중복 선점 차단],
#       [결제-만료 경쟁 차단]으로 출력된다

# 3. 전체 테스트
./gradlew test

# 4. HOT 업데이트 비율 (8절) — e2e나 동시성 테스트를 한 번 돌린 뒤
docker exec -it tikkit-postgres psql -U tikkit_user -d tikkit_db -c "
SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / nullif(n_tup_upd, 0), 1) AS hot_pct
FROM pg_stat_user_tables WHERE relname IN ('schedule_seats', 'reservations');"

# 5. 조회 비용과 응답 크기 (8절)
docker exec -it tikkit-postgres psql -U tikkit_user -d tikkit_db -c "
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT ss.ticket_grade_id, count(*) FROM schedule_seats ss
WHERE ss.schedule_id = 17 AND ss.status = 'AVAILABLE' GROUP BY ss.ticket_grade_id;"
curl -s http://localhost:8080/api/v1/schedules/17/seats -o /dev/null -w "%{size_download} bytes\n"

# 6. 예매 플로우 (BE dev 프로필이 떠 있는 상태에서)
cd ../tikkit-front
npm run build        # playwright가 npm run start로 프로덕션 서버를 띄운다 (함정 4)
npm run test:e2e
```
