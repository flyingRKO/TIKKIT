#!/usr/bin/env python3
"""k6 요약 JSON의 워크로드 무결성을 검사한다 (Task 040).

왜 필요한가
-----------
improvements/README.md 교훈 1이 이 레포에서 가장 비싸게 배운 것이다 — **수치가 좋아지는
가장 흔한 이유는 요청이 일을 안 한 것**이다. 010에서 좌석 조회가 전부 403이 되면서
p95가 64 -> 2.95ms로 "개선"됐고, 027에서는 좌석이 소진돼 선점 시도가 0건인 실행이
"좌석맵 조회 처리량"을 선점 처리량으로 잡아 보고했다.

붕괴하면 지연이 **좋아지므로** k6의 지연 임계값은 오히려 통과한다. 그래서 임계값과
별개로 "iteration이 할 일을 했는가"를 따로 봐야 한다.

010이 이걸 `run-group.sh`에 넣었다고 기록했는데 **그 스크립트는 커밋되지 않았다**
(git 히스토리에 없다). 그래서 Task 040에서 다시 만들면서, 이번에는 셸 루프 안이 아니라
요약 JSON을 사후에 읽는 독립 스크립트로 둔다 — 측정을 다시 돌리지 않고도 과거 결과에
적용할 수 있고, 커밋되는 자리에 있다.

쓰는 법
-------
    python load-test/check-run.py load-test/results/t040-b-sb128-s600-1.json
    python load-test/check-run.py load-test/results/t040-*.json

시나리오를 파일명이나 --profile로 알려주면 그 시나리오의 기대값으로 검사한다.
못 알아내면 공통 검사(checks·real_errors·요청 수가 0이 아님)만 한다.
"""

import json
import sys
from pathlib import Path

