#!/usr/bin/env bash
# 대기열 Redis 상태 초기화 (Task 033)
#
# ★ 측정 회차 사이에 반드시 돌린다 ★
#
# 98-reset-reservations.sql은 Postgres만 비운다. 그런데 입장권 수명(pass-ttl)이 15분이라
# 1회차에서 입장한 계정들의 active 엔트리가 2회차 시작 시점에도 유효하다. 그러면 2회차의
# 진입 요청이 WAITING이 아니라 **ADMITTED를 즉시 받고 대기열을 전혀 타지 않는다.**
#
# 증상이 "2·3회차가 1회차보다 훨씬 빠르다"로만 나타나서 캐시 워밍업으로 오해하기 쉽다.
# 98-reset 주석이 적은 Postgres판 함정("좌석이 소진된 상태로 남아 다른 것을 재게 된다")의
# Redis판이고, 대기열 측정에서 가장 밟기 쉬운 자리다.
#
# 세션·조회 캐시는 건드리지 않는다 — FLUSHDB를 쓰면 로그인된 VU가 전부 로그아웃되고
# (AUTH_SESSION_STORE=redis라 세션이 같은 인스턴스에 있다) 다음 측정이 BCrypt 500~2,000회를
# 측정 구간 앞에 끌고 온다. 그래서 큐 키만 지운다.
#
# 사용법:
#   load-test/redis/reset-queue.sh                 # 모든 대기열 키 (tikkit:queue:*)
#   load-test/redis/reset-queue.sh 1234 1235       # 특정 회차만
set -euo pipefail

CONTAINER="${REDIS_CONTAINER:-tikkit-redis-prod}"

if [ "$#" -gt 0 ]; then
    keys=()
    for sid in "$@"; do
        # 중괄호는 Redis Cluster hash tag 리터럴이다 (QueueKeys.java 참조).
        keys+=("tikkit:queue:{${sid}}:waiting" "tikkit:queue:{${sid}}:active")
    done
    docker exec "$CONTAINER" redis-cli DEL "${keys[@]}"
else
    # KEYS는 O(N) 블로킹이라 운영에서는 금물이지만, 여기는 측정 사이의 로컬 스택이고
    # 키가 회차당 2개뿐이다. SCAN으로 쪼갤 이유가 없다.
    #
    # xargs를 거치는 이유: 키가 0개일 때 redis-cli DEL을 인자 없이 부르면 에러다.
    docker exec "$CONTAINER" sh -c \
        "redis-cli --no-raw KEYS 'tikkit:queue:*' | sed -e 's/^[0-9]*) //' -e 's/\"//g' \
         | tr -d '\r' | grep -v '^$' | xargs -r redis-cli DEL"
fi

echo "--- 남은 대기열 키 ---"
docker exec "$CONTAINER" redis-cli KEYS 'tikkit:queue:*'
