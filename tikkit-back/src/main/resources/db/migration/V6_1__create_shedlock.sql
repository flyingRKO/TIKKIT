-- ShedLock 락 테이블 (Task 026)
--
-- BE가 2대가 되면 @Scheduled 배치가 양쪽에서 돈다. 이 테이블의 행 하나를 선점한 인스턴스만
-- 실행하게 만든다.
--
-- 번호를 V7이 아니라 V6_1로 붙인 이유: V7(인덱스 추가, Task 028)과 V8(queue_enabled, Task 031)이
-- docs/ERD.md 마이그레이션 이력 표와 ROADMAP에 이미 예약돼 있다. 번호를 밀면 두 문서를 같이
-- 고쳐야 한다. 서브번호 관행은 V3_1·V3_2와 같다 (Flyway는 6.1로 해석해 V6과 V7 사이에 끼운다).
--
-- 스키마는 ShedLock README의 PostgreSQL DDL 그대로다. 컬럼을 바꾸면 JdbcTemplateLockProvider가
-- 찾지 못하므로 손대지 않는다.
--   name       : 락 이름. @SchedulerLock(name = "...")과 같다. PK라서 한 작업당 한 행이다
--   lock_until : 이 시각까지 락이 유효하다. 지나면 다른 인스턴스가 가져갈 수 있다
--   locked_at  : 락을 잡은 시각
--   locked_by  : 잡은 인스턴스 식별자. 누가 돌렸는지 추적용이다
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'ShedLock 분산 스케줄러 락 (다중 인스턴스에서 @Scheduled 중복 실행 방지)';
COMMENT ON COLUMN shedlock.name IS '락 이름 (@SchedulerLock의 name과 동일, 작업당 1행)';
COMMENT ON COLUMN shedlock.lock_until IS '락 유효 시각. 지나면 다른 인스턴스가 선점 가능';
COMMENT ON COLUMN shedlock.locked_at IS '락을 잡은 시각';
COMMENT ON COLUMN shedlock.locked_by IS '락을 잡은 인스턴스 식별자';