# 시나리오별 기대값.
#
# reqs_per_iter: iteration당 HTTP 요청 수. setup의 목록/상세 조회가 http_reqs에 같이
#   잡히므로 하한만 본다(setup은 iteration을 1도 만들지 않아 비율을 위로 끌어올린다).
# bytes_per_iter: iteration당 수신 바이트 하한. 좌석맵 응답이 2,000석에서 237.9KB다.
# bytes_per_iter_max: 수신 바이트 **상한**(없으면 None). 교훈 10의 ②를 막는 자리다 —
#   015에서 압축 군을 쟀는데 k6가 `Accept-Encoding`을 기본으로 보내지 않아 **비압축 248KB가
#   하한 검사를 OK로 통과했다.** 그걸 잡은 건 장치가 아니라 눈이었다("작아야 하는 군에서
#   248KB가 통과했다"). 기대값이 방향을 가진 군은 **하한만으로 판정할 수 없다.**
# seatmap_seats: tikkit_seatmap_seats의 최솟값. 좌석이 비어 오면 조립 비용이 사라진다.
PROFILES = {
    # 좌석맵 단독 — Task 040의 주력 시나리오. iteration당 GET /schedules/{id}/seats 1개.
    'seatmap': {
        'reqs_per_iter': 0.9,
        'bytes_per_iter': 200_000,
        'bytes_per_iter_max': None,
        'seatmap_seats_min': 1900,
    },
    # 좌석맵 + 응답 압축 — Task 039의 gzip 재판정 군.
    #
    # **바이트 하한을 낮추는 것이 이 프로필의 전부다.** 압축이 걸리면 k6가 받는
    # data_received가 19:1로 줄어(247,885 -> 13,086B. 009_1 3.6절) 위 'seatmap' 프로필의
    # 200,000B에 전 군이 걸린다. 그러면 "응답이 비어 오고 있다"로 FAIL인데 실제로는
    # 멀쩡한 실행이다.
    #
    # 장치를 끄는 게 아니라 **무결성의 본체를 바이트에서 좌석 수로 옮기는** 것이다.
    # 이 파일 아래 (5)번 주석이 이미 그 근거를 적어 뒀다 — 바이트는 schedule_seats.id
    # 자릿수에도 반응하므로 좌석 수가 더 믿을 만한 신호다. 압축은 그 "덜 믿을 만한 축"을
    # 하나 더 흔드는 변경이라, 압축 군에서는 seatmap_seats가 유일한 붕괴 탐지기다.
    #
    # 10,000B는 13,086B에 약 24% 여유다. 압축률은 좌석 id 자릿수와 등급 분포에 따라
    # 흔들리므로 실측값에 딱 붙이지 않는다.
    # 상한 50,000B: 실측 13,086B의 약 3.8배이고 비압축 247,885B의 1/5이다. 압축률은 좌석 id
    # 자릿수와 등급 분포에 따라 흔들리므로 실측에 딱 붙이지 않되, **비압축이 통과할 수 없는
    # 자리**에 둔다. 이 한 줄이 015에서 눈으로 잡은 것을 장치로 옮긴다.
    'seatmap-gzip': {
        'reqs_per_iter': 0.9,
        'bytes_per_iter': 10_000,
        'bytes_per_iter_max': 50_000,
        'seatmap_seats_min': 1900,
    },
    # 탐색 — 목록 1 + 상세 1 + 등급 1 = 3개.
    'browse': {
        'reqs_per_iter': 2.5,
        'bytes_per_iter': 2_000,
        'bytes_per_iter_max': None,
        'seatmap_seats_min': None,
    },
    # 예매 — 로그인 1 + 좌석맵 1 + 선점 1(실패 시 생략) = 3~4개.
    # 010이 쓴 하한(요청 2.5개 / 수신 100KB)이 이 시나리오 기준이다.
    #
    # seatmap_seats를 안 보는 이유: booking-spike.js는 좌석맵을 받아 쓰기만 하고
    # tikkit_seatmap_seats Trend를 기록하지 않는다(seatmap.js만 기록한다). 대신
    # 수신 바이트 하한이 같은 일을 한다 — 좌석맵이 비어 오면 237KB가 안 나온다.
    'booking': {
        'reqs_per_iter': 2.5,
        'bytes_per_iter': 100_000,
        'bytes_per_iter_max': None,
        'seatmap_seats_min': None,
    },
    # 대기열 — **이 체커의 프로필 모델에 반쯤만 맞는다.**
    #
    # queue-spike.js의 iteration당 요청 수는 입장 비율에 따라 1~4개로 **정당하게** 변한다
    # (대기 중이면 진입 1개로 끝나고, 입장하면 좌석맵 + 선점 + 결제까지 간다). 그래서
    # 010이 쓴 하한 2.5를 걸면 "대기열이 제 역할을 한 실행"이 FAIL이 된다.
    #
    # 여기서 볼 수 있는 건 "iteration이 요청을 하나라도 보냈는가"뿐이다. 붕괴 탐지의
    # 본체는 k6 스크립트 쪽 커스텀 지표다 — `tikkit_queue_waiting_ratio`(폴링 군이
    # WAITING에 머물렀나)와 `tikkit_reject_ratio`(거절 군이 전부 403인가). 그 둘은
    # 요약 JSON의 threshold로 들어가므로 k6 종료코드 99로 나타난다.
    'queue': {
        'reqs_per_iter': 0.9,
        'bytes_per_iter': 0,
        'bytes_per_iter_max': None,
        'seatmap_seats_min': None,
    },
}


def guess_profile(path: Path, metrics: dict | None = None) -> str | None:
    """시나리오를 추정한다. **지표를 먼저 보고 파일명은 보조로 쓴다.**

    전에는 파일명만 봤는데, 015의 압축 군 태그가 `t039-c-local-gzip`·`t039-d-nginxgzip`처럼
    'seatmap'을 담고 있지 않아서 **추정이 실패하고 교훈 1의 본체 검사를 건너뛴 채 "통과"가
    나왔다**(종료코드 0). 하한·상한·좌석 수를 하나도 안 보고 통과하는 게 가장 위험한 모양이다.

    `tikkit_seatmap_seats`는 `seatmap.js`만 기록하므로 파일명과 무관하게 믿을 수 있다.
    압축 여부는 지표로 알 수 없어서 거기만 파일명을 쓴다.
    """
    name = path.name.lower()
    metrics = metrics or {}

    def has(metric_name):
        return isinstance(metrics.get(metric_name), dict)

    family = None
    # 지표 우선. 각 스크립트만 기록하는 커스텀 지표가 파일명보다 믿을 만하다 —
    # 태그 규칙은 Task마다 바뀌지만(t033-a-pool20-r120처럼 시나리오 이름이 없는 것도 있다)
    # 지표는 스크립트가 정한다.
    if has('tikkit_seatmap_seats'):
        family = 'seatmap'          # seatmap.js만 기록한다
    elif has('tikkit_queue_waiting_ratio') or has('tikkit_reject_ratio'):
        family = 'queue'            # queue-poll.js / queue-reject.js
    elif has('tikkit_hold_success'):
        # booking-spike.js와 queue-spike.js가 같이 기록한다. 대기열 쪽은
        # tikkit_queue_pass_denied를 같이 들고 있으므로 그걸로 가른다.
        family = 'queue' if has('tikkit_queue_pass_denied') else 'booking'
    # 지표로 못 가리면 파일명으로 내려간다.
    elif 'seatmap' in name:
        family = 'seatmap'
    elif 'browse' in name:
        family = 'browse'
    elif 'queue' in name:
        family = 'queue'
    elif 'booking' in name or 'spike' in name:
        family = 'booking'

    # 압축 군은 바이트 기대값이 반대 방향이라 별도 프로필이다.
    if family == 'seatmap' and 'gzip' in name:
        return 'seatmap-gzip'
    return family


