-- 확장성 측정용 대형 공연장 (Task 030_2)
--
-- 01b-small-catalog.sql의 변종이다. 공연 1개 / 회차 1개는 그대로 두고 좌석만
-- 2,000석 -> 10,000석으로 늘렸다.
--
-- ★ 왜 좌석 수를 늘린 시드가 따로 필요한가 ★
--   Task 030_2의 수용/기각 근거는 "지금 몇 KB 줄었나"가 아니라 "좌석당 비용의 기울기가
--   줄었나"다 (ROADMAP: "근거가 전송량이 아니라 확장성이다"). 좌석맵 문서 전송이 32.9KB뿐이라
--   반으로 줄여도 16KB이고, 그 숫자로는 구조 변경을 정당화할 수 없다.
--   기울기는 한 점에서 말할 수 없어서 두 번째 측정 지점이 필요하다.
--
--   더해서 009 4.8절이 memo + Set 전환을 "이 규모에서 이득 없음"으로 기각했는데 그
--   "이 규모"가 2,000석 / 선택 상한 4매였다. 좌석 수를 바꿔야 그 기각의 유효 범위가 정해진다.
--
-- ⚠ 01 / 01b / 01c는 같은 DB에 공존시키지 않는다.
--   공연장 이름을 01b와 같은 '[LOAD] 부하테스트 아레나'로 쓴다 — 99-teardown.sql이 그 이름
--   하나를 따라가며 지우기 때문이고, 이름을 바꾸면 teardown이 이 시드를 못 지운다.
--   대신 가드가 "공연장 이름 = 제목별 / 회차 = 공연별"이라 섞으면 에러 없이 조용히 어긋난다.
--   군을 바꿀 때는 99-teardown.sql로 비우고 다시 넣는다.
--
-- ⚠ 좌석맵 응답 바이트는 schedule_seats.id 자릿수에 선형으로 반응한다 (009_1 3.8절).
--   teardown은 시퀀스를 되돌리지 않으므로 군을 갈아끼우면 id 자릿수가 변한다.
--   바이트는 같은 군·같은 세션 안에서만 비교하고, 표에 id 자릿수를 함께 적는다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/01c-large-venue.sql
--   그 다음 02-bulk-schedule-seats.sql로 schedule_seats를 채운다 (회차 1개 x 10,000석).
--
-- 규모: schedule_seats 10,000 = 회차 1개 x 좌석 10,000석.

-- [1/5] 부하 전용 공연장 (01b와 같은 이름)
INSERT INTO venues (name, address)
VALUES ('[LOAD] 부하테스트 아레나', '서울특별시 송파구 부하로 1')
ON CONFLICT ON CONSTRAINT uk_venues_name DO NOTHING;

