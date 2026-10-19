-- FE 측정용 축소 카탈로그 (Task 030)
--
-- 01-bulk-catalog.sql의 변종이다. 공연 1개 / 회차 1개로 줄이고 나머지는 전부 같다.
-- 01을 고치지 않고 따로 둔 이유는 006/007/008의 "재현 방법" 블록이 01을 그대로
-- 참조하기 때문이다.
--
-- ★ 좌석 2,000석은 01과 같게 유지해야 한다. ★
--   Task 030은 "축소 시드와 600만 행에서 좌석맵 응답 바이트가 같다"를 전제로 측정
--   횟수를 줄였다. 좌석 수를 건드리면 그 전제가 깨지고 두 군을 비교할 수 없게 된다.
--   줄인 것은 회차 수뿐이고, 좌석맵 응답 크기는 "회차당 좌석 수"에서만 나온다.
--
-- 공연장 이름은 01과 같은 '[LOAD] 부하테스트 아레나'를 쓴다. 99-teardown.sql이 그
-- 이름 하나를 따라가며 지우므로 이름을 바꾸면 teardown이 이 시드를 못 지운다.
--
-- ⚠ 01과 같은 DB에 공존시키지 않는다. 가드가 공연=제목별 / 회차=공연별이라 섞으면
--   에러 없이 조용히 어긋난다 (공연 0001만 회차 1개로 남는다). 군을 바꿀 때는
--   99-teardown.sql로 비우고 다시 넣는다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/01b-small-catalog.sql
--
-- 규모: schedule_seats 2,000 = 회차 1개 x 좌석 2,000석.
--   공연 1개 x 회차 1개 = 1회차 / 공연장 1곳 x 2,000석

-- [1/5] 부하 전용 공연장
--
-- 공연장을 하나만 만든다. seats는 venue 단위 공유 자원이므로(V4 주석) 공연장이 하나면
-- 물리 좌석도 2,000행뿐이고, 늘어나는 건 schedule_seats 하나다.
-- 테이블별 행 수가 "회차 x 좌석" 하나로만 커져서 측정 결과를 해석하기 쉽다.
INSERT INTO venues (name, address)
VALUES ('[LOAD] 부하테스트 아레나', '서울특별시 송파구 부하로 1')
ON CONFLICT ON CONSTRAINT uk_venues_name DO NOTHING;

-- [2/5] 물리 좌석 2,000석 (VIP 200 / R 400 / S 600 / A 800, 40석/열)
--
-- V5 백필처럼 좌·중앙·우 3블록으로 쪼개지 않고 등급당 한 블록으로 둔다. 블록 분할은
-- 좌석맵 UI의 x 좌표를 위한 것이고, 부하 테스트는 응답 크기와 행 수만 보기 때문이다.
-- 덕분에 section이 등급명 그대로여서 [5/5]에서 split_part 없이 조인된다.
--
-- 40석/열은 V5의 "1,000석 초과면 40석/열" 규칙(돔 규모)을 따른 것이다.
WITH v AS (
    SELECT id FROM venues WHERE name = '[LOAD] 부하테스트 아레나'
),
layout(grade, grade_rank, row_count) AS (
    -- VIP가 무대에 가장 가깝고 A가 가장 멀다. 합계가 2,000석이 되도록 열 수를 맞췄다.
    VALUES ('VIP', 1, 5), ('R', 2, 10), ('S', 3, 15), ('A', 4, 20)
),
offsets AS (
    -- 앞선 등급들의 열 수를 누적해 pos_y 시작점을 잡는다 (V5와 같은 방식).
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
         CROSS JOIN LATERAL generate_series(1, 40) AS n(n)
ON CONFLICT ON CONSTRAINT uk_seats_venue_section_row_number DO NOTHING;

-- [3/5] 공연 1개 — ON_SALE (01은 1,000개)
--
-- dev 시드는 ON_SALE 회차가 id 1~8뿐이라 예매 부하를 걸 대상이 거의 없다.
-- 여기서는 전부 판매중으로 만든다. category를 5종으로 돌려서 목록 필터 조회가
-- 한 값에만 쏠리지 않게 한다.
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

-- [4/5] 회차 1개 (공연당 1개) + 등급 4개 (회차당 4개). 01은 3,000회차 / 12,000등급
--
-- booking_open_at은 이미 지났고 booking_close_at은 먼 미래여서 전 회차가 예매 가능하다.
-- ck_schedules_booking_window(open < close <= show)를 만족시키려고 close를 show - 2시간으로
-- 둔다 (dev 시드와 같은 관행).
INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at)
SELECT p.id,
       date_trunc('hour', now()) + interval '30 days' + (k.k || ' days')::interval + interval '19 hours',
       now() - interval '3 days',
       date_trunc('hour', now()) + interval '30 days' + (k.k || ' days')::interval + interval '17 hours'
FROM performances p
         JOIN venues v ON v.id = p.venue_id AND v.name = '[LOAD] 부하테스트 아레나'
         CROSS JOIN generate_series(1, 1) AS k(k)
-- 회차 3개를 한 덩어리로 보고 하나라도 있으면 건너뛴다. uk_schedules_performance_show_at에
-- ON CONFLICT를 걸 수도 있지만, show_at이 date_trunc('hour', now()) 기준이라 재실행 시각이
-- 다르면 충돌하지 않고 회차가 6개로 늘어난다.
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

-- [5/5] 공연의 start_date/end_date를 회차에서 파생
--
-- status는 [3/5]에서 ON_SALE로 직접 넣었다. 날짜만 채운다 — idx_performances_status_start_date가
-- (status, start_date) 복합이라 start_date가 NULL이면 목록 정렬(start_date asc)이
-- 인덱스를 제대로 못 탄다.
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

-- 결과 확인
SELECT (SELECT count(*) FROM seats st JOIN venues v ON v.id = st.venue_id
        WHERE v.name = '[LOAD] 부하테스트 아레나')                          AS seats,
       (SELECT count(*) FROM performances p JOIN venues v ON v.id = p.venue_id
        WHERE v.name = '[LOAD] 부하테스트 아레나')                          AS performances,
       (SELECT count(*) FROM schedules s JOIN performances p ON p.id = s.performance_id
        JOIN venues v ON v.id = p.venue_id WHERE v.name = '[LOAD] 부하테스트 아레나') AS schedules;
