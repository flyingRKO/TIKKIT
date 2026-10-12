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
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/99-teardown.sql

BEGIN;

-- 부하 데이터의 회차 id를 한 번만 구해 재사용한다.
CREATE TEMP TABLE load_schedules ON COMMIT DROP AS
SELECT s.id
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나';

CREATE TEMP TABLE load_reservations ON COMMIT DROP AS
SELECT r.id
FROM reservations r
WHERE r.schedule_id IN (SELECT id FROM load_schedules);

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

COMMIT;

DROP PROCEDURE IF EXISTS load_test_fill_schedule_seats(int);

SELECT (SELECT count(*) FROM venues WHERE name = '[LOAD] 부하테스트 아레나') AS venue_left,
       (SELECT count(*) FROM schedule_seats)                                AS schedule_seats_left,
       (SELECT count(*) FROM members WHERE email LIKE 'loadtest%')          AS members_left;