-- [2/5] 물리 좌석 10,000석 (VIP 1,000 / R 2,000 / S 3,000 / A 4,000, 100석/열)
--
-- 등급 비율을 01b와 1:2:3:4로 똑같이 맞췄다. 선택 가능 좌석 수(= A등급)도 같은 비율로
-- 늘어나야 "좌석당" 환산값을 두 군 사이에서 비교할 수 있다.
--
-- 100석/열은 V5의 "1,000석 초과면 40석/열" 규칙에서 의도적으로 벗어난 값이다. 40석/열로
-- 10,000석을 만들면 250열이 되어 배치도가 폭 40 x 높이 250의 극단적인 세로 비율이 된다.
-- 그러면 좌석 대부분이 화면 밖으로 밀려서 페인트 비용이 과소 측정되고, 2,000석군(40 x 50)과
-- 종횡비가 달라져 비교 자체가 흔들린다. 100 x 100으로 두면 종횡비가 0.8 -> 1.0으로
-- 가까이 남는다. 측정 전용 시드이고 운영 백필(V5)은 건드리지 않는다.
WITH v AS (
    SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나'
),
layout(grade, grade_rank, row_count) AS (
    -- VIP가 무대에 가장 가깝고 A가 가장 멀다. 합계가 100열 x 100석 = 10,000석이다.
    VALUES ('VIP', 1, 10), ('R', 2, 20), ('S', 3, 30), ('A', 4, 40)
),
offsets AS (
    -- 앞선 등급들의 열 수를 누적해 pos_y 시작점을 잡는다 (01b/V5와 같은 방식).
    SELECT l.grade,
           l.row_count,
           COALESCE(SUM(l.row_count) OVER (
               ORDER BY l.grade_rank
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS rows_before
    FROM layout l
)
INSERT INTO seats (venue_id, section, row_label, seat_number, pos_x, pos_y)
SELECT v.id,
       o.grade,
       r.r::text,
       n.n,
       n.n,
       o.rows_before + r.r
FROM v
         CROSS JOIN offsets o
         CROSS JOIN LATERAL generate_series(1, o.row_count) AS r(r)
         CROSS JOIN LATERAL generate_series(1, 100) AS n(n)
ON CONFLICT ON CONSTRAINT uk_seats_venue_section_row_number DO NOTHING;

-- [3/5] 공연 1개 — ON_SALE (01b와 동일)
INSERT INTO performances (title, category, description, poster_url, venue_id,
                          running_minutes, age_rating, status)
SELECT '[LOAD] 부하테스트 공연 ' || lpad(g.n::text, 4, '0'),
       (ARRAY['CONCERT', 'MUSICAL', 'THEATER', 'CLASSIC', 'SPORTS'])[1 + (g.n % 5)],
       NULL,
       NULL,
       v.id,
       120,
       '전체 관람가',
       'ON_SALE'
FROM (SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나') v
         CROSS JOIN generate_series(1, 1) AS g(n)
-- performances.title에는 유니크 제약이 없어서 ON CONFLICT를 쓸 수 없다.
WHERE NOT EXISTS (
    SELECT 1 FROM performances p
    WHERE p.title = '[LOAD] 부하테스트 공연 ' || lpad(g.n::text, 4, '0')
);

-- [4/5] 회차 1개 + 등급 4개 (01b와 동일)
INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at)
SELECT p.id,
       date_trunc('hour', now()) + interval '30 days' + (k.k || ' days')::interval + interval '19 hours',
       now() - interval '3 days',
       date_trunc('hour', now()) + interval '30 days' + (k.k || ' days')::interval + interval '17 hours'
FROM performances p
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나'
         CROSS JOIN generate_series(1, 1) AS k(k)
WHERE NOT EXISTS (SELECT 1 FROM schedules s WHERE s.performance_id = p.id);

INSERT INTO ticket_grades (schedule_id, grade, price)
SELECT s.id, g.grade, g.price
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나'
         CROSS JOIN (VALUES ('VIP', 150000::numeric(12, 0)),
                            ('R', 99000::numeric(12, 0)),
                            ('S', 66000::numeric(12, 0)),
                            ('A', 44000::numeric(12, 0))) AS g(grade, price)
ON CONFLICT ON CONSTRAINT uk_ticket_grades_schedule_grade DO NOTHING;

-- [5/5] 공연의 start_date/end_date를 회차에서 파생 (01b와 동일)
UPDATE performances p
SET start_date = sub.min_show_date,
    end_date   = sub.max_show_date,
    updated_at = now()
FROM (SELECT s.performance_id,
             MIN(s.show_at)::date AS min_show_date,
             MAX(s.show_at)::date AS max_show_date
      FROM schedules s
               JOIN performances pp ON pp.id = s.performance_id
               JOIN venues v ON v.id = pp.venue_id AND v.name = '[LOAD] 부하테스트 아레나'
      GROUP BY s.performance_id) sub
WHERE p.id = sub.performance_id
  AND p.start_date IS DISTINCT FROM sub.min_show_date;

-- 결과 확인 — 좌석 10,000 / 공연 1 / 회차 1
SELECT (SELECT count(*) FROM seats st JOIN venues v ON v.id = st.venue_id
        WHERE v.name = '[LOAD] 부하테스트 아레나')                          AS seats,
       (SELECT count(*) FROM performances p JOIN venues v ON v.id = p.venue_id
        WHERE v.name = '[LOAD] 부하테스트 아레나')                          AS performances,
       (SELECT count(*) FROM schedules s JOIN performances p ON p.id = s.performance_id
        JOIN venues v ON v.id = p.venue_id WHERE v.name = '[LOAD] 부하테스트 아레나') AS schedules;
