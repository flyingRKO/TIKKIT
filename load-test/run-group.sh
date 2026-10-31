#!/usr/bin/env bash
# 측정 군 하나를 N회 반복해 돌린다 (Task 040)
#
# 왜 스크립트인가: Task 040은 군이 6개이고 각 군을 3회 돌린다. 18회를 손으로 치면
# 환경변수 하나가 조용히 어긋나고, 그러면 "같은 조건으로 쟀다"는 전제가 깨진다 —
# 010이 "QUEUE_EXPECTED=1을 끈 군에 넘기면 403이 조용히 통과한다"로 밟은 함정과 같은
# 종류다. 010도 run-group.sh를 썼다고 기록했는데 그 파일은 커밋되지 않았다.
#
# 매 실행마다 네 가지를 같이 남긴다. 하나라도 빠지면 그 실행은 판정에 못 쓴다.
#   1) k6 요약 JSON            -> results/<tag>-<n>.json
#   2) Postgres I/O 리포트     -> results/<tag>-<n>-dbio.txt   (06 리셋 -> 실행 -> 07 읽기)
#   3) 백엔드 Hikari/heap CSV  -> results/<tag>-<n>-metrics.csv
#   4) 워크로드 무결성 판정    -> results/<tag>-<n>-integrity.txt (check-run.py)
#
# 쓰는 법:
#   bash load-test/run-group.sh <tag> <script.js> <반복수> [k6 -e 인자들...]
#
# 예:
#   bash load-test/run-group.sh t040-b-sb128-s600 seatmap.js 3 \
#        -e THINK_MIN=0 -e THINK_MAX=0 -e VUS=300 -e DURATION=45s -e SAMPLE_SCHEDULES=600
#
# 전제:
#   - docker-compose.prod.yml 스택이 떠 있고 .env가 있다
#   - Git Bash면 MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' 가 export돼 있다 (007 6절)
#   - 반복 1회차는 **warmup으로 쓰고 버린다** (shared_buffers를 바꾼 군은 cold에서 출발한다).
#     그래서 반복수 3을 주면 실제로 4번 돈다. 0회차 파일명에 -warmup이 붙는다.
set -euo pipefail

TAG="${1:?tag가 필요하다 (예: t040-b-sb128-s600)}"
SCRIPT="${2:?k6 스크립트 파일명이 필요하다 (예: seatmap.js)}"
REPEAT="${3:?반복 횟수가 필요하다}"
shift 3

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE="docker compose -f docker-compose.prod.yml"
set -a; . ./.env; set +a

PSQL="docker exec -i tikkit-postgres-prod psql -q -U $POSTGRES_USER -d $POSTGRES_DB"

# 무결성 검사의 기대값 프로필. 파일명이 t040-...로 시작하면 check-run.py가 시나리오를
# 추정하지 못하므로 스크립트명에서 직접 넘긴다 — 추정 실패는 WARN으로 조용히 지나가고,
# 그러면 "요청 수·바이트 하한"이라는 이 장치의 본체가 꺼진 채 통과한다.
case "$SCRIPT" in
  seatmap.js)       PROFILE=seatmap ;;
  browse*.js)       PROFILE=browse ;;
  booking-spike.js) PROFILE=booking ;;
  *)                PROFILE="" ;;
esac
RESULTS="load-test/results"
mkdir -p "$RESULTS"

# 군을 시작하기 전에 설정을 한 번 찍는다. "1GB로 바꿨다고 생각했는데 안 바뀐 군"을
# 사후에 구분할 수 있는 유일한 방법이다.
echo "=== 군 $TAG ==="
$PSQL -c "select current_setting('shared_buffers') shared_buffers, current_setting('track_io_timing') io_timing;"
echo "k6 인자: $*"
echo

for i in $(seq 0 "$REPEAT"); do
  if [ "$i" -eq 0 ]; then
    name="${TAG}-warmup"
    echo "--- [$name] 버릴 실행 (cold 출발을 모든 군에 동일하게 맞춘다) ---"
  else
    name="${TAG}-${i}"
    echo "--- [$name] ---"
  fi

  # 예약을 되돌린다. 좌석맵 시나리오는 좌석을 바꾸지 않지만, 같은 스택에서 예매 군을
  # 번갈아 돌리면 좌석이 소진된 상태가 남는다 (98 파일 주석의 그 함정).
  $PSQL -f - < load-test/sql/98-reset-reservations.sql > /dev/null

  # I/O 카운터 리셋. 이게 k6 실행 직전이어야 한다.
  $PSQL -f - < load-test/sql/06-reset-db-stats.sql > /dev/null

  # 백엔드 지표 샘플러를 띄운다. trap이 스크립트 안에 있지만, 여기서도 종료를 보장한다 —
  # 고아로 남으면 다음 실행에 부하가 섞여 "같은 설정인데 처리량 반토막"이 된다(Task 033).
  bash load-test/sample-backend-metrics.sh "$RESULTS/${name}-metrics.csv" 2 &
  sampler_pid=$!

  k6_rc=0
  $COMPOSE --profile load run --rm --no-deps k6 run \
    "$@" --summary-export="/results/${name}.json" "/scripts/${SCRIPT}" || k6_rc=$?

  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  # k6 종료코드 99는 "임계값 위반"이다. 포화 군에서는 정상 신호이므로 멈추지 않는다.
  if [ "$k6_rc" -ne 0 ] && [ "$k6_rc" -ne 99 ]; then
    echo "k6가 종료코드 $k6_rc로 끝났다 — 이 실행은 버린다" | tee "$RESULTS/${name}-integrity.txt"
    continue
  fi

  $PSQL -f - < load-test/sql/07-db-io-report.sql > "$RESULTS/${name}-dbio.txt" 2>&1

  python load-test/check-run.py ${PROFILE:+--profile "$PROFILE"} "$RESULTS/${name}.json" | tee "$RESULTS/${name}-integrity.txt" || true
  echo
done

echo "=== 군 $TAG 끝. 결과: $RESULTS/${TAG}-* ==="
