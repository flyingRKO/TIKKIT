# 012. `shared_buffers` 재판정 — I/O를 0으로 만들었는데 처리량이 안 변했다 (Task 040)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 040 (Phase 11: 성능 잔여 과제) — 근거는 [007](007-query-optimization.md) 9절·10절 |
| 대상 코드 | `docker-compose.prod.yml`(postgres `command`), `.env.example`, `load-test/sql/06~07`(신규), `load-test/check-run.py`(신규), `load-test/run-group.sh`(신규) |
| before 기준선 | [007](007-query-optimization.md) 7.1절 좌석맵 p95 **6.77ms**, 4.6절 cold `read` **196**(그중 `seats` 154) |
| 결론 | **`shared_buffers` 상향을 기각했다.** 600회차로 작업 집합을 키워 디스크 읽기를 0 → 53만 페이지로 만든 뒤 512MB·1GB로 올려 **다시 0으로 되돌렸는데, 처리량이 775 → 768 → 766 req/s로 움직이지 않았다**(9회 산포 736~796 안). **커버링 인덱스 기각도 유지**하며, 이제 "지금 규모에서"라는 조건을 뗀다 — cold에서도, 포화에서도, 작업 집합이 버퍼를 넘겨도 기각이다 |
| 알게 된 것 | 007이 "버퍼 접근 수는 I/O의 대리지표가 아니다"로 닫았는데 한 줄이 더 붙는다 — **이 규모에서는 I/O도 처리량의 대리지표가 아니다.** 좌석맵 포화의 벽은 **Postgres CPU 400%(4.0코어)** 이고 BE 2대 합계(271%)보다 크다 |
| 기각 | **`shared_buffers` 512MB·1GB**(I/O를 0으로 만들어도 처리량 무변화). **커버링 인덱스 `idx_ss_cover`**(요청당 버퍼 접근 1,409 → 189회로 -86.6%인데 디스크 읽기는 9.8 → 12.7페이지로 늘고, 처리량 +3.1%가 산포 안) |
| 작성 시점 | 2026-10-30 |
| 데이터 | `schedule_seats` 6,026,894행 (heap 596MB, 인덱스 397MB), DB 1,014MB — 007과 같은 시드 |

---

## 1. 왜 만들었나

007이 인덱스 후보 7종을 전부 수치로 기각하면서, **정작 가장 유력한 후보를 안 쟀다**고 스스로 적었다.

