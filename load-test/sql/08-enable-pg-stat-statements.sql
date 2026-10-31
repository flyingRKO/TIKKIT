-- pg_stat_statements 확장을 만든다 (Task 036)
--
-- 한 번만 돌리면 된다. 확장은 DB에 영속되므로 군마다 다시 만들 필요가 없고,
-- run-group.sh도 이걸 호출하지 않는다 — 측정 루프에 넣으면 매 실행 DDL이 끼어든다.
--
-- **Flyway에 넣지 않았다.** db/migration에 두면 dev와 test(Testcontainers)도 이 확장을
-- 요구하게 되는데, 그쪽 postgres에는 shared_preload_libraries가 없어서
-- CREATE EXTENSION은 되지만 쿼리 통계가 안 쌓인다(뷰를 읽으면 에러가 난다).
-- "기동 조건을 측정 때문에 바꾸지 않는다"는 쪽을 골랐다. 003 3절이 Redis에 대해 정한 것과
-- 같은 선택이다.
--
-- 전제: docker-compose.prod.yml postgres command에
--       shared_preload_libraries=pg_stat_statements가 있고 컨테이너가 그 설정으로 떴다.
--       설정 없이 CREATE EXTENSION만 하면 뷰 조회가 다음 에러로 죽는다 -
--       "pg_stat_statements must be loaded via shared_preload_libraries".
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/08-enable-pg-stat-statements.sql

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 설정이 실제로 로드됐는지 같이 확인한다. 확장만 만들고 라이브러리가 없으면 여기서
-- 깨지므로, 측정을 시작한 뒤에 "통계가 안 쌓인다"로 헤매지 않는다.
SELECT current_setting('shared_preload_libraries') AS preload,
       current_setting('pg_stat_statements.track')  AS track,
       current_setting('pg_stat_statements.max')    AS max_statements,
       (SELECT count(*) FROM pg_stat_statements)    AS rows_now;
