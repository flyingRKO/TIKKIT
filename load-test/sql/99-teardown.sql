-- 부하 테스트 데이터 삭제 (Task 027)
--
-- 빠른 길은 이 파일이 아니라 볼륨을 비우는 것이다:
--   docker compose -f docker-compose.prod.yml down -v
-- Postgres는 DELETE로 디스크를 반납하지 않는다. 600만 행을 지워도 파일 크기는 그대로고
-- VACUUM FULL(ACCESS EXCLUSIVE 락 + 테이블 크기만큼의 여유 공간)이 따로 필요하다.
--
-- 이 파일은 "dev 시드와 e2e 데이터는 남기고 부하 데이터만 걷어내고 싶을 때" 쓴다.
-- 삭제 순서는 FK 역순이고, 특히 schedule_seats가 reservations를 참조하므로
-- 예약보다 좌석을 먼저 지워야 한다.
--
-- == FK 역방향 인덱스가 없으면 이 스크립트는 끝나지 않는다 (Task 030에서 실측) ==
--
-- Postgres는 FK 자식 컬럼에 인덱스를 자동으로 만들지 않는다. 부모 행을 지울 때마다
-- "이걸 참조하는 자식이 남아있나"를 확인하는데, 그 컬럼에 인덱스가 없으면 자식 테이블을
-- **풀스캔**한다. 부모가 N행이면 N번이다.
--
-- 복합 유니크가 있어도 **선두 컬럼이 아니면 못 탄다.** schedule_seats.seat_id는
-- uk_schedule_seats_schedule_seat(schedule_id, seat_id)의 두 번째라 seat_id로는 못 찾는다.
-- "인덱스 있으니 괜찮다"는 착각이 여기서 난다.
--
-- 600만 행(회차 3,000 × 좌석 2,000)에서 실측한 비용:
--   DELETE FROM reservations    예약 80,814행 × schedule_seats 600만 풀스캔 -> 41분에도 미완
--   DELETE FROM schedule_seats  좌석 600만행 × reservation_seats 20만 풀스캔 -> 10분에도 미완
--   DELETE FROM seats           좌석 2,000행 × schedule_seats 599MB 풀스캔  -> 미완
-- DELETE는 트랜잭션 안에서 테이블을 작게 만들지 않는다. 먼저 지운 행도 dead tuple로 힙에
-- 남아 있어서 뒤따르는 풀스캔은 여전히 모든 페이지를 읽는다.
--
-- 그래서 아래 [0/3]에서 필요한 인덱스를 **트랜잭션 안에서 만들고 커밋 전에 지운다.**
-- 영구 추가하지 않는 이유: V4__create_seat_tables.sql:38이 idx(reservation_id)를 의도적으로
-- 뺐다. 모든 선점/해제가 그 컬럼을 UPDATE하므로 인덱스가 붙으면 HOT 업데이트가 깨진다
-- (docs/ERD.md 2절). 런타임 쓰기 성능을 위해 일회성 삭제 비용을 받는 트레이드오프이고,
-- 이 스크립트가 그 비용을 지불하는 자리다.
--
-- 스키마가 바뀐 뒤 인덱스를 못 타는 FK를 다시 뽑는 조회는
-- docs/improvements/009-frontend-performance.md의 재현 방법에 있다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/99-teardown.sql

BEGIN;

-- [0/3] 삭제 전용 임시 인덱스. 600만 행에서도 각 0.3~2초면 만들어진다 — 풀스캔 한 번보다 싸다.
-- 커밋 전에 전부 DROP하므로 다른 세션에는 보이지 않고, 실패해 롤백되면 같이 사라진다.
CREATE INDEX tmp_teardown_ss_reservation ON schedule_seats (reservation_id);
CREATE INDEX tmp_teardown_ss_seat        ON schedule_seats (seat_id);
CREATE INDEX tmp_teardown_ss_grade       ON schedule_seats (ticket_grade_id, schedule_id);
CREATE INDEX tmp_teardown_rs_sseat       ON reservation_seats (schedule_seat_id);
CREATE INDEX tmp_teardown_res_schedule   ON reservations (schedule_id);
CREATE INDEX tmp_teardown_res_grade      ON reservations (ticket_grade_id, schedule_id);
CREATE INDEX tmp_teardown_perf_venue     ON performances (venue_id);

-- [1/3] 부하 데이터의 회차·예약 id를 한 번만 구해 재사용한다.
CREATE TEMP TABLE load_schedules ON COMMIT DROP AS
SELECT s.id
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나';

CREATE TEMP TABLE load_reservations ON COMMIT DROP AS
SELECT r.id
FROM reservations r
WHERE r.schedule_id IN (SELECT id FROM load_schedules);

-- [2/3] FK 역순으로 삭제.
DELETE FROM reservation_seats WHERE reservation_id IN (SELECT id FROM load_reservations);
DELETE FROM schedule_seats WHERE schedule_id IN (SELECT id FROM load_schedules);
DELETE FROM payments WHERE reservation_id IN (SELECT id FROM load_reservations);
DELETE FROM reservations WHERE id IN (SELECT id FROM load_reservations);
DELETE FROM ticket_grades WHERE schedule_id IN (SELECT id FROM load_schedules);
DELETE FROM schedules WHERE id IN (SELECT id FROM load_schedules);

DELETE FROM seats
WHERE venue_id = (SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나');

DELETE FROM performances
WHERE venue_id = (SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나');

DELETE FROM venues WHERE name = '[LOAD] 부하테스트 아레나';

-- 계정은 공연장에 매달려 있지 않아 이메일 패턴으로 지운다.
-- 예약이 남아 있으면 FK가 막는데, 부하 데이터 예약은 위에서 이미 지웠다.
DELETE FROM members WHERE email LIKE 'loadtest%@tikkit.com';

-- [3/3] 임시 인덱스 회수. 여기까지 왔으면 FK 검사는 모두 끝났다.
DROP INDEX tmp_teardown_ss_reservation;
DROP INDEX tmp_teardown_ss_seat;
DROP INDEX tmp_teardown_ss_grade;
DROP INDEX tmp_teardown_rs_sseat;
DROP INDEX tmp_teardown_res_schedule;
DROP INDEX tmp_teardown_res_grade;
DROP INDEX tmp_teardown_perf_venue;

COMMIT;

DROP PROCEDURE IF EXISTS load_test_fill_schedule_seats(int);

-- DELETE는 디스크를 반납하지 않는다. 다음 측정이 600만 행이 남긴 bloat 위에서 돌지 않도록
-- 공간을 돌려주고 통계를 다시 잡는다. VACUUM FULL은 트랜잭션 안에서 못 돌려서 여기 둔다.
VACUUM (FULL, ANALYZE) schedule_seats;
VACUUM (FULL, ANALYZE) reservation_seats;
VACUUM (FULL, ANALYZE) reservations;
VACUUM (ANALYZE) payments, ticket_grades, schedules, performances, seats, venues, members;

SELECT (SELECT count(*) FROM venues WHERE name = '[LOAD] 부하테스트 아레나') AS venue_left,
       (SELECT count(*) FROM schedule_seats)                                AS schedule_seats_left,
       (SELECT count(*) FROM members WHERE email LIKE 'loadtest%')          AS members_left,
       pg_size_pretty(pg_relation_size('schedule_seats'))                   AS schedule_seats_size;
