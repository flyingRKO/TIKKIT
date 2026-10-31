#!/usr/bin/env bash
# 측정 군 하나를 N회 반복해 돌린다 (Task 040)
#
# 왜 스크립트인가: Task 040은 군이 6개이고 각 군을 3회 돌린다. 18회를 손으로 치면
# 환경변수 하나가 조용히 어긋나고, 그러면 "같은 조건으로 쟀다"는 전제가 깨진다 —
# 010이 "QUEUE_EXPECTED=1을 끈 군에 넘기면 403이 조용히 통과한다"로 밟은 함정과 같은
# 종류다. 010도 run-group.sh를 썼다고 기록했는데 그 파일은 커밋되지 않았다.
#
# 매 실행마다 다섯 가지를 같이 남긴다. 하나라도 빠지면 그 실행은 판정에 못 쓴다.
#   1) k6 요약 JSON            -> results/<tag>-<n>.json
#   2) Postgres I/O 리포트     -> results/<tag>-<n>-dbio.txt   (06 리셋 -> 실행 -> 07 읽기)
#   3) 백엔드 Hikari/heap CSV  -> results/<tag>-<n>-metrics.csv
#   4) 워크로드 무결성 판정    -> results/<tag>-<n>-integrity.txt (check-run.py)
#   5) 쿼리별 실행 시간        -> results/<tag>-<n>-pgss.txt    (Task 036. 09 읽기)
#      확장이 없으면 에러 텍스트가 남는다 — sql/08을 한 번 돌려야 한다는 신호다.
#   6) nginx 로그 집계         -> results/<tag>-<n>-nginx.txt   (Task 039)
#      no live upstreams / 임시파일 버퍼링 / 502·504 건수. 036에서 이게 없어서 각 군
#      3회차가 전부 502인 것을 **사후에 손으로** 찾았다 (014 4.7절).
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

# 같은 스크립트로 기대값이 다른 군을 돌릴 때 덮는다 (Task 039의 gzip 군이 그렇다 —
# seatmap.js를 그대로 쓰는데 압축 때문에 수신 바이트 하한이 다르다).
#   PROFILE_OVERRIDE=seatmap-gzip bash load-test/run-group.sh t039-c-gzip seatmap.js 3 ...
PROFILE="${PROFILE_OVERRIDE:-$PROFILE}"
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

  # nginx 로그를 어디서부터 읽을지 잡는다 (Task 039). 실행 전 시각을 쓰므로 이전 회차
  # 로그가 섞이지 않는다. UTC로 뽑는 이유: docker는 --since를 UTC 기준으로 읽는다.
  log_since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

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

  # 쿼리별 실행 시간 (Task 036). 확장이 없으면 09가 에러를 뱉는데, 그 에러를 파일에
  # 그대로 남기고 넘어간다 — 조용히 빈 파일을 만들면 "안 쌓였다"와 "안 돌았다"를
  # 구분할 수 없다(교훈 1의 작은 판본. 통과했다는 신호와 일을 했다는 신호는 다르다).
  $PSQL -f - < load-test/sql/09-pg-stat-report.sql > "$RESULTS/${name}-pgss.txt" 2>&1 || true

  # nginx 로그 집계 (Task 039). 한 번만 읽고 awk로 한 번에 센다 — 포화 45초면 로그가
  # 100만 줄 단위라 패턴마다 다시 읽으면 집계가 측정보다 오래 걸린다.
  #
  # **총 줄 수를 같이 남기는 게 핵심이다.** 009 6.2절이 `error_log /dev/stderr`를 빈 파일로
  # 읽어 "0건"을 얻고 그걸 증거로 쓸 뻔했다 — 빈 파일에서 나온 0건은 증거가 아니라
  # 측정 실패다. 총 줄 수가 0이면 아래 경고가 그걸 구분해 준다.
  $COMPOSE logs --since "$log_since" nginx 2>/dev/null | awk '
    { total++ }
    /no live upstreams/            { nolive++ }
    /buffered to a temporary file/ { tmpfile++ }
    /" 502 /                       { s502++ }
    /" 504 /                       { s504++ }
    END {
      printf "로그 줄 수            %d
", total+0
      printf "no live upstreams     %d
", nolive+0
      printf "임시파일 버퍼링       %d
", tmpfile+0
      printf "502                   %d
", s502+0
      printf "504                   %d
", s504+0
      if (total+0 == 0)
        print "경고: 로그가 0줄이다. 0건은 \"안 터졌다\"가 아니라 \"안 읽혔다\"일 수 있다"
      else if (nolive+0 > 0 || tmpfile+0 > 0)
        print "경고: 이 실행은 nginx 쪽에서 오염됐다 — 014 4.7절과 같은 자리다"
    }' > "$RESULTS/${name}-nginx.txt" 2>&1 || true
  cat "$RESULTS/${name}-nginx.txt"

  python load-test/check-run.py ${PROFILE:+--profile "$PROFILE"} "$RESULTS/${name}.json" | tee "$RESULTS/${name}-integrity.txt" || true
  echo
done

echo "=== 군 $TAG 끝. 결과: $RESULTS/${TAG}-* ==="