def metric(metrics: dict, name: str, field: str):
    entry = metrics.get(name)
    if not isinstance(entry, dict):
        return None
    return entry.get(field)


def check(path: Path, profile: str | None) -> tuple[bool, bool]:
    """(통과했나, 본체 검사를 건너뛰었나)를 돌려준다."""
    try:
        summary = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as exc:
        print(f'  [FAIL] 요약 JSON을 읽을 수 없다: {exc}')
        return False, False

    metrics = summary.get('metrics', {})
    if not metrics:
        print('  [FAIL] metrics가 비어 있다 — --summary-export 경로를 확인한다')
        return False, False

    # 지표를 읽은 뒤에 추정한다(파일명만으로는 압축 군을 못 알아본다).
    if profile is None:
        profile = guess_profile(path, metrics)
    print(f'  프로필: {profile or "? (추정 실패)"}')

    ok = True

    def fail(msg):
        nonlocal ok
        ok = False
        print(f'  [FAIL] {msg}')

    def warn(msg):
        print(f'  [WARN] {msg}')

    def info(msg):
        print(f'  [ ok ] {msg}')

    iterations = metric(metrics, 'iterations', 'count') or 0
    http_reqs = metric(metrics, 'http_reqs', 'count') or 0
    data_received = metric(metrics, 'data_received', 'count') or 0

    # (1) 일을 하긴 했나. 0이면 나머지 비율이 전부 0 나누기가 된다.
    if iterations == 0:
        fail('iterations가 0이다 — 이 실행은 아무것도 재지 않았다')
        return False, False
    if http_reqs == 0:
        fail('http_reqs가 0이다')
        return False, False
    info(f'iterations={iterations:,} http_reqs={http_reqs:,} '
         f'data_received={data_received / 1048576:.1f}MiB')

    # (2) checks. breakpoint 프로필에서는 임계값이 checks만 남으므로 여기가 유일한
    #     "스크립트 버그 vs 서버 포화" 분별 장치다.
    checks_rate = metric(metrics, 'checks', 'value')
    if checks_rate is None:
        warn('checks 지표가 없다')
    elif checks_rate < 0.90:
        fail(f'checks {checks_rate * 100:.2f}% < 90% — 응답이 기대와 다르다')
    elif checks_rate < 1.0:
        warn(f'checks {checks_rate * 100:.2f}% (포화 구간이면 정상일 수 있다)')
    else:
        info('checks 100%')

    # (3) real_errors. 409(정상 거절)와 진짜 에러를 가르는 커스텀 Rate다.
    real_errors = metric(metrics, 'tikkit_real_errors', 'value')
    if real_errors is None:
        warn('tikkit_real_errors 지표가 없다 — lib/api.js를 거치지 않는 스크립트인가')
    elif real_errors > 0.01:
        fail(f'tikkit_real_errors {real_errors * 100:.2f}% > 1%')
    else:
        info(f'tikkit_real_errors {real_errors * 100:.2f}%')

    # (4) 여기가 교훈 1의 본체 — iteration이 실제로 요청을 보냈고 바이트를 받았는가.
    expected = PROFILES.get(profile) if profile else None
    if expected is None:
        warn(f'시나리오를 모르겠다 — **요청 수·바이트·좌석 수 검사를 전부 건너뛴다.** '
             f'이 실행은 "통과"가 아니라 "검사 안 됨"이다. '
             f'--profile {"|".join(PROFILES)} 로 알려준다')
        return ok, True

    reqs_per_iter = http_reqs / iterations
    bytes_per_iter = data_received / iterations
    if reqs_per_iter < expected['reqs_per_iter']:
        fail(f'iteration당 요청 {reqs_per_iter:.2f}개 < {expected["reqs_per_iter"]} — '
             f'iteration이 조기 종료하고 있다')
    else:
        info(f'iteration당 요청 {reqs_per_iter:.2f}개')

    # 상한이 있는 프로필은 한 줄로 합쳐 보고한다 — 같은 지표에 ok와 FAIL이 같이 찍히면
    # 스캔할 때 통과로 읽힌다.
    bytes_max = expected.get('bytes_per_iter_max')
    if bytes_per_iter < expected['bytes_per_iter']:
        fail(f'iteration당 수신 {bytes_per_iter:,.0f}B < {expected["bytes_per_iter"]:,}B — '
             f'응답이 비어 오고 있다(403/404가 임계값을 통과하는 전형)')
    elif bytes_max is not None and bytes_per_iter > bytes_max:
        # 하한만 보면 "작아야 하는 군"에서 큰 응답이 조용히 통과한다 (교훈 10 ②).
        fail(f'iteration당 수신 {bytes_per_iter:,.0f}B > 상한 {bytes_max:,}B — '
             f'이 군은 응답이 작아야 하는데 크다. 조건이 안 걸렸다 '
             f'(압축 군이면 k6가 Accept-Encoding을 보냈는지 확인한다)')
    elif bytes_max is not None:
        info(f'iteration당 수신 {bytes_per_iter:,.0f}B '
             f'(구간 {expected["bytes_per_iter"]:,}~{bytes_max:,}B)')
    else:
        info(f'iteration당 수신 {bytes_per_iter:,.0f}B')

    # (5) 좌석맵 시나리오라면 좌석 수가 고정이어야 한다. 좌석이 줄면 조립 비용이 같이
    #     줄어서 "빨라졌다"로 읽힌다. 바이트는 schedule_seats.id 자릿수에도 반응하므로
    #     (01c-large-venue.sql 주석) 좌석 수가 더 믿을 만한 신호다.
    seats_min_expected = expected['seatmap_seats_min']
    if seats_min_expected is not None:
        seats_min = metric(metrics, 'tikkit_seatmap_seats', 'min')
        if seats_min is None:
            warn('tikkit_seatmap_seats 지표가 없다')
        elif seats_min < seats_min_expected:
            fail(f'좌석 수 최솟값 {seats_min:.0f} < {seats_min_expected} — '
                 f'빈 좌석맵을 받은 iteration이 있다')
        else:
            seats_avg = metric(metrics, 'tikkit_seatmap_seats', 'avg')
            info(f'좌석 수 min={seats_min:.0f} avg={seats_avg:.0f}')

    return ok, False


