-- ticket_grades.version 컬럼 제거 (docs/ERD.md 1절, 4절 참조)
-- V3에서 낙관적 락 비교 실험용으로 추가했고, 조건부 UPDATE를 채택해 더 이상 쓰지 않는다
-- (비교 수치와 채택 근거: docs/improvements/002-db-lock-comparison.md).
-- 적용된 마이그레이션은 수정할 수 없으므로 V3를 되돌리는 스크립트를 따로 둔다. 버전을 V4가 아니라
-- V3_1로 단 이유는 ERD.md 마이그레이션 이력에 V4~V8이 이미 미래 계획으로 잡혀 있어서다.

ALTER TABLE ticket_grades DROP COLUMN version;
