-- 부하 데이터의 예약을 지우고 좌석을 AVAILABLE로 되돌린다 (Task 027)
--
-- 측정을 반복하려면 매번 이걸 돌려야 한다. 예매 시나리오는 좌석을 HELD/SOLD로 바꾸는데,
-- 선점은 10분 홀드이고 만료 배치(ReservationExpiryScheduler, 60초 주기)는 10분이 지나야
-- 치우기 때문에 짧은 측정을 연달아 돌리면 좌석이 그대로 소진된 상태로 남는다.
--
-- 그러면 다음 측정이 조용히 다른 것을 재게 된다 — 좌석맵에 빈자리가 없으면 예매 시나리오의
-- iteration이 선점 요청 없이 조기 종료하므로, "선점 처리량"이 아니라 "좌석맵 조회 처리량"이
-- 측정된다. 실제로 그렇게 나온 적이 있다(SAMPLE_SCHEDULES=1로 두 번 연달아 돌렸을 때
-- 두 번째 실행의 선점 시도가 0건이었다).
--
-- 99-teardown.sql과 다르다. 이쪽은 카탈로그(공연·회차·좌석·계정)를 남기고 예약만 지운다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/98-reset-reservations.sql

BEGIN;

CREATE TEMP TABLE load_reservations ON COMMIT DROP AS
SELECT r.id
FROM reservations r
         JOIN schedules s ON s.id = r.schedule_id
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나';

-- 좌석을 먼저 푼다. ck_schedule_seats_status_holder 때문에 status와 reservation_id를
-- 반드시 한 번에 같이 써야 한다 (AVAILABLE이면 reservation_id가 NULL이어야 한다).
--
-- **reservation_seats를 경유하는 게 핵심이다.** schedule_seats.reservation_id에는 인덱스가
-- 없다 — 모든 선점/해제가 그 컬럼을 갱신하므로 인덱스가 있으면 HOT 업데이트가 깨지기 때문에
-- V4가 의도적으로 뺐다. 그래서 `WHERE ss.reservation_id IN (...)`으로 쓰면 600만 행 풀스캔이
-- 되고, 처음에 그렇게 썼다가 5분이 지나도 안 끝나서 중단했다.
--
-- reservation_seats에는 uk_reservation_seats_reservation_seat (reservation_id, schedule_seat_id)가
-- 있어서 예약 -> 좌석 id를 인덱스로 찾고, 그 다음은 schedule_seats의 PK로 바로 꽂힌다.
-- V4 주석이 "좌석 해제는 reservation_seats를 경유한다"고 적어둔 경로가 이것이다.
UPDATE schedule_seats ss
SET status         = 'AVAILABLE',
    reservation_id = NULL,
    updated_at     = now()
FROM reservation_seats rs
WHERE rs.schedule_seat_id = ss.id
  AND rs.reservation_id IN (SELECT id FROM load_reservations)
  AND ss.status <> 'AVAILABLE';

-- 예약을 **지우지 않고 CANCELLED로 바꾼다.** 이게 이 스크립트에서 가장 중요한 결정이다.
--
-- DELETE FROM reservations를 쓰면 끝나지 않는다. schedule_seats.reservation_id가
-- reservations를 참조하는데 그 컬럼에 인덱스가 없어서(위 주석 참조), Postgres가 예약 1건을
-- 지울 때마다 "이 예약을 참조하는 좌석이 남아있나"를 확인하려고 600만 행을 풀스캔한다.
-- 예약 13,798건이면 그게 13,798번이다. 실제로 12분을 넘기고도 안 끝나서 pg_terminate_backend로
-- 끊었다(psql을 죽여도 서버 쿼리는 계속 돈다는 것도 그때 확인했다).
--
-- CANCELLED로 바꾸면 FK 검사가 아예 없고, 측정 반복에 필요한 두 가지가 다 해결된다 —
-- 좌석은 위에서 이미 AVAILABLE로 돌렸고, uk_reservations_pending_member_grade는
-- PENDING에만 걸리는 부분 인덱스라 CANCELLED가 되는 순간 풀린다.
--
-- 예약 행이 실행마다 쌓이는 건 받아들인다. 부하 테스트 데이터고, 완전 삭제는
-- 99-teardown.sql이 한다(그쪽은 schedule_seats를 먼저 지워서 이 문제를 피한다).
UPDATE reservations
SET status       = 'CANCELLED',
    cancelled_at = now(),
    updated_at   = now()
WHERE id IN (SELECT id FROM load_reservations)
  AND status IN ('PENDING', 'CONFIRMED');

COMMIT;

SELECT count(*) FILTER (WHERE status = 'AVAILABLE') AS available,
       count(*) FILTER (WHERE status <> 'AVAILABLE') AS not_available
FROM schedule_seats;
