#!/usr/bin/env bash
# 대기열 Redis 지표 스냅샷 (Task 033)
#
# ★ 왜 Grafana가 아니라 스크립트인가 ★
# Prometheus가 긁는 대상은 backend1:8080 / backend2:8080 둘뿐이다
# (monitoring/prometheus/prometheus.yml). Redis 자체 지표를 보려면 redis_exporter를 compose에
# 넣어야 하는데, 재야 하는 것이 네 숫자뿐이라 컨테이너를 추가하는 것보다 샘플링이 가볍다.
#
# 재는 것:
#   used_memory              대기열이 메모리를 얼마나 먹는가 (031이 "10만 명 = 약 10MB"로 추산만 했다)
#   evicted_keys             ★ 0이어야 한다. allkeys-lru는 대기열 키도 축출 대상이고, 축출되면
#                            멤버가 아니라 큐 하나가 통째로 날아가 전원이 꼬리로 재진입한다
#   instantaneous_ops_per_sec 세션·캐시·대기열이 한 Redis를 공유한다. 상태 폴링 1회가
#                            ZSCORE + ZCARD + ZRANK + 세션 읽기로 왕복 4회다
#   keyspace_hits/misses     캐시 군에서 같이 본다
#
# 사용법:
#   load-test/redis/queue-stats.sh              # 1회 스냅샷
#   WATCH=5 load-test/redis/queue-stats.sh      # 5초 간격 반복 (Ctrl+C로 중단)
set -euo pipefail

CONTAINER="${REDIS_CONTAINER:-tikkit-redis-prod}"
WATCH="${WATCH:-0}"

snapshot() {
    echo "=== $(date +%H:%M:%S) ==="
    docker exec "$CONTAINER" redis-cli INFO memory \
        | grep -E '^(used_memory_human|used_memory_peak_human|maxmemory_human|maxmemory_policy):' \
        | tr -d '\r'
    docker exec "$CONTAINER" redis-cli INFO stats \
        | grep -E '^(evicted_keys|expired_keys|instantaneous_ops_per_sec|keyspace_hits|keyspace_misses):' \
        | tr -d '\r'
    echo "--- 대기열 키별 크기 ---"
    docker exec "$CONTAINER" sh -c \
        "for k in \$(redis-cli --no-raw KEYS 'tikkit:queue:*' | sed -e 's/^[0-9]*) //' -e 's/\"//g' | tr -d '\r'); do
             printf '%s  card=%s  bytes=%s  enc=%s\n' \
                 \"\$k\" \"\$(redis-cli ZCARD \$k)\" \"\$(redis-cli MEMORY USAGE \$k)\" \"\$(redis-cli OBJECT ENCODING \$k)\"
         done"
}

if [ "$WATCH" -gt 0 ]; then
    while true; do
        snapshot
        sleep "$WATCH"
    done
else
    snapshot
fi
