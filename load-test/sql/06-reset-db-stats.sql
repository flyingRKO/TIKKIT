-- Postgres I/O 통계를 0으로 되돌린다 (Task 040)
--
-- 07-db-io-report.sql의 짝이다. 군마다 이 순서로 쓴다:
--   06-reset  ->  k6 실행  ->  07-report
--
-- 왜 필요한가: pg_statio_user_tables와 pg_stat_database의 카운터는 **서버 기동 이후
-- 누적값**이다. 리셋하지 않고 읽으면 직전 군의 읽기와 시드 주입(600만 행 INSERT)까지
-- 섞인 값을 보게 되고, 군 사이의 차이가 누적값 안에 묻힌다.
--
-- Task 040이 보려는 것은 "작업 집합이 shared_buffers를 넘겼을 때 실제 디스크 읽기가
-- 얼마나 늘어나는가"다. 007이 shared hit(버퍼를 집은 횟수)으로 -83%를 보고도 아무것도
-- 빨라지지 않은 이유가 그게 I/O가 아니었기 때문이고, 그래서 이번에는 heap_blks_read와
-- blk_read_time을 직접 센다.
--
-- pg_stat_reset()은 현재 DB의 테이블·인덱스·함수 통계와 pg_stat_database 행을 함께
-- 비운다. n_tup_hot_upd(HOT 비율)도 같이 비는데, 007 재현 7번이 이미 그 전제로 쓰고 있다.
--
-- **shared_buffers 자체는 비우지 않는다.** 그건 컨테이너 재시작이 하는 일이고
-- (docker restart tikkit-postgres-prod), 군마다 shared_buffers를 바꾸려면 어차피
-- 재시작하므로 모든 군이 cold에서 출발한다. warmup 1회를 버려 조건을 맞춘다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/06-reset-db-stats.sql

SELECT pg_stat_reset();

-- 쿼리별 통계도 같이 비운다 (Task 036). pg_stat_reset()은 pg_stat_statements를 건드리지
-- 않는다 — 별개 확장의 공유 메모리라서 리셋 함수가 따로 있다. 안 비우면 09 리포트의
-- total_exec_time이 직전 군까지 더해진 값이 되고, 군 사이 차이가 누적값에 묻힌다.
--
-- 확장이 없어도 이 스크립트가 깨지지 않게 조건부로 돈다. 040의 측정을 그대로 재현할 때는
-- shared_preload_libraries 없이 뜬 컨테이너일 수 있고, 그때 06이 죽으면 측정 루프가 멈춘다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') THEN
        PERFORM pg_stat_statements_reset();
        RAISE NOTICE 'pg_stat_statements_reset() 실행';
    ELSE
        RAISE NOTICE 'pg_stat_statements 확장 없음 - 쿼리 통계는 안 모은다 (sql/08 참고)';
    END IF;
END $$;

-- 리셋이 먹었는지 확인한다. 돌아오는 값이 0이 아니면 k6 실행 전에 이미 누가 읽은 것이다
-- (예: 다른 셸에서 psql로 조회했거나, BE가 기동 중 Flyway validate를 돌렸다).
SELECT current_setting('shared_buffers')   AS shared_buffers,
       current_setting('track_io_timing')  AS track_io_timing,
       blks_read,
       blks_hit
FROM pg_stat_database
WHERE datname = current_database();