> **`shared_buffers`가 기본값 128MB다.** DB가 1,039MB인데 손대지 않았다. 4.5절의 결론("인덱스가
> 줄이는 게 I/O가 아니다")은 작업 집합이 이 128MB 안에 들어온다는 전제에 기대고 있다. **이 값을
> 올리는 것이 인덱스보다 효과가 클 수 있는데 측정하지 않았다.**
> — [007](007-query-optimization.md) 9절

그리고 그 전제가 깨지는 구간을 다음 Task로 넘겼다.

> 4.5절의 결론이 "작업 집합이 `shared_buffers` 128MB 안에 들어온다"는 전제에 기대고 있다. 포화
> 구간에서는 그 전제가 깨질 수 있으므로, breakpoint 테스트에서 커버링 인덱스 후보를 **다시 재야
> 한다.** 기각은 "지금 규모에서"라는 조건이 붙은 기각이다.
> — [007](007-query-optimization.md) 10절

포화 하네스는 Task 033이 만들었고, 작업 집합은 하네스의 `SAMPLE_SCHEDULES`만 올리면 커진다.
그래서 **새 인프라 없이** 닫을 수 있는 Task였다.

## 2. 먼저 로드맵 두 줄을 고쳤다 — 측정보다 이게 앞이다

ROADMAP Phase 11이 Task 035의 교훈으로 **"착수할 때 근거 문서의 해당 절을 먼저 읽고, 어긋나면
로드맵을 고치는 것부터 한다"** 를 적어 뒀다. 읽었고, 두 군데가 어긋났다. 011이 원인 정정을 문서
맨 앞에 둔 것과 같은 이유로 여기도 앞에 둔다.

### 2.1 "등가는 메모리에 상주할 때만 성립한다" — 틀렸다

로드맵은 이렇게 적고 있었다.

> 커버링 인덱스는 버퍼를 2,130 → 354(-83%)로 줄였는데 실제 페이지 읽기가 196 vs 198로 같았던 것이
> 기각 근거였고, **그 등가는 작업 집합이 메모리에 상주할 때만 성립한다.**

007 4.6절의 그 196 vs 198은 **컨테이너를 재시작해 shared buffers를 비우고, 한 번도 안 건드린
회차**(A=회차 500, B=회차 900)로 잰 **cold 측정**이다. "메모리에 없는" 상태를 이미 쟀고 거기서
같았다. `docs/ERD.md` 2절은 이 결론을 조건 없이 정확하게 적고 있었다 — 로드맵만 조건을 붙였다.

007 자신도 9절에서 "작업 집합이 넘기면 **그때는 커버링 인덱스가 읽을 페이지를 실제로 줄인다**"고
적었는데, 그 문장도 같은 문서 4.6절 데이터와 충돌한다. 힙 42페이지를 안 읽는 대신 인덱스 44페이지를
읽기 때문이고, 애초에 cold `read` 196 중 **154페이지는 `seats` Seq Scan 몫**이라 이 인덱스가
건드리지도 못한다.

그래서 재판정의 가설을 **반대 방향으로** 세웠다 — 작업 집합이 커지면 338MB가 같은 버퍼를 두고
힙과 경쟁해 **더 불리해질** 수 있다. (결과는 4.5·4.6절. 읽기는 실제로 늘었고, 처리량은 안 움직였다.)

### 2.2 "`uk_members_email` 제거를 검토한다" — 007에 없는 말이다

이 인덱스는 `CREATE UNIQUE INDEX uk_members_email ON members (lower(email))`(`V1`)이고
**이메일 대소문자 무시 유니크 제약 본체**다(`V1` 118행 COMMENT: "대소문자 구분 없이 유일").
`AuthService`가 `existsByEmail` 체크와 저장 사이의 레이스를 이 제약에 맡기고 있고
(`AuthService.java:42`), 테스트 픽스처도 제약 이름에 의존한다(`AbstractConcurrencyTest.java:70`).
지우면 성능 항목이 아니라 **동시 가입 중복 방어가 사라진다.**

007 10절이 적은 선택지는 둘이고 **제거는 그중에 없다** — ① 리포지토리를 `lower(email) = ?`로
② `members(email)` 평문 인덱스 신설. 이번 범위는 **기울기 측정까지**로 잡았다(4.8절).

**두 정정 모두 교훈 6이 가리키는 자리다.** 수치는 007에 그대로 있었고 읽히지 않았을 뿐이다.

## 3. 측정 설계 — 축 셋, 군 아홉

007이 "작업 집합이 버퍼를 넘기면"이라고만 적어 둔 그 구간을 **만들 수 있어야** 재판정이 성립한다.
세 가지를 각각 독립적으로 움직였다.

| 축 | 값 | 바꾸는 방법 |
|---|---|---|
| `shared_buffers` | 128MB / 512MB / **1GB** | `docker-compose.prod.yml`의 postgres `command`(신설) + 재시작 |
| 작업 집합 | 회차 60개 / **600개** | `SAMPLE_SCHEDULES` — 하네스에 이미 있었다(`lib/catalog.js:45`) |
| 커버링 인덱스 | 없음 / `idx_ss_cover` | psql에서 직접 생성·DROP (마이그레이션으로 넣지 않는다) |

**작업 집합을 키우는 데 새 시드가 필요 없었다.** 시드에 회차가 이미 3,000개 있고
(`01-bulk-catalog.sql`: 공연 1,000 × 회차 3 × 2,000석), 회차 하나의 cold `read`가 39페이지
(312KB)다. 600회차면 **183MB**로 128MB를 1.43배 넘긴다. `catalog.js:48`의 `page < 10` 상한이
정확히 600회차(10페이지 × 공연 20 × 회차 3)까지 허용해서, 상한을 건드리지 않고 거기까지 갔다.

### 3.1 군을 아홉으로 늘린 이유 둘

처음 설계는 6군이었는데 돌리면서 둘을 추가했다.

- **A2(포화·60회차)를 넣었다.** A(저부하·60회차)와 B(포화·600회차)를 비교하면 **부하 수준과 작업
  집합이 동시에 바뀐다** — 차이가 나와도 어느 축 때문인지 가를 수 없다. A2가 있어야 A2↔B가 작업
  집합 축만 분리한다.
- **B를 세 번 쟀다(B·B′·B″).** D군(커버링 인덱스)이 처음 +3.0%로 나왔는데, 007이 7.4절에서
  "엄밀히 비교하려면 같은 데이터 위에서 old/new를 번갈아 재야 한다. 하지 않았다"를 한계로 남긴
  자리였다. 인덱스를 떼고 다시 재니(B′) 767, 측정 세션 끝에 또 재니(B″) 776이었고, **9회를 모으자
  산포가 736~796으로 벌어져 D의 793~800을 삼켰다**(4.6절). 한 번만 쟀으면 채택했을 수치다.

| 군 | `shared_buffers` | 회차 | 인덱스 | 부하 | 답하는 질문 |
|---|---|---|---|---|---|
| **A** | 128MB | 60 | — | 저부하 | 007 7.1 기준선이 재현되나 |
| **A2** | 128MB | 60 | — | 포화 | 작업 집합 축의 대조군 |
| **B / B′ / B″** | 128MB | 600 | — | 포화 | **007이 안 쟀던 구간** |
| **D** | 128MB | 600 | `idx_ss_cover` | 포화 | 인덱스 재판정 |
| **C1** | 512MB | 600 | — | 포화 | 기울기 중간점 |
| **C2** | **1GB** | 600 | — | 포화 | 전부 상주. B와 비교가 본론 |
| **E** | 1GB | 60 | — | 저부하 | **036의 before가 바뀌는가** |

포화는 `THINK_MIN=0 THINK_MAX=0 VUS=300 DURATION=45s`다(`seatmap.js`가 `ramping-vus`라
think를 꺼야 포화가 된다 — `lib/config.js`의 그 스위치). 군마다 warmup 1회를 버려
`shared_buffers`를 바꾼 군이 cold에서 출발하는 조건을 전 군에 똑같이 적용했다.

## 4. 핵심 발견

### 4.1 전제는 실제로 깨졌다 — 디스크 읽기 0 → 53만 페이지

| 군 | `blks_read` | `hit_pct` | `blk_read_ms` |
|---|---|---|---|
| A2 (60회차 / 128MB) | **0** | 100.00% | 0 |
| B (600회차 / 128MB) | **535,248** | 99.31% | 1,928 |
| B′ | 522,343 | 99.32% | 2,041 |
| B″ | 529,558 | 99.32% | 1,952 |

007의 "k6 좌석맵 시나리오도 회차 60개를 돌리므로 약 20MB로 128MB 안에 들어간다"가 정확히
재현됐다(A2에서 `blks_read`가 **0**이다). 600회차로 올리면 45초 동안 **53만 페이지(4.1GB)** 를
읽고 I/O에 약 **2초**를 쓴다. 요청당으로는 **9.8페이지**다.

**이 수치가 세 번 반복에서 522k~535k로 거의 변하지 않는다는 점이 중요하다.** 뒤에 나오는 처리량은
같은 조건에서 736~796으로 흔들리는데, I/O 쪽은 ±1.2% 안이다. 그래서 "작업 집합이 넘었다"는 사실
자체는 확실하고, 흔들리는 건 그 결과다.

### 4.2 그 "디스크 읽기"는 디스크가 아니었다 — 페이지당 3.96μs

1,928ms / 535,248페이지 = **페이지당 3.6~4.0μs**다. NVMe라도 4KB 랜덤 읽기가 수십 μs이고
8KB면 더 든다. 즉 Postgres가 "shared buffer miss"로 센 이 읽기는 거의 전부 **OS 페이지 캐시에서의
memcpy**다 — 호스트 RAM이 31GB인데 DB가 1,014MB라서 전체가 OS 캐시에 들어간다.

007이 `effective_cache_size 4GB`로 설정만 해두고 쟀던 그 계층이 여기서 수치로 드러났다.
**그래서 `shared_buffers` 상향이 걷어낼 수 있는 최대치가 측정 시작 시점에 이미 보였다** —
45초 중 2초, 그것도 16코어에 흩어진 값이다.

### 4.3 I/O를 0으로 만들었는데 처리량이 안 변했다

| 군 | `blks_read` | `blk_read_ms` | 처리량 (3회 중앙) | 좌석맵 p95 중앙 |
|---|---|---|---|---|
| B / B′ / B″ (128MB) | 522k~535k | 1,928~2,041 | **775** req/s (9회, 736~796) | 402~474ms |
| C1 (512MB) | **0** | **0** | **768** req/s (767~774) | 519ms |
| C2 (1GB) | **0** | **0** | **766** req/s (759~769) | 417ms |

**512MB에서 이미 `blks_read`가 0이다.** 600회차 작업 집합 183MB가 완전히 상주한다. 1GB는 더
올려도 같다. I/O 2초를 통째로 없앴는데 처리량은 **775 → 768 → 766**으로, 128MB 9회 산포
(736~796) 안에서 움직이지도 않았다.

**기울기가 평평하다.** 세 점을 잡은 이유(교훈 3)가 여기서 값을 했다 — 128MB와 1GB만 쟀다면
"1GB가 과했나"를 물을 수 있었는데, 512MB에서 이미 I/O가 0이고 처리량이 그대로라 그 질문이 닫힌다.

이게 007의 발견이 한 단계 올라간 지점이다.

> 007: `Buffers: shared hit`은 I/O의 대리지표가 아니다.
> 012: **이 규모에서는 I/O도 처리량의 대리지표가 아니다.**

### 4.4 벽은 Postgres CPU 400%다

포화 구간(1GB, 600회차)에서 컨테이너 CPU를 2초 간격 28샘플로 쟀다.

| 컨테이너 | 중앙 | 최대 |
|---|---|---|
| **tikkit-postgres-prod** | **400.3%** (4.00코어) | 427.5% |
| tikkit-prod-backend1-1 | 134.2% | 168.3% |
| tikkit-prod-backend2-1 | 135.0% | 148.7% |
| BE 2대 합계 | 271.5% | 317.0% |

**좌석맵 워크로드에서는 Postgres가 BE 2대보다 CPU를 많이 쓴다.** Task 033의
`t033-backend-metrics/cpu-probe.txt`에 남은 **예매** 워크로드는 정반대였다 — backend1 291% /
backend2 414% / **postgres 51%**. 같은 스택인데 워크로드에 따라 CPU 주인이 바뀐다.

Task 038이 "포화 벽의 정체가 'CPU'까지만이고 어느 코드가 먹는지 프로파일링하지 않았다"고
남겼는데, **좌석맵 워크로드에 대해서는 절반이 닫혔다** — 프로세스 단위까지는 Postgres다.
그 4코어는 I/O가 아니라 2,000행 튜플 구성과 237KB 결과 전송에 들어가고, 그래서 `shared_buffers`로
I/O를 0으로 만들어도 안 줄어든다. **Task 036의 "조립 68%"가 이 4코어와 같은 것을 보고 있을
가능성이 크다.**

### 4.5 커버링 인덱스는 cold에서 **더** 읽는다 — 194 → 200

007 4.6절을 그대로 재현했다. 컨테이너를 재시작해 버퍼를 비우고, 한 번도 안 건드린 회차로 잰다.

| | 인덱스 없음 (회차 4500) | `idx_ss_cover` (회차 4501) | 007 (참고) |
|---|---|---|---|
| 실행 시간 | 4.082 ms | 4.220 ms | 4.757 / 4.015 ms |
| `shared read` 합계 | **194** | **200** | 196 / 198 |
| 그중 `seats` Seq Scan | 155 | 155 | 154 / 154 |
| 그중 `schedule_seats` | 39 | 45 | 42 / 44 |
| `I/O Timings: shared read` | **1.516 ms** | **1.680 ms** | (007은 `track_io_timing`이 꺼져 있었다) |

007의 196 vs 198이 **방향까지 재현됐다 — 인덱스 쪽이 6페이지 더 읽는다.** 로드맵이 적었던
"작업 집합을 넘기면 인덱스가 읽을 페이지를 줄인다"의 반대다.

`track_io_timing`을 켠 덕에 007이 못 본 값이 하나 붙는다 — cold 좌석맵 4.08ms 중 **1.52ms(37%)가
실제 읽기 대기**다. 그리고 그 1.52ms는 warm이 되면 사라지는데, 4.3절이 보여준 대로 **사라져도
처리량은 안 바뀐다.**

### 4.6 포화에서 +3.1%가 나왔지만 산포 안이다

| 군 | 요청당 버퍼 접근(hit) | 요청당 읽기(read) | 처리량 중앙 | 처리량 범위 |
|---|---|---|---|---|
| B·B′·B″ 무인덱스 (9회) | **1,409회** | **9.8페이지** | 775 req/s | **736~796** |
| D 커버링 인덱스 (3회) | **189회 (-86.6%)** | **12.7페이지 (+29%)** | 799 req/s | 793~800 |

요청당 버퍼 접근이 **1,409 → 189회**로 줄었다. 007이 단일 쿼리에서 본 2,130 → 354(-83%)와 같은
효과가 요청 단위에서 -86.6%로 재현된 것이다. 디스크 읽기는 반대로 9.8 → 12.7페이지로 늘었고
(338MB 인덱스가 같은 버퍼를 다툰다 — `hit_pct` 99.31% → 93.69%), 그게 2.1절에서 세운 가설이었다.

처리량은 중앙값으로 **+3.1%**다. 그런데 **D의 최솟값 793이 무인덱스 9회의 최댓값 796보다 낮다** —
두 분포가 겹친다. 3회로 끝냈다면 "+3.0%, 범위가 안 겹친다"로 채택 쪽에 섰을 수치이고, B를 두 번 더
잰 것이 그걸 막았다.

**그래서 기각을 유지한다.** 그리고 이제 조건을 뗀다 — 007의 기각은 "지금 규모에서"였는데, 여기서
**cold에서도, 포화에서도, 작업 집합이 버퍼를 1.43배 넘겨도** 같은 결론이다. 내주는 쪽은 그대로다:
**338MB + HOT 100% → 34% + 예매 p95 +9.5%**(007 4.9절).

### 4.7 036의 before는 바뀌지 않는다 — 040을 앞에 둔 이유가 틀렸다

로드맵이 040을 036 앞에 둔 근거는 "`shared_buffers`를 올리면 036의 before 값이 바뀐다"였다.
저부하·60회차에서 128MB와 1GB를 비교했다(007 7.1과 같은 조건).

| 군 | 좌석맵 p95 (3회) | 중앙 | med 중앙 |
|---|---|---|---|
| 007 7.1 (참고) | 6.67 / 6.77 / 6.93 ms | 6.77 ms | — |
| A (128MB) | 7.20 / 7.25 / 6.88 ms | **7.20 ms** | 6.03 ms |
| E (1GB) | 7.08 / 6.89 / 6.97 ms | **6.97 ms** | 6.02 ms |

-0.23ms(-3.2%)이고 두 범위가 겹친다(A 6.88~7.25 / E 6.89~7.08). 중앙값 `med`는 6.03 vs 6.02로
사실상 같다. 당연한 결과다 — 60회차 작업 집합은 128MB에서도 `blks_read`가 0이라 **올릴 여지가
처음부터 없었다.**

**순서 근거가 틀렸다는 것도 결과다.** 착수 전에 이 판정 조건을 적어 뒀기 때문에 그대로 기록할 수
있다. 036을 먼저 했어도 040이 그 결론을 뒤집지 않았다. 다만 040을 먼저 한 덕에 **036이 공략할
대상이 I/O가 아니라 Postgres CPU 4코어라는 것**을 알고 들어간다(4.4절) — 순서의 이득은 거기서
나왔고, 로드맵이 적은 이유에서 나온 게 아니다.

### 4.8 `uk_members_email` — 5만 명에서 이미 로그인의 8%다

007 4.10절이 "회원이 503명이 아니라 수십만이 되면 Seq Scan이 선형으로 늘어나므로, 그때 판단할
항목으로 남긴다"고 적었다. 그 "수십만"을 숫자로 바꿨다. 회원을 늘려가며 두 쿼리를 warm 3회씩 쟀다.

| 회원 | 힙 페이지 | `where email = ?` (현재 코드) | `where lower(email) = ?` | 차이 | 로그인 p95 54ms 대비 |
|---|---|---|---|---|---|
| 503 (007) | ~11 | Seq Scan **0.088 ms** | Index Scan 0.049 ms | 0.039 ms | 0.07% |
| 2,005 | 45 | Seq Scan **0.248 ms** | 0.065 ms | 0.183 ms | 0.34% |
| 10,005 | 214 | Seq Scan **0.895 ms** | 0.067 ms | 0.828 ms | 1.5% |
| 30,005 | 640 | Seq Scan **2.287 ms** | 0.078 ms | 2.209 ms | 4.1% |
| 50,005 | 1,065 | Seq Scan **4.442 ms** | 0.074 ms | 4.368 ms | **8.1%** |
| 200,005 | 4,257 | Seq Scan **13.279 ms** (직렬) | 0.074 ms | 13.205 ms | **24.5%** |

페이지당 3~5μs로 **선형이 맞다**(007의 예측이 성립한다). 인덱스 쪽은 0.049 → 0.074ms로 평탄하다.
**재판단 조건을 "수십만"에서 "약 3만 명"으로 바꾼다** — 로그인 p95의 5%(2.7ms)를 넘는 지점이
거기이고, 5만 명이면 8%다. 007이 "0.07%니까 고치지 않는다"고 쓴 판단은 503명에서만 맞았다.

**조치는 하지 않았다** — 이 Task의 범위를 측정으로 잡았다. 선택지는 007 10절 그대로 둘이다.

| 안 | 내용 | 대가 |
|---|---|---|
| (a) | `findByEmail`/`existsByEmail`을 `lower(m.email) = :email`로 | 인덱스 추가 0, 쓰기 비용 0. 인증 쿼리가 함수 조건이 된다 |
| (b) | `members(email)` 평문 인덱스 신설 | 함수 인덱스와 중복, 쓰기 비용 2배 |

확인한 사실 하나가 (a)의 비용을 더 낮춘다 — **앱이 이미 이메일을 소문자로 정규화한다**
(`Member.java:45`, `AuthService.java:25`, `MemberDetailsService.java:26`, 그리고
`MemberRepository.java:10-11`의 규약 주석). 컬럼 값이 항상 소문자이므로 `lower(email) = email`이고,
(a)는 **쿼리 의미를 실질적으로 바꾸지 않는다.** 007이 "인증 쿼리의 의미가 바뀐다"고 적은 우려는
정규화를 안 하는 코드였다면 맞았다.

### 4.9 20만 명에서 플래너가 병렬로 간다 — 동시 부하에선 못 빌리는 자원이다

4.8절의 20만 명 측정을 처음 그냥 돌렸을 때 11.391ms가 나왔는데, 플랜을 보니 이랬다.

```
 Gather (actual time=0.373..9.855 rows=1 loops=1)
   Workers Planned: 2
   Workers Launched: 2
   ->  Parallel Seq Scan on members (actual time=3.123..5.620 rows=0 loops=3)
```

**워커 2개를 빌려서 3배의 CPU로 2.56배 빨라진 값이다.** 로그인이 동시에 들어오는 상황에서는
`max_parallel_workers`(기본 8)가 즉시 고갈되므로 저 11.4ms는 나오지 않는다.
`max_parallel_workers_per_gather = 0`으로 직렬화해 다시 재니 **13.279ms**였고, 표에는 그 값을 썼다.

007 9절이 "`EXPLAIN ANALYZE`는 접속 하나에서 직렬로 쟀다. 동시에 82 req/s가 들어오는 중의 플랜이
아니다"를 한계로 남겼는데, **그 한계가 처음으로 실제 숫자를 바꾼 사례다.** 단일 접속 측정이
빨라 보이는 방향으로 틀린다.

## 5. 실제로 만든 것

### 5.1 `shared_buffers`를 측정 축으로 뺐다

두 compose 모두 postgres 서비스에 `command:`가 없었고 `postgresql.conf`도 마운트하지 않아서,
**모든 Postgres 파라미터가 `postgres:15` 이미지 기본값이었다.** `docker-compose.prod.yml`에
`command:`를 신설했다.

```yaml
command:
  - postgres
  - -c
  - shared_buffers=${POSTGRES_SHARED_BUFFERS:-128MB}
  - -c
  - track_io_timing=on
```

**기본값을 128MB로 뒀다.** 재기 전에 올리면 before가 사라진다. 기각으로 끝났으므로 그대로 둔다.
`track_io_timing`은 켠 상태로 남긴다 — 4.2·4.5절이 보여준 대로 "읽은 페이지 수"와 "읽기에 쓴
시간"은 다른 값이고, 007이 버퍼 접근 수로 한 번 속은 자리라 시간 쪽을 볼 수 있어야 한다.
개발용 `tikkit-back/docker-compose.yml`은 건드리지 않았다.

### 5.2 Postgres I/O 지표 수집 — psql 손커맨드뿐이었다

`pg_stat_statements`·`pg_buffercache`·postgres_exporter가 레포에 **하나도 없다.**
`sample-backend-metrics.sh`는 BE만 긁고, Grafana로는 Postgres 내부가 안 보인다.
그래서 두 파일을 만들었다.

| 파일 | 하는 일 |
|---|---|
| `load-test/sql/06-reset-db-stats.sql` | `pg_stat_reset()` + 리셋 확인. k6 실행 **직전**이어야 한다 |
| `load-test/sql/07-db-io-report.sql` | `pg_stat_database`(blks_read/hit, blk_read_time) + `pg_statio_user_tables`(테이블별) + `pg_stat_user_indexes`(어떤 인덱스가 실제로 돌았나) |

07의 4번째 블록이 D군에서 값을 했다 — `idx_ss_cover`의 `idx_scan`이 56,051이고
`uk_schedule_seats_schedule_seat`가 0이라, **플래너가 실제로 새 인덱스를 골랐다**는 것을 확인했다.
이게 0이면 그 군은 "인덱스가 있는데 안 쓰는 군"이지 재판정이 아니다.

### 5.3 워크로드 무결성 검사 — 010이 만들었다고 적었지만 커밋되지 않았다

010이 `run-group.sh`에 "iteration당 요청 수 2.5개 이상, 수신 100KB 이상"을 넣었다고 기록했는데,
**그 스크립트는 레포에도 git 히스토리에도 없다.** 교훈 1("수치가 좋아지는 가장 흔한 이유는 요청이
일을 안 한 것")의 집행 장치가 사라져 있었다.

`load-test/check-run.py`로 다시 만들면서 셸 루프 **밖**에 뒀다 — 요약 JSON을 사후에 읽으므로
과거 결과에도 적용되고, 커밋되는 자리에 있다. 기존 결과 3개(`seatmap`/`browse`/`booking-spike`
final)로 통과를, 응답이 비어 오는 합성 케이스로 실패를 확인했다. 이번 36회 실행 전부 통과했다
(좌석 수 2,000 고정, iteration당 수신 247KB).

### 5.4 `load-test/run-group.sh`

군 하나를 N회 반복하며 매 실행마다 네 산출물을 남긴다 — k6 요약 JSON / `07` I/O 리포트 /
Hikari·heap CSV / 무결성 판정. 18회를 손으로 치면 환경변수 하나가 조용히 어긋난다.
warmup 1회를 버리는 규칙도 여기 들어 있다.

## 6. 밟은 함정 셋

### 6.1 postgres를 재생성하면 BE 풀이 끊긴 커넥션을 들고 있다

010이 "군마다 `up -d backend1 backend2` + `restart nginx`가 필수"라고 적어 뒀는데, 그건 **BE
환경변수를 바꾼 군**의 절차다. Task 040은 postgres만 바꾸므로 BE의 compose 설정이 그대로이고,
`up -d`가 **아무것도 하지 않는다.** 그 상태로 측정에 들어가면 이게 쏟아진다.

```
HikariPool-1 - Failed to validate connection ... (This connection has been closed.)
```

요청은 200으로 나가므로(Hikari가 검증 실패분을 버리고 새로 연다) **로그를 안 보면 모른다.**
`restart backend1 backend2`로 바꿨다. `docker compose restart`는 컨테이너를 유지하므로
`docker logs`에 재시작 전 로그가 남아 있고, 그래서 깨끗해졌는지 확인할 때는 `--since`를
재시작 이후로 좁혀야 한다.

### 6.2 `python -I`는 `PYTHONIOENCODING`을 무시한다

`check-run.py`가 한국어 메시지 안의 em dash 하나에서 `UnicodeEncodeError`로 죽었다 —
윈도우 콘솔이 cp949다. `PYTHONIOENCODING=utf-8`을 export해도 안 됐는데, **격리 모드(`-I`)가
`-E`를 포함해서 환경변수를 전부 무시**하기 때문이다. 스크립트 안에서
`sys.stdout.reconfigure(encoding='utf-8')`로 닫았다. 밖에 맡기면 "검사 실패"와 "검사 자체가 안
돌았다"가 구분되지 않는데, 둘을 못 가르는 게 더 나쁘다.

### 6.3 하네스를 벗어난 1회 측정이 군 전체를 넘었다

CPU를 잡으려고 `run-group.sh` 없이 k6를 직접 한 번 돌렸더니 **832 req/s**가 나왔다 — 9회 군
산포(736~796)의 위다. 그 실행만 `98-reset`·`06-reset`과 `sample-backend-metrics.sh`
(2초마다 `docker exec curl /actuator/prometheus` ×2)가 빠져 있었다. 어느 쪽이 얼마를 먹는지는
가르지 않았고, 대신 **군 간 비교는 같은 하네스 안에서만 한다**는 규칙으로 닫았다. 4.3·4.6절의
비교는 전부 `run-group.sh` 안이다. 이 832는 수치로 쓰지 않는다.

## 7. 수치

### 7.1 처리량·지연 (좌석맵, 포화 `VUS=300 DURATION=45s`, 3회)

| 군 | `shared_buffers` | 회차 | 인덱스 | 처리량 req/s | 중앙 | 좌석맵 p95 중앙 |
|---|---|---|---|---|---|---|
| A2 | 128MB | 60 | — | 769 / 781 / 779 | **779** | 425 ms |
| B | 128MB | 600 | — | 775 / 776 / 781 | 776 | 472 ms |
| B′ | 128MB | 600 | — | 767 / 736 / 772 | 767 | 474 ms |
| B″ | 128MB | 600 | — | 739 / 776 / 796 | 776 | 402 ms |
| **B∪B′∪B″ (9회)** | 128MB | 600 | — | — | **775** (736~796) | — |
| D | 128MB | 600 | `idx_ss_cover` | 793 / 800 / 799 | 799 (793~800) | 399 ms |
| C1 | **512MB** | 600 | — | 767 / 768 / 774 | **768** | 519 ms |
| C2 | **1GB** | 600 | — | 759 / 769 / 766 | **766** | 417 ms |

### 7.2 저부하 (007 7.1과 같은 조건, `VUS=30 DURATION=90s`, 3회)

| 군 | 좌석맵 p95 | 중앙 | med |
|---|---|---|---|
| 007 7.1 (before) | 6.67 / 6.77 / 6.93 ms | 6.77 ms | — |
| A (128MB / 60회차) | 7.20 / 7.25 / 6.88 ms | **7.20 ms** | 6.03 ms |
| E (1GB / 60회차) | 7.08 / 6.89 / 6.97 ms | **6.97 ms** | 6.02 ms |

### 7.3 DB 블록 I/O (45초 실행 1회당, 3회 중앙)

| 군 | `blks_read` | `hit_pct` | `blk_read_ms` | 요청당 버퍼 접근 | 요청당 읽기 |
|---|---|---|---|---|---|
| A2 (60회차 / 128MB) | **0** | 100.00% | 0 | — | 0 |
| B (600회차 / 128MB) | 535,248 | 99.31% | 1,928 | **1,409회** | **9.8page** |
| D (+ `idx_ss_cover`) | **715,333** | 93.69% | 2,265 | **189회** | **12.7page** |
| C1 (512MB) | **0** | 100.00% | **0** | — | 0 |
| C2 (1GB) | **0** | 100.00% | **0** | 1,419회 | 0 |

### 7.4 포화 CPU (1GB / 600회차, 2초 간격 28샘플)

| 컨테이너 | 중앙 | 최대 | Task 033 예매 워크로드 (참고) |
|---|---|---|---|
| **postgres** | **400.3%** | 427.5% | 51.3% |
| backend1 | 134.2% | 168.3% | 291.7% |
| backend2 | 135.0% | 148.7% | 414.7% |

### 7.5 Hikari (포화 군, 2초 간격 샘플의 최대)

| 군 | `active` | `pending` | `acquire_max` |
|---|---|---|---|
| A / E (저부하) | 1 | **0** | 0.5 / 283.7 ms |
| B / B′ (128MB) | 10 | 81 / 63 | 445 / 388 ms |
| C1 (512MB) | 10 | **42** | 389 ms |
| C2 (1GB) | 10 | **52** | 364 ms |
| D (커버링) | 10 | **45** | 903 ms |

`pending`이 128MB 72(중앙) → 512MB·1GB 42~52로 줄었는데 **처리량은 안 올랐다.** Task 033이
"풀 소진과 커넥션 대기는 포화의 원인이 아니라 증상이었다"고 적은 그대로다. 샘플 간격이 2초인
게이지라 값 자체는 거칠다.

## 8. 한계

- **부하 생성기가 측정 대상과 같은 호스트에서 돈다**(006의 첫 한계, Task 038). 이번에 그 대가가
  처리량 산포로 나타났다 — 같은 조건 9회가 736~796(±4%)이고, 그 폭이 커버링 인덱스의 +3.1%를
  삼켰다. **I/O 지표는 ±1.2%로 훨씬 안정적이라 결론을 거기에 걸었다.**
- **`seatmap.js` 하나만 포화시켰다.** 축이 좌석맵 작업 집합이라 `browse`(공연 1,008행)와
  `booking-spike`는 포화 군에서 안 돌렸다. 섞어서 재는 것은 Task 043이다.
- **600회차(183MB)까지만 키웠다.** `catalog.js:48`의 `page < 10` 상한이 거기이고, 상한을 풀면
  3,000회차(약 985MB)까지 간다. 128MB의 1.43배에서 이미 "I/O가 생겨도 처리량이 안 바뀐다"가
  나왔으므로 더 키우지 않았다. **다만 985MB는 OS 페이지 캐시(31GB)에도 전부 들어가므로, 더 키워도
  4.2절의 "진짜 디스크를 안 친다"는 조건은 안 바뀐다.** 그 조건을 깨려면 DB가 호스트 RAM을
  넘어야 한다.
- **진짜 디스크 I/O를 한 번도 안 쟀다.** 위 항목의 결과다. 운영에서 DB가 RAM을 넘으면
  `shared_buffers`의 가치가 달라지고, 이 기각은 거기까지 가지 않는다.
- **`blk_read_ms`는 `track_io_timing`을 켠 뒤의 값이라 007과 직접 비교할 수 없다.** 007은 꺼져
  있었다. 켜는 것 자체의 오버헤드도 분리하지 않았다(A군 p95 7.20 vs 007 6.77의 +6.4%에 이게
  섞여 있을 수 있고, monitoring 프로필 2개가 떠 있던 것도 섞여 있다).
- **커버링 인덱스의 쓰기 대가를 이번에 재측정하지 않았다.** HOT 100% → 34%와 예매 p95 +9.5%는
  007 4.9절 값을 그대로 인용했다. 기각 쪽이라 다시 재지 않았지만, 채택을 검토할 때는 다시 재야 한다.
- **`uk_members_email` 기울기는 단일 접속 `EXPLAIN`이다**(007 9절의 같은 한계). 4.9절이 보여준
  대로 그 측정은 빨라 보이는 방향으로 틀린다 — 20만 명의 13.279ms도 동시 부하 중의 값은 아니다.
- **Postgres CPU 400%를 프로세스 단위까지만 쪼갰다.** 어느 쿼리·어느 단계(튜플 구성/정렬/전송)가
  먹는지는 `pg_stat_statements`가 없어서 못 봤다. Task 036의 입구다.

## 9. 다음 단계

- **[Task 036] 좌석맵 조립 비용**: 이 Task가 036의 과녁을 바꿔 준다. **줄여야 하는 것은 I/O가
  아니라 Postgres CPU 4코어다**(4.4절). 그리고 036의 before는 `shared_buffers`에 흔들리지
  않는다(4.7절). 로컬 캐시를 보더라도 "DB 조회가 빨라져서"가 아니라 **"Postgres CPU를 아예 안
  쓰게 해서" 이득이 나오는 구조**로 재야 한다.
- **[Task 036] `pg_stat_statements`를 켤 것**: 400%를 쿼리 단위로 쪼개려면 필요하다.
  `shared_preload_libraries`가 필요해서 postgres `command:`를 또 손대야 하는데, 이번에 그 자리를
  만들어 뒀다.
- **[Task 043] 간섭 재현**: 4.4절이 043의 전제를 하나 바꾼다 — 좌석맵과 예매가 **CPU를 먹는
  컨테이너가 서로 다르다**(좌석맵은 postgres 400%, 예매는 BE 700%). 섞으면 둘이 다른 자원에서
  부딪히므로, 처리량이 아니라 자원별로 봐야 한다는 043의 설계가 더 맞다.
- **[미배정] `uk_members_email`을 회원 3만 명 전에 고칠 것**(4.8절). 재판단 조건을 "수십만"에서
  **약 3만 명**으로 당겼다. (a)안이 유력하다 — 앱이 이미 소문자로 정규화하므로 쿼리 의미가 실질적으로
  안 바뀐다. 다만 지금 회원은 2,005명(부하 시드)이라 급하지 않다.
- **[Task 038] 전용 부하 노드**: 이 Task가 그 필요를 한 번 더 보여줬다 — 같은 조건 9회의 ±4%가
  3%대 변경을 판정 불가로 만든다. 다만 **I/O 지표는 그 노이즈를 거의 안 받는다**는 것도 알았다.

**범위 밖에서 발견한 것**:

- **Postgres가 좌석맵 워크로드의 최대 CPU 소비자다**(4.4절). t033의 `cpu-probe.txt`와 나란히
  놓으면 워크로드에 따라 CPU 주인이 바뀐다 — 예매는 BE, 좌석맵은 DB다.
- **`load-test/results/`가 gitignore 대상이다**(`.gitignore:21-25`). 이번 36회 실행의 산출물도
  로컬에만 남는다. 문서의 표가 유일한 기록이다.

---

## 재현 방법

```bash
# 0) 로컬 BE·FE 개발 서버를 모두 끈 상태로 시작한다 (단일 호스트에서 k6까지 돈다)
cd /path/to/TIKKIT
cp .env.example .env
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'     # Git Bash에서만 (007 6절)
set -a; . ./.env; set +a
docker compose -f docker-compose.prod.yml --profile monitoring up -d

P="docker exec -i tikkit-postgres-prod psql -q -U $POSTGRES_USER -d $POSTGRES_DB -f -"

# 1) 시드 — 007과 같은 600만 행. 02가 약 80초 걸린다
$P < load-test/sql/99-teardown.sql
$P < load-test/sql/01-bulk-catalog.sql
$P < load-test/sql/02-bulk-schedule-seats.sql
$P < load-test/sql/03-loadtest-members.sql
$P < load-test/sql/05-disable-queue.sql          # seatmap.js는 대기열을 켠 회차에 쓸 수 없다
docker exec -i tikkit-postgres-prod psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "VACUUM (ANALYZE, PARALLEL 0) schedule_seats;"

# 2) 회차 하나의 작업 집합을 확인한다 (600회차 = 183MB 계산의 근거)
docker restart tikkit-postgres-prod && sleep 10    # 버퍼를 비운다
docker exec -i tikkit-postgres-prod psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
select ss.id, s.section, s.row_label, s.seat_number, s.pos_x, s.pos_y, ss.status, ss.ticket_grade_id
from schedule_seats ss join seats s on s.id = ss.seat_id
where ss.schedule_id = 4500 order by s.pos_y, s.pos_x;"
# -> read=194 (seats 155 + schedule_seats 39), I/O Timings: shared read=1.516

# 3) 군을 돈다. run-group.sh가 06리셋 -> k6 -> 07리포트 -> 무결성검사를 묶는다
#    warmup 1회가 자동으로 추가되고 버려진다
bash load-test/run-group.sh t040-a-sb128-s60-low   seatmap.js 3
bash load-test/run-group.sh t040-a2-sb128-s60-sat  seatmap.js 3 \
     -e THINK_MIN=0 -e THINK_MAX=0 -e VUS=300 -e DURATION=45s
bash load-test/run-group.sh t040-b-sb128-s600-sat  seatmap.js 3 \
     -e THINK_MIN=0 -e THINK_MAX=0 -e VUS=300 -e DURATION=45s -e SAMPLE_SCHEDULES=600

# 4) D군 — 커버링 인덱스. 반드시 측정 후 DROP한다 (마이그레이션으로 넣지 않는다)
docker exec -i tikkit-postgres-prod psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
CREATE INDEX idx_ss_cover ON schedule_seats (schedule_id) INCLUDE (seat_id, status, ticket_grade_id);"
docker exec -i tikkit-postgres-prod psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "VACUUM (ANALYZE, PARALLEL 0) schedule_seats;"   # PARALLEL 0: /dev/shm 부족으로 죽는다
bash load-test/run-group.sh t040-d-sb128-s600-cover seatmap.js 3 \
     -e THINK_MIN=0 -e THINK_MAX=0 -e VUS=300 -e DURATION=45s -e SAMPLE_SCHEDULES=600
docker exec -i tikkit-postgres-prod psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "DROP INDEX idx_ss_cover;" -c "VACUUM (ANALYZE, PARALLEL 0) schedule_seats;"
# -> 인덱스를 뗀 뒤 B를 다시 잰다(B'). 3회만으로 판정하면 +3%를 채택하게 된다 (3.1절)

# 5) shared_buffers를 바꾼다. 재시작 필요 파라미터라 CONFIG SET이 안 된다
sed -i 's/^POSTGRES_SHARED_BUFFERS=.*/POSTGRES_SHARED_BUFFERS=512MB/' .env
set -a; . ./.env; set +a
docker compose -f docker-compose.prod.yml up -d postgres && sleep 12
docker compose -f docker-compose.prod.yml restart backend1 backend2 && sleep 25
docker compose -f docker-compose.prod.yml restart nginx && sleep 8
#  주의: up -d backend1 backend2 로는 안 된다 — BE 설정이 안 바뀌어서 재생성되지 않고,
#        끊긴 커넥션을 든 Hikari 풀로 측정에 들어간다 (6.1절). 확인:
docker logs --since 30s tikkit-prod-backend1-1 2>&1 | grep -c "has been closed"   # -> 0
docker exec -i tikkit-postgres-prod psql -q -t -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "select current_setting('shared_buffers');"                                  # -> 512MB

# 6) uk_members_email 기울기 (4.8절). 회원을 늘려가며 warm 3회씩
docker exec -i tikkit-postgres-prod psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
INSERT INTO members (email, password, name, phone, role)
SELECT 'bulk'||lpad(g.n::text,7,'0')||'@tikkit.com',
       '\$2b\$10\$H9zvS6Kdgp14diNSRTb3..NCsZdlAqrEJlkhKMNsxqJ5LGiBDwA1q',
       'bulk '||g.n, '010-1111-0000', 'USER'
FROM generate_series(1, 48000) AS g(n) ON CONFLICT DO NOTHING;"
docker exec -i tikkit-postgres-prod psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
set max_parallel_workers_per_gather = 0;      -- 20만 명에서 플래너가 워커를 빌린다 (4.9절)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
select id, email, password, name, phone, role, created_at, updated_at
from members where email = 'loadtest0500@tikkit.com';"
# 끝나면 되돌린다
docker exec -i tikkit-postgres-prod psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "delete from members where email like 'bulk%@tikkit.com';" \
  -c "VACUUM (FULL, ANALYZE) members;"

# 7) 정리 — shared_buffers를 128MB로 되돌리고 스택을 내린다
sed -i 's/^POSTGRES_SHARED_BUFFERS=.*/POSTGRES_SHARED_BUFFERS=128MB/' .env
docker compose -f docker-compose.prod.yml --profile monitoring --profile load down
```
