-- 회차별 대기열 on/off 플래그 (Task 031)
--
-- 왜 별도 테이블이 아니라 컬럼인가: 값이 회차당 boolean 하나인 1:1 속성이다. 입장 속도·입장권 TTL은
-- 전역 프로퍼티(tikkit.queue.*)로 두기로 했으므로 회차별로 보관할 설정이 이것뿐이다.
-- docs/ERD.md 4절 마이그레이션 이력 표가 이 번호에 이 컬럼을 예약해 뒀다.
-- (V7은 Task 028의 인덱스 추가용으로 예약했다가 EXPLAIN ANALYZE에서 근거가 안 나와 만들지 않았다 —
--  번호는 비워 둔 채다. V6_1이 V7 대신 서브번호를 쓴 것도 이 예약 때문이었다.)
--
-- 기본값 false인 이유는 tikkit.cache.type=none, tikkit.auth.session-store=memory와 같다:
-- 새 인프라를 끈 상태가 기본이어야 Redis 없이도 기동·테스트가 돌아간다
-- (docs/improvements/003-redis-distributed-lock.md 3절). 플래그가 false인 회차는 인터셉터가
-- Redis를 아예 건드리지 않고 통과한다.
--
-- NOT NULL + DEFAULT false로 단다. nullable로 두면 "NULL은 안 켜진 것"이라는 해석을 읽는 쪽마다
-- 반복해야 하고, 한 곳이 빠지면 조용히 통과한다. Postgres 11+는 상수 DEFAULT를 메타데이터에만
-- 기록하므로 기존 행을 재작성하지 않고, 테이블이 커도 잠금 비용이 없다.
--
-- 인덱스를 걸지 않는다. 이 컬럼을 읽는 쿼리는 QueueFlagRegistry가 10초마다 한 번 돌리는
-- "SELECT id FROM schedules WHERE queue_enabled = true" 하나뿐이고, schedules는 회차 테이블이라
-- 전체 스캔이 ms 단위다(Task 028 측정에서 공연 1,008행 목록 조회가 Seq Scan으로도 0.55ms였다).
-- 측정 근거 없이 인덱스를 추가하지 않는다는 Task 028의 결정과 같은 선이다.
ALTER TABLE schedules
    ADD COLUMN queue_enabled boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN schedules.queue_enabled IS '대기열 사용 여부. true면 좌석 조회·예매 선점 API가 Redis 대기열 입장권을 요구한다 (Task 031)';
