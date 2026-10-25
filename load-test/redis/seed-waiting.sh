#!/usr/bin/env bash
# 대기 ZSET에 합성 멤버를 심는다 (Task 033)
#
# ★ 왜 필요한가 ★
# 계정이 2,000개이고 maxVUs가 그 이하라, k6가 만들 수 있는 서로 다른 대기열 멤버는 수백 명이다.
# 그런데 재야 하는 것은 "대기 10만 명일 때"의 거동이다 — 순번 계산 비용(ZRANK), 메모리,
# allkeys-lru 축출, 그리고 폴링 10초 구간(rank >= 1,000).
#
# 해법은 **요청률과 큐 깊이를 분리하는 것**이다. 깊이는 여기서 심고, 요청률은 k6가 만든다.
# Task 032가 폴링 5초 구간을 밟으려고 앞에 150명을 심은 것과 같은 방법이고 규모만 다르다.
#
# ★ 멤버 id를 1,000만부터 쓰는 이유 ★
# 실제 회원 id와 겹치면 그 회원의 순번이 합성 멤버로 덮인다(ZADD는 같은 멤버의 score를 갱신한다).
# 심기 전에 확인한다:  SELECT max(id) FROM members;
#
# ★ score를 과거로 주는 이유 ★
# 합성 멤버가 실제 VU보다 **앞에** 서야 VU가 WAITING 구간에 머문다. 뒤에 심으면 VU가 즉시
# 입장해 ADMITTED 분기(ZSCORE 한 번)로 빠지고, 그러면 ZRANK 비용을 재지 못한다.
#
# ★ 청크로 쪼개는 이유 ★
# Redis는 단일 스레드라 ZADD 10만 개를 한 EVAL로 돌리면 그 시간 동안 다른 명령이 전부 멈춘다.
# 측정 전이라 무해하지만, 같은 Redis에 세션이 있어서 블로킹이 길면 기동 중인 BE의 헬스체크가
# 흔들린다. 1만 개씩 쪼갠다.
#
# 사용법:
#   load-test/redis/seed-waiting.sh <scheduleId> <count> [memberIdOffset]
#   load-test/redis/seed-waiting.sh 1234 100000
set -euo pipefail

CONTAINER="${REDIS_CONTAINER:-tikkit-redis-prod}"
SID="${1:?scheduleId가 필요하다}"
COUNT="${2:?심을 인원이 필요하다}"
OFFSET="${3:-10000000}"
CHUNK="${CHUNK:-10000}"

KEY="tikkit:queue:{${SID}}:waiting"

# 10분 전부터 1ms 간격으로 증가. FIFO라 먼저 심힌 쪽이 앞에 선다.
BASE=$(docker exec "$CONTAINER" redis-cli TIME | head -1)
BASE_MS=$(( BASE * 1000 - 600000 ))

LUA='local k=KEYS[1] local b=tonumber(ARGV[1]) local n=tonumber(ARGV[2]) local o=tonumber(ARGV[3])
for i=1,n do redis.call("ZADD",k,b+i,tostring(o+i)) end
return redis.call("ZCARD",k)'

done_count=0
while [ "$done_count" -lt "$COUNT" ]; do
    remain=$(( COUNT - done_count ))
    step=$(( remain < CHUNK ? remain : CHUNK ))
    card=$(docker exec "$CONTAINER" redis-cli EVAL "$LUA" 1 "$KEY" \
        "$(( BASE_MS + done_count ))" "$step" "$(( OFFSET + done_count ))")
    done_count=$(( done_count + step ))
    echo "심은 누적 ${done_count} / ${COUNT}  (ZCARD=${card})"
done

echo "--- 결과 ---"
docker exec "$CONTAINER" redis-cli ZCARD "$KEY"
# skiplist여야 한다. listpack이면 Redis가 작은 ZSET을 압축 표현으로 들고 있는 것이고,
# 그 구간의 메모리·ZRANK 비용은 큰 큐에 외삽할 수 없다.
docker exec "$CONTAINER" redis-cli OBJECT ENCODING "$KEY"
docker exec "$CONTAINER" redis-cli MEMORY USAGE "$KEY"
