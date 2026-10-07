-- dev 프로필 전용 시드 (application-dev.yml의 flyway.locations에서만 로드됨).
--
-- V1_1은 CROSS JOIN으로 모든 회차에 VIP 30 / R 80 / S 120을 똑같이 넣는다. 그래서 1만 5천석 규모의
-- 돔과 1,700석 소극장이 전부 230석이 되고, V5 백필의 그리드 산정 공식(venue별 등급 최댓값 기준)이
-- 공연장별로 갈라질 준비가 돼 있는데도 입력이 균일해서 결과가 똑같아진다. 좌석 배치도(Task 023)에서
-- 공연장 차이를 검수할 수 없으므로 여기서 실제 규모의 약 1/5로 조정한다.
--
-- V1_1을 직접 고치지 않고 별도 버전으로 분리한 이유: V1_1은 이미 적용된 마이그레이션이라
-- 내용을 바꾸면 Flyway가 체크섬 불일치로 기동을 거부한다(기존 dev DB를 전부 비워야 한다).
-- 4.1은 V4 뒤, V5 앞에 돌기 때문에 백필이 조정된 수량을 그대로 읽는다.
--
-- 운영 DB에는 적용되지 않는다. 실제 공연장 좌석 수를 모르는 상태로 운영 데이터를 덮어쓰면 안 된다.

-- 등급별 수량을 공연장 규모에 맞게 조정한다.
-- 새 총량은 모두 기존 총량(VIP 30 / R 80 / S 120) 이상이라, 이미 팔린 수량만큼을 보존해도
-- ck_ticket_grades_remaining_range(0 <= remaining <= total)를 항상 만족한다.
WITH venue_quantity(venue_name, grade, total_quantity) AS (
    VALUES ('고척스카이돔', 'VIP', 200),
           ('고척스카이돔', 'R', 600),
           ('고척스카이돔', 'S', 1200),
           ('올림픽공원 KSPO돔', 'VIP', 150),
           ('올림픽공원 KSPO돔', 'R', 550),
           ('올림픽공원 KSPO돔', 'S', 1100),
           -- 예술의전당 오페라극장 VIP 48석은 실제 1층 OP석 수와 같게 맞췄다.
           ('예술의전당 오페라극장', 'VIP', 48),
           ('예술의전당 오페라극장', 'R', 150),
           ('예술의전당 오페라극장', 'S', 270),
           ('블루스퀘어 마스터카드홀', 'VIP', 30),
           ('블루스퀘어 마스터카드홀', 'R', 110),
           ('블루스퀘어 마스터카드홀', 'S', 210)
)
UPDATE ticket_grades tg
SET total_quantity     = vq.total_quantity,
    -- 이미 팔린 수량(기존 total - remaining)은 그대로 두고 새 총량에서 뺀다.
    -- remaining = total로 덮어쓰면 선점·결제된 재고가 되살아난다.
    remaining_quantity = vq.total_quantity - (tg.total_quantity - tg.remaining_quantity),
    updated_at         = now()
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id
         JOIN venue_quantity vq ON vq.venue_name = v.name
WHERE tg.schedule_id = s.id
  AND tg.grade = vq.grade;

-- 3층까지 있는 큰 공연장에만 A 등급을 추가한다. ticket_grades의 grade CHECK에는 A가 있지만
-- V1_1이 VIP/R/S만 넣어서 지금까지 한 번도 쓰이지 않았고, 등급별 색상 토큰(--grade-a)과
-- 배치도 렌더링을 Task 023에서 검수할 수 없었다. 1,700석 소극장에 A석 블록이 있는 건 어색하므로
-- 공연장 전체에 넣지 않고, 등급 수가 공연장마다 다른 경우까지 함께 커버한다.
INSERT INTO ticket_grades (schedule_id, grade, price, total_quantity, remaining_quantity)
SELECT s.id, 'A', 44000, vq.total_quantity, vq.total_quantity
FROM schedules s
         JOIN performances p ON p.id = s.performance_id
         JOIN venues v ON v.id = p.venue_id
         JOIN (VALUES ('고척스카이돔', 1200),
                      ('올림픽공원 KSPO돔', 1200)) AS vq(venue_name, total_quantity)
              ON vq.venue_name = v.name
ON CONFLICT ON CONSTRAINT uk_ticket_grades_schedule_grade DO NOTHING;