def main(argv: list[str]) -> int:
    # 윈도우 콘솔은 기본이 cp949다. 한국어 메시지에 들어간 em dash 하나에
    # UnicodeEncodeError로 죽는다 — 그러면 "검사 실패"가 아니라 "검사 자체가 안 돌았다"가
    # 되는데, 둘을 구분할 수 없는 게 더 나쁘다. PYTHONIOENCODING에 맡기지 않는 이유는
    # python -I(격리 모드)가 환경변수를 무시하기 때문이다.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding='utf-8')
        except (AttributeError, OSError):
            pass

    profile = None
    paths = []
    i = 0
    while i < len(argv):
        if argv[i] == '--profile' and i + 1 < len(argv):
            profile = argv[i + 1]
            i += 2
            continue
        paths.append(Path(argv[i]))
        i += 1

    if not paths:
        print(__doc__)
        return 2
    if profile is not None and profile not in PROFILES:
        print(f'알 수 없는 --profile {profile!r} (가능: {", ".join(PROFILES)})')
        return 2

    all_ok = True
    skipped = []
    for path in paths:
        print(path.name)
        run_ok, run_skipped = check(path, profile)
        if not run_ok:
            all_ok = False
        if run_skipped:
            skipped.append(path.name)
        print()

    if not all_ok:
        print('무결성 검사 실패 — 이 실행의 수치는 쓰지 않는다')
        return 1
    if skipped:
        # "통과"로 끝내면 안 된다. 013이 적은 "통과했다는 신호와 일을 했다는 신호는
        # 다르다"의 이 스크립트 판본이다.
        print(f'무결성 검사 **불완전** — {len(skipped)}개 실행이 프로필 미지정으로 '
              f'본체 검사를 건너뛰었다: {", ".join(skipped)}')
        return 2
    print('무결성 검사 통과')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
