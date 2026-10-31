-- 쿼리별 실행 시간을 읽는다 (Task 036)
--
-- 06-reset-db-stats.sql로 리셋한 뒤 k6를 돌리고 이걸 읽는다. 07의 짝이고 보는 층이 다르다 -
-- 07은 "DB 전체가 디스크에서 몇 페이지를 읽었나"이고 09는 "그 시간을 어느 쿼리가 먹었나"다.
--
-- 왜 필요한가: 040이 포화 구간 CPU를 컨테이너 단위로 쟀더니 postgres 400%(4코어)
-- vs BE 2대 271%였다. 그 4코어가 I/O가 아니라 튜플 구성과 전송이라는 것까지는 알았지만
-- (shared_buffers로 읽기를 0으로 만들어도 처리량이 안 변했다), **어느 쿼리의 어느 단계인지는
-- 프로세스 단위 측정으로 알 수 없다.** 012 8절이 그걸 "Task 036의 입구"로 남겼다.
--
-- total_exec_time을 보는 이유: calls가 많은 쿼리와 한 번이 비싼 쿼리를 같은 축에서 비교해야
-- 한다. mean만 보면 좌석맵 1회(비싸고 드문 것)와 세션 조회(싸고 잦은 것)의 CPU 기여를
-- 못 비교한다. 교훈 7이 "무엇이 줄어드는지와 무엇이 빨라지는지를 따로 확인한다"고 적은 축이다.
--
-- 실행:
--   docker compose -f docker-compose.prod.yml exec -T postgres \
--     psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/09-pg-stat-report.sql

\pset pager off

\echo '=== [1/3] 총 실행 시간 상위 15개 ==='
-- rows/call을 같이 낸다. 좌석맵이 2,000행이고 등급 조회가 3행이라, 행 수를 모르면
-- "느린 쿼리"와 "많이 꺼내는 쿼리"가 구분되지 않는다.
SELECT round(total_exec_time::numeric, 1)                      AS total_ms,
       -- round(double, int)는 Postgres에 없다. numeric만 2인자 round를 받는다 -
       -- 처음에 캐스팅을 빼서 이 쿼리만 조용히 죽었다(Task 036 측정 1회차).
       round((100 * total_exec_time / nullif(sum(total_exec_time) OVER (), 0))::numeric, 1) AS pct,
       calls,
       round(mean_exec_time::numeric, 3)                       AS mean_ms,
       round((rows::numeric / nullif(calls, 0)), 1)            AS rows_per_call,
       shared_blks_hit                                         AS blks_hit,
       shared_blks_read                                        AS blks_read,
       left(regexp_replace(query, '\s+', ' ', 'g'), 110)       AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT 15;

\echo ''
\echo '=== [2/3] 좌석맵 경로만 (schedule_seats를 읽는 SELECT) ==='
-- 군 사이 비교의 주 지표다. 캐시를 켜면 여기 calls가 줄어야 하고, 안 줄면 캐시가
-- 프록시를 안 타고 있다는 뜻이다(008 3.1절의 자기호출 함정. 적중률만 0%로 조용히 틀린다).
SELECT round(total_exec_time::numeric, 1)                      AS total_ms,
       calls,
       round(mean_exec_time::numeric, 3)                       AS mean_ms,
       round((rows::numeric / nullif(calls, 0)), 1)            AS rows_per_call,
       left(regexp_replace(query, '\s+', ' ', 'g'), 160)       AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query ILIKE '%schedule_seats%'
  AND query ILIKE 'select%'
ORDER BY total_exec_time DESC
LIMIT 10;

\echo ''
\echo '=== [3/3] 합계 (군 식별용) ==='
SELECT count(*)                                                AS statements,
       sum(calls)                                              AS total_calls,
       round(sum(total_exec_time)::numeric, 1)                 AS total_ms,
       current_setting('pg_stat_statements.track')             AS track
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database());
