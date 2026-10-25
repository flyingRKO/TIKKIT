-- 부하 테스트용 계정 2,000개 (Task 027, Task 033에서 500 → 2,000)
--
-- 왜 계정이 많이 필요한가: uk_reservations_pending_member_grade(V3_2)가 (member_id,
-- ticket_grade_id)에 PENDING 1건만 허용한다. 한 계정으로 VU를 여러 개 돌리면 두 번째부터
-- 409 DUPLICATE_PENDING_RESERVATION이 쏟아지고, 그걸 서버 문제로 오진하게 된다.
-- k6는 __VU 번호로 이 풀에서 계정을 하나씩 집는다 (load-test/k6/lib/accounts.js).
--
-- 가입 API로 만들지 않는 이유: 500번의 BCrypt 해싱이 전부 BE에서 돌아 setup 단계가
-- 몇 분씩 걸리고, 그 시간이 측정 구간 바로 앞에 CPU 스파이크로 남는다.
-- 비밀번호 평문이 같으면 해시를 재사용해도 로그인된다 — dev 시드의 "Password1!" 해시를 그대로 쓴다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/03-loadtest-members.sql

-- Task 033에서 500 → 2,000으로 올렸다. 006 9절이 남긴 항목이다 — "VU를 더 띄우려면 이 500을
-- 같이 올려야 한다". 포화점을 찾으려면 서버가 무너질 때까지 도착률을 밀어야 하는데, 예매
-- 시나리오의 maxVUs가 계정 수에 묶여 있어서 500으로는 그 상한에 먼저 닿는다. 그러면 재는 것이
-- "서버의 포화점"이 아니라 "부하 생성기의 상한"이 된다.
-- 2,000명이어도 BCrypt 비용은 0이다 — 해시를 그대로 복사하고 로그인 때만 검증한다.

INSERT INTO members (email, password, name, phone, role)
SELECT 'loadtest' || lpad(g.n::text, 4, '0') || '@tikkit.com',
       -- "Password1!" (db/seed/V1_1__seed_dev_data.sql과 같은 해시)
       '$2b$10$H9zvS6Kdgp14diNSRTb3..NCsZdlAqrEJlkhKMNsxqJ5LGiBDwA1q',
       '부하테스트 ' || lpad(g.n::text, 4, '0'),
       '010-0000-' || lpad(g.n::text, 4, '0'),
       'USER'
FROM generate_series(1, 2000) AS g(n)
-- uk_members_email은 lower(email)에 걸린 부분 아닌 "표현식 유니크 인덱스"라서
-- 제약 이름으로 ON CONFLICT를 걸 수 없다. 대상 없는 DO NOTHING이 모든 유니크 인덱스를 커버한다.
ON CONFLICT DO NOTHING;

SELECT count(*) AS loadtest_members
FROM members
WHERE email LIKE 'loadtest%@tikkit.com';
