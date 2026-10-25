-- 대기열 플래그를 전부 끈다 (Task 033)
--
-- 04-enable-queue.sql의 짝이다. 군을 왕복할 때마다 쓴다 — B군(대기열 기능은 켜고 회차는 끈다)이
-- 이 상태를 요구하고, A군(기능 자체를 끈다)에서도 플래그가 남아 있으면 혼란스럽다.
--
-- 부하 공연장만 끄지 않고 **전부** 끈다. dev 시드 회차에 플래그가 켜져 있으면 Playwright e2e가
-- 좌석 조회에서 403을 받아 깨지는데, 증상이 "대기열과 무관한 e2e가 갑자기 실패"라서 원인을
-- 찾기까지 오래 걸린다. 끄는 쪽은 넓게 가는 게 안전하다.
--
-- **반영까지 최대 10초.** QueueFlagRegistry 스냅샷 TTL이 10초이고 인스턴스마다 갱신 시점이
-- 다르다. 바로 측정을 시작하면 nginx 라운드로빈이 보호되는 요청과 통과되는 요청을 섞는다.
--
-- Redis에 남은 큐는 지우지 않는다 — load-test/redis/reset-queue.sh가 그걸 한다.
-- 플래그만 끄고 active ZSET을 남겨두면 입장권이 pass-ttl(15분) 동안 유효한데, 플래그가 꺼진
-- 회차는 가드를 아예 타지 않으므로 무해하다. 다만 C군으로 돌아올 때 지난 입장권이 살아 있으면
-- "미리 입장시킨 인원"이 의도한 수와 달라지므로 reset을 같이 돌린다.

UPDATE schedules
SET queue_enabled = false
WHERE queue_enabled;

SELECT count(*) AS still_enabled
FROM schedules
WHERE queue_enabled;
