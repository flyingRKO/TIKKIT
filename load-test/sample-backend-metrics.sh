#!/usr/bin/env bash
# 측정 중 BE 지표를 샘플링한다 (Task 033)
#
# ★ 왜 Prometheus가 아닌가 ★
# 로드맵이 "DB 커넥션"을 명시 측정 항목으로 두고 있는데, Prometheus+Grafana를 띄우면 로컬
# 호스트에 컨테이너가 둘 더 올라간다(사용자 제약: 가벼운 검증부터). 재야 하는 것이 네댓 개
# 숫자뿐이라 /actuator/prometheus를 직접 긁는 쪽이 싸다.
#
# **대신 포기하는 것**: Prometheus의 10초 scrape보다 촘촘히 볼 수 있지만, k6의 p95와 같은
# 타임라인에 겹쳐 보는 것은 Grafana 없이는 눈으로 맞춰야 한다(004_3에서 가장 번거로웠던 부분).
# 포화점 탐색에서는 "계단별 최대값"만 필요해서 그 비용을 지불한다.
#
# nginx를 거치지 않는다 — nginx.conf가 /actuator를 차단한다(Task 026). 컨테이너 안에서
# 자기 자신을 때리므로 그 차단과 무관하다.
#
# 재는 것:
#   hikaricp_connections_active    사용 중 커넥션
#   hikaricp_connections_pending   ★ 커넥션을 기다리는 스레드. 0이 아니면 포화의 1차 신호
#   hikaricp_connections_timeout   0이 아니면 500 응답이 났다
#   jvm_memory_used_bytes(heap)
#   tikkit_queue_waiting_size      대기 인원
#   tikkit_queue_admitted_total    누적 입장 인원
#
# 사용법:
#   load-test/sample-backend-metrics.sh <출력파일> [간격초]
#   (백그라운드로 띄우고 측정이 끝나면 kill 한다)
set -uo pipefail

OUT="${1:?출력 파일 경로가 필요하다}"
INTERVAL="${2:-2}"
COMPOSE="${COMPOSE:-docker-compose.prod.yml}"

echo "ts,service,hikari_active,hikari_pending,hikari_timeout_total,hikari_acquire_count,hikari_acquire_max,heap_used_bytes,queue_waiting,queue_admitted_total" > "$OUT"

# ★ docker compose exec가 아니라 docker exec다 ★
#
# `docker compose exec`는 매번 compose 파일을 파싱하고 프로젝트를 해석한다. 측정 중에
# 2초마다 서비스 둘이면 그 CPU가 **부하 생성기와 서버가 쓸 CPU를 뺏는다.** 측정 장치가
# 측정을 흔드는 경로다(006이 k6 라벨 카디널리티에서 겪은 것과 같은 종류).
# 컨테이너 이름을 고정하고 docker exec을 직접 쓰면 그 비용이 사라진다.
#
# 이름은 compose 프로젝트명(tikkit-prod) + 서비스명 + 인덱스다. container_name을 주지 않은
# 서비스라서 이 규칙이 적용된다(docker-compose.prod.yml의 backend1 주석 참조).
container_of() {
    echo "tikkit-prod-$1-1"
}

sample_one() {
    local svc="$1"
    local raw
    raw=$(docker exec "$(container_of "$svc")" \
            wget -q -O - http://localhost:8080/actuator/prometheus 2>/dev/null) || return 0

    # grep -m1로 첫 줄만. hikaricp 지표는 pool 라벨이 하나뿐이다.
    local active pending timeout acqcount acqmax heap waiting admitted
    active=$(echo "$raw"  | grep -m1 '^hikaricp_connections_active'  | awk '{print $2}')
    pending=$(echo "$raw" | grep -m1 '^hikaricp_connections_pending' | awk '{print $2}')
    timeout=$(echo "$raw" | grep -m1 '^hikaricp_connections_timeout' | awk '{print $2}')
    # 커넥션을 몇 번 가져갔는가. 거절 경로가 커넥션을 잡는지 보는 지표다(Task 033 4.6절) —
    # 전원이 403으로 거절되는 구간에서 이 값이 안 늘면 @Transactional이 커넥션을 아낀 것이다.
    acqcount=$(echo "$raw" | grep -m1 '^hikaricp_connections_acquire_seconds_count' | awk '{print $2}')
    acqmax=$(echo "$raw"   | grep -m1 '^hikaricp_connections_acquire_seconds_max'   | awk '{print $2}')
    # heap은 area="heap"인 여러 영역의 합이다. 하나만 집으면 eden만 보게 된다.
    #
    # ★ $2가 아니라 $NF다 ★ 라벨 안의 id에 공백이 있다(id="G1 Eden Space"). $2로 집으면
    # "Eden"이 잡혀서 awk가 0으로 더한다 — 에러 없이 heap이 전부 0으로 찍힌다.
    heap=$(echo "$raw"    | grep '^jvm_memory_used_bytes{.*area="heap"' | awk '{s+=$NF} END {print s}')
    waiting=$(echo "$raw" | grep -m1 '^tikkit_queue_waiting_size'     | awk '{print $2}')
    admitted=$(echo "$raw"| grep -m1 '^tikkit_queue_admitted_total'   | awk '{print $2}')

    echo "$(date +%s),${svc},${active:-},${pending:-},${timeout:-},${acqcount:-},${acqmax:-},${heap:-},${waiting:-},${admitted:-}" >> "$OUT"
}

# ★ 고아가 남지 않게 한다 ★
# 부모 셸이 죽으면 이 루프만 살아남아 측정이 끝난 뒤에도 계속 docker exec을 돌린다.
# 다음 측정에 그 부하가 섞이는데, 증상이 "같은 설정인데 처리량이 반토막"이라서 원인을
# 찾기 어렵다(Task 033 1단계에서 실제로 겪어 A군 3회를 버렸다).
trap 'exit 0' TERM INT HUP

echo "샘플링 시작 (${INTERVAL}초 간격) → ${OUT}" >&2
while true; do
    sample_one backend1
    sample_one backend2
    sleep "$INTERVAL"
done
