-- 지정석 전환 backfill 단계 (docs/ERD.md 2절 참조, Task 021)
--
-- 세 statement 모두 멱등하다 — ON CONFLICT DO NOTHING / NOT EXISTS 가드로 이미 처리된 행을 건너뛴다.
-- 멱등성은 운영 재실행 안전성 때문만이 아니다. 테스트 프로필은 db/seed를 로드하지 않아서 Flyway가
-- 이 파일을 실행하는 시점의 DB가 비어 있고, 그래서 기동 시의 실행에는 검증할 데이터가 없다.
-- SeatBackfillMigrationTest가 마이그레이션 전 모양의 픽스처를 넣고 이 파일을 그대로 재실행한다.
--
-- DO 블록이나 CREATE FUNCTION을 쓰지 않은 이유: 그 테스트가 ScriptUtils로 이 파일을 세미콜론
-- 기준으로 쪼개 실행하므로, 달러 인용으로 감싼 본문이 있으면 statement 분리가 깨진다.
-- 그래서 검증 실패 시 예외를 던지는 로직도 여기 넣지 않고 테스트 쪽 단정으로 옮겼다.

-- [1/3] venue별 좌석 그리드 생성
--
-- 좌석은 venue 단위 공유 자원이라, 그 공연장의 모든 회차를 통틀어 가장 큰 회차를 담을 수 있어야
-- 모든 회차의 schedule_seats가 만들어진다. 그래서 등급별 MAX(total_quantity)를 기준으로 삼는다.
-- 올림(CEIL)으로 생기는 여분 좌석은 seats에만 남고 schedule_seats는 받지 않는다([2/3]).
WITH grade_order(grade, grade_rank) AS (
    -- VIP가 무대에서 가장 가깝고 A가 가장 멀다. pos_y 오프셋 순서를 정한다.
    VALUES ('VIP', 1), ('R', 2), ('S', 3), ('A', 4)
),
block(block, block_rank, share) AS (
    -- 실제 공연장처럼 등급마다 좌·중앙·우 3블록으로 쪼갠다. share는 4분모 기준 비율이라
    -- 좌 25% / 중앙 50% / 우 25%가 된다. 열당 좌석 수가 4의 배수여서 나눗셈이 정확하다.
    VALUES ('좌', 1, 1), ('중', 2, 2), ('우', 3, 1)
),
venue_grade AS (
    SELECT p.venue_id, tg.grade, MAX(tg.total_quantity) AS max_quantity
    FROM ticket_grades tg
             JOIN schedules s ON s.id = tg.schedule_id
             JOIN performances p ON p.id = s.performance_id
    GROUP BY p.venue_id, tg.grade
),
venue_size AS (
    -- 돔 규모(수천 석)에 20석/열을 적용하면 60열이 넘어 실제 공연장과 동떨어진다.
    SELECT venue_id,
           CASE WHEN SUM(max_quantity) <= 1000 THEN 20 ELSE 40 END AS seats_per_row
    FROM venue_grade
    GROUP BY venue_id
),
sized AS (
    SELECT vg.venue_id,
           vg.grade,
           go.grade_rank,
           vs.seats_per_row,
           CEIL(vg.max_quantity::numeric / vs.seats_per_row)::int AS row_count
    FROM venue_grade vg
             JOIN venue_size vs ON vs.venue_id = vg.venue_id
             JOIN grade_order go ON go.grade = vg.grade
),
offsets AS (
    -- 앞선 등급들의 행 수를 누적해 pos_y 시작점을 잡는다.
    SELECT sz.*,
           COALESCE(SUM(sz.row_count) OVER (
               PARTITION BY sz.venue_id ORDER BY sz.grade_rank
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS rows_before
    FROM sized sz
),
block_layout AS (
    -- 앞선 블록들의 좌석 수를 누적하고 블록 사이에 통로 2칸을 둬 pos_x 시작점을 잡는다.
    SELECT o.venue_id,
           o.grade,
           o.row_count,
           o.rows_before,
           b.block,
           (o.seats_per_row * b.share / 4)::int AS block_seats,
           COALESCE(SUM((o.seats_per_row * b.share / 4)::int) OVER (
               PARTITION BY o.venue_id, o.grade ORDER BY b.block_rank
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
               + (b.block_rank - 1) * 2 AS x_offset
    FROM offsets o
             CROSS JOIN block b
)
INSERT INTO seats (venue_id, section, row_label, seat_number, pos_x, pos_y)
SELECT bl.venue_id,
       bl.grade || '-' || bl.block,
       -- 국내 예매처 관행대로 열은 숫자다 (docs/ERD.md 2절 "좌석 명명 규칙").
       r.r::text,
       n.n,
       bl.x_offset + n.n,
       bl.rows_before + r.r
FROM block_layout bl
         CROSS JOIN LATERAL generate_series(1, bl.row_count) AS r(r)
         CROSS JOIN LATERAL generate_series(1, bl.block_seats) AS n(n)
ON CONFLICT ON CONSTRAINT uk_seats_venue_section_row_number DO NOTHING;

-- [2/3] 회차별 schedule_seats 생성 (등급당 앞쪽 total_quantity개 좌석에만 등급을 배정)
--
-- section은 '{등급}-{블록}' 형식이라 split_part로 등급을 되찾는다. LIKE 'VIP-%' 대신 쓴 이유는
-- 등급 코드가 다른 등급의 접두어가 되는 경우(지금은 없지만 등급이 늘면 생길 수 있다)를 피하려는 것이다.
-- 한 등급의 좌석이 3블록에 흩어져 있으므로 "앞쪽"은 블록을 가로질러 pos_y, pos_x 순으로 센다.
WITH grade_seats AS (
    SELECT tg.id AS ticket_grade_id,
           tg.schedule_id,
           tg.total_quantity,
           st.id AS seat_id,
           ROW_NUMBER() OVER (PARTITION BY tg.id ORDER BY st.pos_y, st.pos_x) AS rn
    FROM ticket_grades tg
             JOIN schedules s ON s.id = tg.schedule_id
             JOIN performances p ON p.id = s.performance_id
             JOIN seats st ON st.venue_id = p.venue_id
                                  AND split_part(st.section, '-', 1) = tg.grade
)
INSERT INTO schedule_seats (schedule_id, seat_id, ticket_grade_id, status)
SELECT schedule_id, seat_id, ticket_grade_id, 'AVAILABLE'
FROM grade_seats
WHERE rn <= total_quantity
ON CONFLICT ON CONSTRAINT uk_schedule_seats_schedule_seat DO NOTHING;

-- [3/3] 기존 CONFIRMED/PENDING 예약을 좌석에 배정
--
-- 데이터 변경 CTE 한 문장으로 처리한다 (ReservationRepository.expirePendingReservations와 같은 패턴).
-- matched를 held와 최종 INSERT가 둘 다 참조하므로 Postgres 12+가 자동으로 materialize하고,
-- 덕분에 UPDATE와 INSERT가 같은 매칭 결과를 본다.
--
-- JOIN(LEFT JOIN이 아니라)이 의도적이다. 불변식이 깨져 좌석이 모자라면 행이 조용히 누락되는데,
-- 그걸 SeatBackfillMigrationTest가 건수 불일치로 잡는다. 여기서 예외를 던지려면 DO 블록이 필요하다.
--
-- expires_at이 이미 지난 PENDING도 그대로 HELD로 만든다. 건너뛰면 "SOLD+HELD == total - remaining"
-- 불변식이 깨지기 때문이다. 다만 Task 013의 만료 배치는 좌석을 모르므로, Task 022에서 만료·취소에
-- 좌석 반환을 넣기 전까지 그 좌석은 HELD로 고착된다 (ROADMAP Task 022 "필수 후속" 참조).
WITH active AS (
    -- CANCELLED/EXPIRED는 재고를 이미 반납했으므로 좌석을 주지 않는다.
    SELECT r.id, r.ticket_grade_id, r.status, r.quantity, r.unit_price, r.created_at
    FROM reservations r
    WHERE r.status IN ('CONFIRMED', 'PENDING')
      AND NOT EXISTS (SELECT 1 FROM reservation_seats rs WHERE rs.reservation_id = r.id)
),
expanded AS (
    -- 매수만큼 펼쳐 (예약, 좌석) 1:1로 만든다. "한 예약 = 한 등급" 정책 덕분에 등급 단위
    -- 파티션만으로 충분하다 — 등급은 회차마다 유일해서 회차 경계도 자동으로 지켜진다.
    SELECT a.id,
           a.ticket_grade_id,
           a.status,
           a.unit_price,
           ROW_NUMBER() OVER (PARTITION BY a.ticket_grade_id
               ORDER BY a.created_at, a.id, seq.seq) AS rn
    FROM active a
             CROSS JOIN LATERAL generate_series(1, a.quantity) AS seq(seq)
),
free_seats AS (
    -- 먼저 예매한 사람이 앞자리를 받도록 좌석도 같은 기준(앞열 → 왼쪽)으로 순번을 매긴다.
    SELECT ss.id,
           ss.ticket_grade_id,
           ROW_NUMBER() OVER (PARTITION BY ss.ticket_grade_id ORDER BY st.pos_y, st.pos_x) AS rn
    FROM schedule_seats ss
             JOIN seats st ON st.id = ss.seat_id
    WHERE ss.status = 'AVAILABLE'
      AND ss.reservation_id IS NULL
),
matched AS (
    SELECT e.id AS reservation_id, e.status, e.unit_price, f.id AS schedule_seat_id
    FROM expanded e
             JOIN free_seats f ON f.ticket_grade_id = e.ticket_grade_id AND f.rn = e.rn
),
held AS (
    -- status와 reservation_id를 한 번에 같이 쓴다 (ck_schedule_seats_status_holder).
    UPDATE schedule_seats ss
    SET status         = CASE m.status WHEN 'CONFIRMED' THEN 'SOLD' ELSE 'HELD' END,
        reservation_id = m.reservation_id,
        updated_at     = now()
    FROM matched m
    WHERE ss.id = m.schedule_seat_id
    RETURNING ss.id
)
INSERT INTO reservation_seats (reservation_id, schedule_seat_id, price)
-- 등급 단가 스냅샷이므로 좌석별 가격이 전부 같다. 좌석별 차등 가격은 범위 밖이다.
SELECT m.reservation_id, m.schedule_seat_id, m.unit_price
FROM matched m
ON CONFLICT ON CONSTRAINT uk_reservation_seats_reservation_seat DO NOTHING;
