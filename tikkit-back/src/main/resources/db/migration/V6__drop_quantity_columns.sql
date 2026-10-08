-- 지정석 전환 contract 단계 (docs/ERD.md 2절 참조, Task 022)
--
-- 재고의 원천이 뒤집혔다. 전환 전에는 total_quantity(숫자)가 원천이고 좌석이 파생이었지만,
-- 이제는 seats + schedule_seats가 원천이고 "잔여 수량"은 schedule_seats의 AVAILABLE 건수로 계산된다.
-- 두 수량 컬럼을 읽거나 쓰는 코드가 하나도 남지 않은 뒤에만 이 마이그레이션을 돌릴 수 있다.
--
-- expand(V4) → backfill(V5) → contract(V6)의 마지막 단계다. expand는 "스키마 먼저, 코드 나중"이
-- 가능했지만 contract는 반대다 — ddl-auto가 validate라서 컬럼만 지우면 Hibernate가 기동을 거부한다.
-- 그래서 이 파일과 TicketGrade 엔티티의 필드 제거가 같은 커밋에 들어간다.
--
-- ck_ticket_grades_remaining_range(0 <= remaining AND remaining <= total)는 두 컬럼을 모두
-- 참조하므로 DROP COLUMN에 딸려 자동으로 사라진다. 별도 DROP CONSTRAINT를 두지 않았고,
-- 롤백되는 트랜잭션에서 실제로 확인했다. 컬럼의 COMMENT도 함께 사라진다.
--
-- uk_ticket_grades_id_schedule UNIQUE (id, schedule_id)는 남는다. schedule_seats의 복합 FK
-- (ticket_grade_id, schedule_id) → ticket_grades (id, schedule_id)가 그 제약에 의존하므로
-- 같이 지워지면 안 된다.
--
-- reservations 스키마는 건드리지 않는다. "한 예약 = 한 등급" 정책을 유지하므로
-- ticket_grade_id / unit_price / quantity는 그대로 쓴다 (docs/ERD.md 2절 "등급 혼합 정책").
-- reservations.quantity는 이제 "매수"이면서 동시에 "좌석 수"이고, 둘이 어긋난 요청은
-- ReservationService가 400으로 끊는다.
--
-- DROP COLUMN은 ACCESS EXCLUSIVE 락을 잡지만 Postgres가 데이터를 다시 쓰지 않고 카탈로그만
-- 바꾸므로 즉시 끝난다. 테이블 크기와 무관하다.

ALTER TABLE ticket_grades
    DROP COLUMN remaining_quantity;

ALTER TABLE ticket_grades
    DROP COLUMN total_quantity;
