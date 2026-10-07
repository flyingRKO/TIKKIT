-- dev 프로필 전용 시드 (application-dev.yml의 flyway.locations에서만 로드됨).
--
-- V5는 운영에서도 도는 백필이라 실제 공연장 배치를 날조할 수 없다. 그래서 구역명을 'VIP-좌'처럼
-- 등급 코드 기반으로 남겨두는데, 좌석 배치도(Task 023)에 그대로 노출하기엔 딱딱하다.
-- 여기는 dev 전용이라 그 제약이 없으므로 사람이 읽는 형태로 바꾼다.
--
-- 원래 이 파일에는 HELD/SOLD 상태 샘플도 넣기로 했었다. dev 시드에 예약이 0건이면 V5의 예약 배정이
-- 아무것도 하지 않아 모든 좌석이 AVAILABLE이 되고, FE가 선점·판매 완료 렌더링을 확인할 수 없기
-- 때문이다. 실제로는 기존 dev DB에 예매 테스트로 쌓인 예약이 있어 V5가 좌석을 배정했으므로 생략했다.
-- 빈 DB에서 처음 띄우면 상태 샘플이 없으니, 그때는 화면에서 직접 예매해 HELD를 만들면 된다.
--
-- 적용 순서가 중요하다: section은 V5의 [2/3]이 split_part(section, '-', 1)로 등급을 되찾는 기준이라
-- V5보다 먼저 바꾸면 schedule_seats가 하나도 생기지 않는다. 파일명이 5.1이라 Flyway가 항상 V5 뒤에
-- 돌려주므로 안전하다. 반대로 이 UPDATE 뒤에는 구분자 '-'가 사라져서 V5를 수동으로 재실행해도
-- [2/3]이 매칭되지 않는다 — 좌석을 다시 만들려면 DB를 비우고 V1부터 재생성해야 한다.

-- 'VIP-중' → 'VIP석 중앙'. section이 varchar(10)이라 가장 긴 'VIP석 좌측'(7자)까지만 쓸 수 있다.
-- WHERE 절이 멱등성을 만든다 — 두 번째 실행에는 '-'가 남아있지 않아 0행이 된다.
UPDATE seats
SET section    = split_part(section, '-', 1) || '석 ' ||
                 CASE split_part(section, '-', 2)
                     WHEN '좌' THEN '좌측'
                     WHEN '중' THEN '중앙'
                     WHEN '우' THEN '우측'
                 END,
    updated_at = now()
WHERE section LIKE '%-%';
