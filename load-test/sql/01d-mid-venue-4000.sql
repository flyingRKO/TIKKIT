-- 교차점 측정용 중간 규모 공연장 (Task 041)
--
-- 01c-large-venue.sql의 변종이다. 공연 1개 / 회차 1개는 그대로 두고 좌석만
-- 10,000석 -> 4,000석으로 줄였다.
--
-- ★ 왜 이 좌석 수가 필요한가 ★
--   009_2가 정적 좌석을 등급별 <path>로 합치면서 **같은 변경이 2,000석에서는 회귀이고
--   10,000석에서는 개선**인 것을 발견했다 (RecalcStyle 18.71 -> 31.59ms / 71.84 -> 29.20ms).
--   그 문서 7절이 "교차점을 모른다 — 두 점만 재서 그 지점을 특정할 수 없다"로 남겼고,
--   8절 항목 5가 "4,000·6,000석을 추가하면 부호가 바뀌는 지점을 좁힐 수 있다"로 적었다.
--
-- ★ 열당 좌석을 01c와 같은 100으로 고정했다 ★
--   01b(2,000석)는 40석/열, 01c(10,000석)는 100석/열이다. 신규 군을 100석/열로 두면
--   4,000 / 6,000 / 10,000이 **열 수만 다른 축**이 되어 좌석 수 외의 변수가 하나 줄어든다.
--   대신 종횡비가 2.5(4,000) / 1.67(6,000) / 1.0(10,000)로 달라진다 — 등급 비율 1:2:3:4를
--   지키려면 열 수가 10의 배수여야 해서 종횡비를 1 근처로 맞출 수가 없다. **한계로 기록한다**
--   (009_2도 0.8 대 1.0인 두 점으로 결론을 냈으므로 같은 수준의 제약이다).
--
-- ⚠ 01 / 01b / 01c / 01d / 01e는 같은 DB에 공존시키지 않는다.
--   공연장 이름을 '[LOAD] 부하테스트 아레나'로 공유한다 — 99-teardown.sql이 그 이름 하나를
--   따라가며 지우기 때문이고, 이름을 바꾸면 teardown이 이 시드를 못 지운다.
--   대신 가드가 "공연장 이름 = 제목별 / 회차 = 공연별"이라 섞으면 에러 없이 조용히 어긋난다.
--   군을 바꿀 때는 99-teardown.sql로 비우고 다시 넣는다.
--
-- ⚠ 좌석맵 응답 바이트는 schedule_seats.id 자릿수에 선형으로 반응한다 (009_1 3.8절).
--   teardown은 시퀀스를 되돌리지 않으므로 군을 갈아끼우면 id 자릿수가 변한다.
--   바이트는 같은 군·같은 세션 안에서만 비교하고, 표에 id 자릿수를 함께 적는다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres --     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/01d-mid-venue-4000.sql
--   그 다음 02-bulk-schedule-seats.sql로 schedule_seats를 채운다 (회차 1개 x 4,000석).
--
-- 규모: schedule_seats 4,000 = 회차 1개 x 좌석 4,000석.

-- [1/5] 부하 전용 공연장 (01b와 같은 이름)
INSERT INTO venues (name, address)
VALUES ('[LOAD] 부하테스트 아레나', '서울특별시 송파구 부하로 1')
ON CONFLICT ON CONSTRAINT uk_venues_name DO NOTHING;

-- [2/5] 물리 좌석 4,000석 (VIP 400 / R 800 / S 1,200 / A 1,600, 100석/열)
--
-- 등급 비율을 01b/01c와 1:2:3:4로 똑같이 맞췄다. 선택 가능 좌석 수(= A등급)도 같은 비율로
-- 늘어나야 "좌석당" 환산값을 군 사이에서 비교할 수 있다.
--
-- 100석/열은 V5의 "1,000석 초과면 40석/열" 규칙에서 의도적으로 벗어난 값이고, 01c와 맞춘
-- 것이다. 측정 전용 시드이고 운영 백필(V5)은 건드리지 않는다.
WITH v AS (
    SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나'
),
layout(grade, grade_rank, row_count) AS (
    -- VIP가 무대에 가장 가깝고 A가 가장 멀다. 합계가 40열 x 100석 = 4,000석이다.
    VALUES ('VIP', 1, 4), ('R', 2, 8), ('S', 3, 12), ('A', 4, 16)
),
offsets AS (
    -- 앞선 등급들의 열 수를 누적해 pos_y 시작점을 잡는다 (01b/01c/V5와 같은 방식).
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

-- [3/5] 공연 1개 — ON_SALE (01b/01c와 동일)
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

-- [4/5] 회차 1개 + 등급 4개 (01b/01c와 동일)
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

-- [5/5] 공연의 start_date/end_date를 회차에서 파생 (01b/01c와 동일)
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
