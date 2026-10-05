-- 같은 회원이 같은 등급에 PENDING 선점을 둘 이상 갖지 못하게 DB에서 막는다 (Task 020).
--
-- 애플리케이션의 existsBy... 가드는 check-then-insert라 동시 요청에 뚫린다
-- (20스레드 동시 요청 시 20건 전원 통과. docs/improvements/003-redis-distributed-lock.md 참조).
-- Redis 분산 락으로도 막히지만, 그쪽은 "모든 쓰기 경로가 같은 키로 락을 잡아야 한다"는
-- 전역 규약을 요구하고 아무것도 강제하지 않는다. 제약은 경로와 무관하게 성립한다.
--
-- WHERE 절이 붙은 부분 인덱스는 테이블 CONSTRAINT로 선언할 수 없어서
-- uk_ 접두어를 유지한 CREATE UNIQUE INDEX로 만든다.
-- CANCELLED/EXPIRED는 재고를 반납한 상태이므로 중복 제한 대상이 아니고,
-- CONFIRMED는 여러 건을 가질 수 있어야 하므로 PENDING만 조건에 넣는다.
--
-- 운영 규모 테이블이라면 ACCESS EXCLUSIVE 락을 피하려고
-- CREATE UNIQUE INDEX CONCURRENTLY + `-- flyway:executeInTransaction=false`가 필요하다.
-- 현재 데이터 규모에서는 불필요해서 쓰지 않는다.
CREATE UNIQUE INDEX uk_reservations_pending_member_grade
    ON reservations (member_id, ticket_grade_id)
    WHERE status = 'PENDING';
