-- dev 프로필 전용 시드 데이터 (application-dev.yml의 flyway.locations에서만 로드됨).
-- 방금 생성된 빈 스키마에 최초로 삽입되는 데이터라, IDENTITY 컬럼이 1부터 삽입 순서대로 채번된다는 점에 의존한다.

-- 테스트 계정 (비밀번호는 둘 다 "Password1!", BCrypt 해시)
INSERT INTO members (email, password, name, phone, role) VALUES
    ('user@tikkit.com', '$2b$10$H9zvS6Kdgp14diNSRTb3..NCsZdlAqrEJlkhKMNsxqJ5LGiBDwA1q', '김도현', '010-1234-5678', 'USER'),
    ('admin@tikkit.com', '$2b$10$H9zvS6Kdgp14diNSRTb3..NCsZdlAqrEJlkhKMNsxqJ5LGiBDwA1q', '관리자', '010-0000-0000', 'ADMIN');

-- 공연장 4곳 (id 1~4)
INSERT INTO venues (name, address) VALUES
    ('올림픽공원 KSPO돔', '서울특별시 송파구 올림픽로 424'),
    ('예술의전당 오페라극장', '서울특별시 서초구 남부순환로 2406'),
    ('블루스퀘어 마스터카드홀', '서울특별시 용산구 이태원로 294'),
    ('고척스카이돔', '서울특별시 구로구 경인로 430');

-- 공연 8개 (id 1~8), 판매 상태를 3그룹(판매중/오픈예정/판매종료)으로 분산
INSERT INTO performances (title, category, description, poster_url, venue_id, running_minutes, age_rating, status) VALUES
    ('판타지아 오케스트라', 'CLASSIC', '국내 정상급 필하모닉이 선사하는 클래식 명곡 갈라 콘서트', NULL, 2, 110, '전체 관람가', 'UPCOMING'),
    ('록스타 라이브 2026', 'CONCERT', '록 밴드 4팀이 함께하는 합동 콘서트', NULL, 1, 150, '만 12세 이상', 'UPCOMING'),
    ('그날의 약속', 'MUSICAL', '이별과 재회를 그린 감성 뮤지컬', NULL, 3, 140, '만 12세 이상', 'UPCOMING'),
    ('겨울, 그리고 봄', 'THEATER', '두 자매의 성장을 그린 연극', NULL, 2, 100, '만 7세 이상', 'UPCOMING'),
    ('아이돌 페스티벌', 'CONCERT', '인기 아이돌 그룹이 총출동하는 페스티벌', NULL, 1, 160, '전체 관람가', 'UPCOMING'),
    ('브로드웨이 나잇', 'MUSICAL', '브로드웨이 인기 넘버 하이라이트 공연', NULL, 3, 130, '전체 관람가', 'UPCOMING'),
    ('챔피언스 매치', 'SPORTS', '프로 리그 챔피언 결정전', NULL, 4, 120, '전체 관람가', 'UPCOMING'),
    ('고전극장: 리어왕', 'THEATER', '셰익스피어 4대 비극 중 하나', NULL, 2, 170, '만 12세 이상', 'UPCOMING');

-- 회차 (id 1~20). booking_close_at = show_at - 2시간으로 통일해 CHECK(booking_open_at < booking_close_at <= show_at)을 항상 만족시킨다.

-- 판매중 그룹: 공연 1~3 (booking_open_at이 이미 지났고, close는 아직 먼 미래)
INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at) VALUES
    (1, now() + interval '20 days', now() - interval '3 days', now() + interval '20 days' - interval '2 hours'),
    (1, now() + interval '21 days', now() - interval '3 days', now() + interval '21 days' - interval '2 hours'),
    (1, now() + interval '23 days', now() - interval '3 days', now() + interval '23 days' - interval '2 hours'),
    (2, now() + interval '15 days', now() - interval '3 days', now() + interval '15 days' - interval '2 hours'),
    (2, now() + interval '16 days', now() - interval '3 days', now() + interval '16 days' - interval '2 hours'),
    (3, now() + interval '25 days', now() - interval '3 days', now() + interval '25 days' - interval '2 hours'),
    (3, now() + interval '26 days', now() - interval '3 days', now() + interval '26 days' - interval '2 hours'),
    (3, now() + interval '27 days', now() - interval '3 days', now() + interval '27 days' - interval '2 hours');

-- 오픈 예정 그룹: 공연 4~6 (booking_open_at이 아직 오지 않음)
INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at) VALUES
    (4, now() + interval '40 days', now() + interval '5 days', now() + interval '40 days' - interval '2 hours'),
    (4, now() + interval '41 days', now() + interval '5 days', now() + interval '41 days' - interval '2 hours'),
    (5, now() + interval '50 days', now() + interval '7 days', now() + interval '50 days' - interval '2 hours'),
    (5, now() + interval '51 days', now() + interval '7 days', now() + interval '51 days' - interval '2 hours'),
    (5, now() + interval '52 days', now() + interval '7 days', now() + interval '52 days' - interval '2 hours'),
    (6, now() + interval '45 days', now() + interval '10 days', now() + interval '45 days' - interval '2 hours'),
    (6, now() + interval '46 days', now() + interval '10 days', now() + interval '46 days' - interval '2 hours');

-- 판매 종료 그룹: 공연 7~8 (booking_close_at이 이미 지남)
INSERT INTO schedules (performance_id, show_at, booking_open_at, booking_close_at) VALUES
    (7, now() - interval '3 days', now() - interval '30 days', now() - interval '5 days'),
    (7, now() - interval '2 days', now() - interval '30 days', now() - interval '5 days'),
    (8, now() - interval '10 days', now() - interval '40 days', now() - interval '12 days'),
    (8, now() - interval '9 days', now() - interval '40 days', now() - interval '12 days'),
    (8, now() - interval '8 days', now() - interval '40 days', now() - interval '12 days');

-- 회차별 등급(VIP/R/S), 잔여 수량은 예약이 아직 없으므로 총 수량과 동일하게 채운다.
INSERT INTO ticket_grades (schedule_id, grade, price, total_quantity, remaining_quantity)
SELECT s.id, g.grade, g.price, g.total_quantity, g.total_quantity
FROM schedules s
CROSS JOIN (VALUES
    ('VIP', 150000::numeric(12,0), 30),
    ('R', 99000::numeric(12,0), 80),
    ('S', 66000::numeric(12,0), 120)
) AS g(grade, price, total_quantity);

-- 공연의 start_date/end_date/status는 schedules로부터 파생되는 값이므로, 시드 삽입 후 즉시 계산해 채운다.
UPDATE performances p
SET start_date = sub.min_show_date,
    end_date   = sub.max_show_date,
    status     = CASE
                     WHEN sub.open_count > 0 THEN 'ON_SALE'
                     WHEN sub.closed_count = 0 THEN 'UPCOMING'
                     ELSE 'CLOSED'
                 END
FROM (
    SELECT
        performance_id,
        MIN(show_at)::date AS min_show_date,
        MAX(show_at)::date AS max_show_date,
        COUNT(*) FILTER (WHERE booking_open_at <= now() AND booking_close_at > now()) AS open_count,
        COUNT(*) FILTER (WHERE booking_close_at <= now()) AS closed_count
    FROM schedules
    GROUP BY performance_id
) sub
WHERE p.id = sub.performance_id;
