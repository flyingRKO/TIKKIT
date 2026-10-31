#!/usr/bin/env bash
# 컨테이너별 CPU를 2초 간격으로 샘플링한다 (Task 036)
#
# 왜 필요한가: 040이 포화 구간의 벽을 postgres 400%(4.0코어) vs BE 2대 271%로 특정했는데,
# 그 샘플을 수동으로 떴다(t033 cpu-probe.txt 방식). Task 036의 판정 기준이
# "처리량이 산포 밖으로 오르고 **Postgres CPU가 같이 내려가는가**"라서 매 군에 이 값이 필요하다.
#
# docker stats의 CPU%는 **논리 스레드 기준**이다(improvements/README.md 측정 환경 1번).
# 400%는 논리 스레드 4개이고, 포화 합계가 물리 8코어 아래일 때만 "물리 4코어"로 읽어도 된다.
#
# 쓰는 법: bash load-test/sample-container-cpu.sh <출력파일> &  ... kill %1
set -uo pipefail
OUT="${1:?출력 파일 경로가 필요하다}"
echo "ts,container,cpu_pct,mem_used" > "$OUT"
while true; do
  ts=$(date +%s)
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
    | grep -E 'postgres-prod|backend[12]|nginx-prod|redis-prod' \
    | sed "s/^/$ts,/" >> "$OUT"
  sleep 2
done
