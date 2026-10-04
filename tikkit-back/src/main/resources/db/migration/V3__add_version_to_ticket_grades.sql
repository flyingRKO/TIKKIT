-- ticket_grades 낙관적 락용 version 컬럼 (docs/ERD.md 1절 참조)
-- Task 019의 락 전략 비교 실험용이다. 조건부 UPDATE를 채택하면 다시 제거한다.
-- 기존 행이 있으므로 NOT NULL에는 DEFAULT가 필수다. JPA @Version(Long)은 0부터 증가하므로 DEFAULT 0으로 맞춘다.

ALTER TABLE ticket_grades ADD COLUMN version bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN ticket_grades.version IS '낙관적 락 버전 (JPA @Version). Task 019 락 전략 비교 실험용';
