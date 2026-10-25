-- 부하 대상 회차에만 대기열을 켠다 (Task 033)
--
-- schedules.queue_enabled(V8)는 "이 회차에 줄을 세우는가"이고 쓰기 API가 없어서 사람이 SQL로
-- 바꾸는 값이다. 01~02 시드가 만드는 회차는 3,000개인데, **전부 켜면 측정이 성립하지 않는다.**
--
-- ── 왜 전부 켜면 안 되는가 ────────────────────────────────────────────────────
-- 1) admit-batch-size는 전역이 아니라 **회차당** 값이다. QueueAdmissionScheduler.run()이
--    enabledScheduleIds()를 전부 순회하면서 회차마다 admitBatch()를 돌리므로,
--    초당 입장 인원 = admit-batch-size x 켠 회차 수 / admit-interval
--    이다. 3,000회차를 켜면 batch=50에서 초당 150,000명이 입장하고, 대기열은 아무것도 막지
--    않는다. "대기열을 켰는데 효과가 없다"로 보이고 원인이 설정값에 안 적혀 있다.
-- 2) 틱마다 회차당 Redis 왕복이 3회다(만료 수거 / 선두 조회 / 대기 인원). 3,000회차면
--    1초 안에 왕복 9,000회를 해야 하고, 못 끝내면 fixedDelay가 밀려 입장 주기가 늘어난다.
--    즉 부하 생성기가 아니라 **입장 배치 자체가 측정 대상을 흔든다.**
--
-- 그래서 기본값이 **1회차**다. 그러면 초당 입장 인원 = admit-batch-size 로 1:1 대응이 되고,
-- 배치 크기를 스윕하는 측정이 그대로 해석된다.
--
-- ★ 1회차로 두려면 시드를 01c-large-venue.sql로 깔아야 한다 ★
--   01-bulk-catalog(회차 3,000개 x 2,000석)에서 1회차만 켜면 재고 2,000석이 수십 초에
--   소진되고, 그때부터 측정되는 것은 "선점 처리량"이 아니라 "매진 조회 처리량"이다
--   (98-reset-reservations.sql 주석이 적은 실제 사례). 01c는 회차 1개 x 10,000석이라
--   켤 회차가 하나뿐이면서 재고가 5배다 — 대기열 측정에 그대로 맞는다.
--
--   계산: 초당 입장 50명 x 평균 2.5석 = 125석/s. 10,000석이면 80초. PAY_RATIO의 취소분이
--   재고를 돌려주므로 90초 측정이 매진에 닿지 않는다.
--
-- 캐시 재판정 군(CACHE_TYPE 비교)은 01-bulk-catalog의 600만 행이 필요하고 대기열을 끄므로
-- 이 SQL과 무관하다. 축을 바꿀 때 99-teardown으로 비우고 다시 시드한다(01/01b/01c 공존 금지).
--
-- ── k6가 정확히 이 회차들을 때려야 한다 ──────────────────────────────────────
-- 아래 마지막 SELECT가 찍는 id 목록을 k6에 그대로 넘긴다:
--   ... run -e SCHEDULE_IDS=<콤마로 이어붙인 id> /scripts/queue-spike.js
-- 안 넘기면 catalog.js가 ON_SALE 회차 60개를 긁어오는데, 그중 대기열이 켜진 것은 몇 개뿐이라
-- 나머지는 DISABLED를 받아 대기열을 거치지 않고 통과한다 — 군이 섞인다.
--
-- ── 대상을 '[LOAD] 부하테스트 아레나'로 한정하는 이유 ────────────────────────
-- dev 시드(공연 id 1~8)를 같이 켜면 Playwright e2e 3개가 좌석 조회에서 403을 받아 깨진다.
-- 증상이 "대기열과 무관한 e2e가 갑자기 실패"라서 원인을 찾기까지 오래 걸린다.
-- 01 / 01b / 01c 세 카탈로그가 전부 같은 공연장 이름을 쓰므로 어느 시드에서도 그대로 돈다.
--
-- ── 애플리케이션 토글이 꺼져 있으면 아무 일도 없다 ───────────────────────────
-- tikkit.queue.enabled(QUEUE_ENABLED)가 false면 DisabledQueueGateway가 올라가 전부 통과한다.
--
-- **반영까지 최대 10초 걸린다.** QueueFlagRegistry가 volatile 스냅샷 + 조회 시 자기 갱신
-- (TTL 10초)으로 캐시한다. 이 SQL 직후 k6를 띄우면 첫 몇 초의 요청이 대기열을 거치지 않고
-- 통과하고, 측정 구간 앞에 "플래그 off" 샘플이 섞인다.
--
-- 실행 (기본 1회차):
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/04-enable-queue.sql
--
-- 회차 수를 바꾸려면 psql 변수로 넘긴다 (01 시드에서 재고를 늘리고 싶을 때):
--   ... psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v queue_schedules=10 \
--       -f - < load-test/sql/04-enable-queue.sql
--   그러면 초당 입장 인원이 admit-batch-size x 10 이 된다는 것을 잊지 말 것.

-- psql -v로 안 넘기면 10을 쓴다. :'name' 형식이 정의되지 않은 변수에서 깨지므로
-- current_setting의 missing_ok로 받는다.
\if :{?queue_schedules}
\else
    \set queue_schedules 1
\endif

-- 먼저 전부 끈다. B군(off) ↔ C군(on)을 왕복할 때 지난 실행의 플래그가 남아 있으면
-- "켠 회차 수"가 누적돼서 초당 입장 인원 계산이 조용히 틀어진다.
UPDATE schedules s
SET queue_enabled = false
FROM performances p
         JOIN venues v ON v.id = p.venue_id
WHERE s.performance_id = p.id
  AND v.name = '[LOAD] 부하테스트 아레나'
  AND s.queue_enabled;

-- id가 작은 쪽부터 N개를 켠다. 무작위로 고르지 않는 이유: 측정을 다시 돌릴 때 같은 회차가
-- 뽑혀야 좌석 재고 상태(98-reset 이후)와 결과를 비교할 수 있다.
UPDATE schedules
SET queue_enabled = true
WHERE id IN (SELECT s.id
             FROM schedules s
                      JOIN performances p ON p.id = s.performance_id
                      JOIN venues v ON v.id = p.venue_id
             WHERE v.name = '[LOAD] 부하테스트 아레나'
             ORDER BY s.id
             LIMIT :queue_schedules);

-- 확인 + k6에 넘길 id 목록.
--   load_enabled      : 켠 회차 수. 0이면 01~02 시드를 안 돌렸다
--   dev_seed_enabled  : 0이 아니면 WHERE 절이 새서 e2e를 깨뜨릴 상태다
--   schedule_ids      : 그대로 -e SCHEDULE_IDS=... 에 붙인다
--   admits_per_second : 초당 입장 인원. admit-batch-size가 회차당이라는 걸 눈에 보이게 한다
SELECT count(*)                                        AS load_enabled,
       (SELECT count(*)
        FROM schedules s2
                 JOIN performances p2 ON p2.id = s2.performance_id
                 JOIN venues v2 ON v2.id = p2.venue_id
        WHERE v2.name <> '[LOAD] 부하테스트 아레나'
          AND s2.queue_enabled)                        AS dev_seed_enabled,
       string_agg(s.id::text, ',' ORDER BY s.id)       AS schedule_ids,
       count(*) * 50                                   AS admits_per_second_at_batch_50
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id
WHERE s.queue_enabled
  AND v.name = '[LOAD] 부하테스트 아레나';
