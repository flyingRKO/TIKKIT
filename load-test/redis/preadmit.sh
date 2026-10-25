#!/usr/bin/env bash
# 부하 계정 전원을 미리 입장시킨다 (Task 033)
#
# ★ 왜 필요한가 — k6만으로는 "대기열을 켠 상태의 보호 경로 용량"을 잴 수 없다 ★
#
# 서로 다른 대기열 멤버 수는 maxVUs(기본 300)가 상한이다. 멤버십이 memberId 기준이고
# 한 VU가 한 계정을 쓰기 때문이다. 그런데 admit-batch-size=50이면 300명이 **6초**에 전원
# 입장한다 — 줄이 서지 않고, 측정 구간 대부분이 "대기열을 켠 채로 전원이 입장한 상태"다.
#
# 그 상태 자체가 재야 하는 군이다: 대기열을 켠 회차에서 좌석 조회·선점이 입장권 검증
# (ZSCORE 왕복 + ZADD GT 연장)을 얹고도 몇 RPS를 처리하는가. 6초를 기다려 우연히 그 상태가
# 되기를 바라는 대신 **처음부터 그 상태로 시작한다.** 램프 구간에 두 상태가 섞이지 않는다.
#
# 반대 방향(줄이 깊은 상태)은 seed-waiting.sh가 만든다. 두 스크립트가 대기열의 양쪽 끝이다.
#
# ★ score에 먼 미래를 넣는 이유 ★
# active ZSET의 score는 입장권 만료 시각이다. 측정 중에 만료되면 그 시점부터 403이 나와
# 군이 둘로 쪼개진다. pass-ttl이 15분이지만 측정·재측정을 묶어 돌리므로 넉넉히 2시간을 준다.
# 입장 배치의 ZREMRANGEBYSCORE가 과거 score만 수거하므로 이 엔트리는 살아남는다.
#
# 사용법:
#   load-test/redis/preadmit.sh <scheduleId> [count]
#   load-test/redis/preadmit.sh 1234 300
set -euo pipefail

REDIS_CONTAINER="${REDIS_CONTAINER:-tikkit-redis-prod}"
PG_CONTAINER="${PG_CONTAINER:-tikkit-postgres-prod}"
SID="${1:?scheduleId가 필요하다}"
COUNT="${2:-300}"

: "${POSTGRES_USER:?.env의 POSTGRES_USER를 export해야 한다}"
: "${POSTGRES_DB:?.env의 POSTGRES_DB를 export해야 한다}"

KEY="tikkit:queue:{${SID}}:active"

# 2시간 뒤 (epoch millis)
EXPIRES_MS=$(( ($(date +%s) + 7200) * 1000 ))

# k6의 accountForVU()가 (__VU - 1) % ACCOUNT_COUNT로 고르므로, VU 1..N이 쓰는 계정은
# loadtest0001..loadtestN이다. 이메일 순서가 id 순서와 같다는 보장이 없으므로 이메일로 정렬한다.
ids=$(docker exec -e PGPASSWORD="${POSTGRES_PASSWORD:-}" "$PG_CONTAINER" \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT id FROM members WHERE email LIKE 'loadtest%@tikkit.com' ORDER BY email LIMIT ${COUNT};" \
    | tr -d '\r')

count=$(echo "$ids" | grep -c '[0-9]' || true)
if [ "$count" -eq 0 ]; then
    echo "loadtest 계정이 없다. load-test/sql/03-loadtest-members.sql을 먼저 돌린다." >&2
    exit 1
fi

# ZADD 한 번에 멤버를 전부 넘긴다. 300개면 인자 길이에 문제가 없다.
args=""
for id in $ids; do
    args="${args} ${EXPIRES_MS} ${id}"
done

# shellcheck disable=SC2086
docker exec "$REDIS_CONTAINER" redis-cli ZADD "$KEY" $args

echo "--- 결과 ---"
echo "미리 입장시킨 계정: ${count}명 (만료 ${EXPIRES_MS})"
docker exec "$REDIS_CONTAINER" redis-cli ZCARD "$KEY"
