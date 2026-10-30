-- Postgres I/O 통계를 읽는다 (Task 040)
--
-- 06-reset-db-stats.sql로 리셋한 뒤 k6를 돌리고 이걸 읽는다. 출력 4개다.
--
-- 왜 이 지표인가: 007이 커버링 인덱스로 `Buffers: shared hit`을 2,130 -> 354(-83%)로
-- 줄이고도 시간이 그대로인 것을 보고 "shared hit은 I/O의 대리지표가 아니다"로 닫았다
-- (4.6절). shared hit은 "버퍼를 집은 횟수"이고 같은 페이지를 49번 집으면 49로 센다.
-- 그래서 Task 040은 **집은 횟수가 아니라 실제로 디스크에서 읽은 페이지 수(_blks_read)와
-- 그 읽기에 쓴 시간(blk_read_time)** 을 본다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/07-db-io-report.sql

\echo '=== [1/4] 설정 (군 식별용) ==='
SELECT current_setting('shared_buffers')  AS shared_buffers,
       current_setting('track_io_timing') AS track_io_timing,
       pg_size_pretty(pg_database_size(current_database())) AS db_size;

\echo ''
\echo '=== [2/4] DB 전체 블록 I/O — 이 군의 핵심 수치 ==='
-- blk_read_time은 track_io_timing=on일 때만 채워진다. 0.0이면 설정이 꺼진 것이고
-- 그 군의 시간 지표는 버려야 한다.
--
-- hit_pct가 100에 가까우면 작업 집합이 shared_buffers 안에 있다는 뜻이다. Task 040의
-- B군(회차 600 / 128MB)에서 이 값이 A군(회차 60 / 128MB)보다 **유의하게 낮아야** 한다 —
-- 안 그러면 "회차당 42페이지" 가정이 틀렸고 작업 집합을 더 키워야 한다.
SELECT blks_read,
       blks_hit,
       round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) AS hit_pct,
       round(blk_read_time::numeric, 1)                             AS blk_read_ms,
       round(blk_write_time::numeric, 1)                            AS blk_write_ms,
       xact_commit,
       xact_rollback
FROM pg_stat_database
WHERE datname = current_database();

\echo ''
\echo '=== [3/4] 테이블별 블록 I/O ==='
-- schedule_seats가 축이고, seats는 대조군이다 — 7,120행(약 154페이지, 1.2MB)이라 어떤
-- shared_buffers에서도 상주한다. 007 4.6절의 cold 측정에서 읽은 196페이지 중 154가
-- 이 seats Seq Scan 몫이었고, 커버링 인덱스가 건드리지 못하는 부분이 그것이다.
--
-- idx_blks_*를 같이 보는 이유: D군(커버링 인덱스 338MB)에서 힙 읽기가 줄어드는 대신
-- 인덱스 읽기가 늘어나는지를 확인한다. 007이 "힙 42페이지를 안 읽는 대신 인덱스
-- 44페이지를 읽는다"고 적은 등가가 포화 구간에서도 성립하는지가 이 Task의 질문이다.
SELECT relname,
       heap_blks_read,
       heap_blks_hit,
       round(100.0 * heap_blks_hit / nullif(heap_blks_hit + heap_blks_read, 0), 2) AS heap_hit_pct,
       idx_blks_read,
       idx_blks_hit,
       pg_size_pretty(pg_relation_size(relid))       AS heap_size,
       pg_size_pretty(pg_indexes_size(relid))        AS idx_size
FROM pg_statio_user_tables
WHERE relname IN ('schedule_seats', 'seats', 'performances', 'schedules', 'ticket_grades',
                  'reservations', 'reservation_seats', 'members')
ORDER BY heap_blks_read DESC;

\echo ''
\echo '=== [4/4] 인덱스별 사용량 — 어떤 인덱스가 실제로 돌았나 ==='
-- D군에서 idx_ss_cover가 실제로 쓰였는지 여기서 확인한다. idx_scan이 0이면 플래너가
-- 안 골랐다는 뜻이고, 그러면 그 군은 "인덱스가 있는데 안 쓰는 군"이지 재판정이 아니다.
--
-- uk_members_email의 idx_scan도 같이 본다 — 007 4.10절이 "로그인이 이 인덱스를 한 번도
-- 타지 않는다"고 적었으니 booking-spike가 로그인을 때려도 0이어야 한다. 0이 아니면
-- 그 주장이 틀렸거나 다른 경로가 생긴 것이다.
SELECT relname,
       indexrelname,
       idx_scan,
       idx_tup_read,
       idx_tup_fetch,
       pg_size_pretty(pg_relation_size(indexrelid)) AS idx_size
FROM pg_stat_user_indexes
WHERE relname IN ('schedule_seats', 'seats', 'performances', 'members')
ORDER BY idx_scan DESC;
