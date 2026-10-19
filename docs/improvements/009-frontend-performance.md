# 009. FE 렌더링 성능 기준선 — 전송량의 88%가 폰트 하나였다 (Task 030 측정 단계)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 030 (Phase 8: 성능 개선) — **측정 단계만**. 개선은 다음 Task |
| 대상 코드 | `load-test/sql/01b-small-catalog.sql`(신규), `load-test/sql/99-teardown.sql`·`98-reset-reservations.sql`(수정), `tikkit-front/e2e/perf/*`(신규), `playwright.config.ts`·`package.json` |
| 작성 시점 | 2026-10-19 |
| before 기준선 | 없다. FE를 재는 첫 측정이다. BE 쪽 비교 대상은 [006](006-performance-baseline.md) 좌석맵 응답 237.9KB / p95 6.72ms, [008](008-caching.md) 4.3절 "좌석맵 응답의 1.4~2.2ms가 캐시 밖" |
| 결론 | **FE 지표를 지배하는 건 좌석맵 응답이 아니라 서브셋 없이 통째로 받는 한글 변수 폰트 2,009KB다** — 전송량의 88%이고 좌석맵 문서(전송 32.7KB)의 61배, BE 좌석맵 응답(240KB)의 8.4배다. 006·007·008이 Task 030으로 넘긴 "237.9KB를 줄여라"는 **전송 기준으로는 세 번째 순위**였다 |
| 알게 된 것 | **어느 구간을 재는지 정하지 않으면 같은 데이터가 전혀 다른 크기로 보인다.** 좌석 배열은 BE→Next에서 240KB(비압축), Next→브라우저에서 659KB(같은 좌석이 SSR 마크업 + 인라인 RSC로 두 번), 실제 전송은 32.7KB(20:1 압축)다. 세 숫자가 다 맞고 다 다르다 |
| 데이터 | A군 `schedule_seats` 2,000행 / B군 6,026,894행. 좌석맵 문서는 **두 군에서 675,063바이트로 바이트 단위 동일** |

---

## 1. 왜 만들었나

ROADMAP의 Task 030은 "Lighthouse 베이스라인 측정 + `next/image`·`revalidate`·Suspense 적용"
한 줄이다. 그런데 착수해 보니 적힌 항목 세 개 중 **둘이 적용 대상이 없었다**.

| ROADMAP 항목 | 실제 | 처리 |
|---|---|---|
| `next/image` 적용 | `next/image` 0건, `<img>` 0건, `public/` 디렉터리 **없음**, `poster_url` 시드 전부 NULL. 포스터 자리는 Lucide 아이콘(`components/performance/performance-card.tsx:31-35`) | **적용 대상 0개** |
| `revalidate`/`cacheLife` | `lib/api/client.ts`의 `apiFetchRaw`가 `cookies()`를 **무조건** 호출해서 전 세그먼트가 동적 렌더링으로 떨어진다. 캐시 지시자 FE 전체 0건 | 선행 리팩터가 필요해 개선 Task로 |
| Suspense 스트리밍 | `Suspense`는 `app/(main)/page.tsx` 1곳뿐. 좌석맵은 `loading.tsx`만 있어 `Promise.all` 3개가 전부 끝날 때까지 백지 | 근거 충분 → 개선 Task 1순위 후보 |

그리고 앞선 Task들이 넘긴 숙제가 그 한 줄보다 컸다. 006·007이 좌석맵 응답 237.9KB를 줄이지
않은 채 넘겼고, 008은 좌석맵 캐싱을 기각하면서 **"좌석맵의 확장성 해법은 캐싱이 아니라 응답
크기를 줄이는 것이고 그건 Task 030이 맡는다"**(`ROADMAP.md`)고 못 박았다.

그래서 이 Task를 **측정**과 **개선**으로 쪼갰다. 이 문서는 측정만 다루고, **FE 애플리케이션
코드는 한 줄도 고치지 않았다.** 무엇을 고칠지 수치로 정하는 것이 목적이다.

맥북에서 한 번 착수했다가 중단했는데, 그 이유 자체가 6절의 첫 항목이 됐다.

---

## 2. 측정 환경

- `docker-compose.prod.yml` 전체 스택(nginx + BE 2대 + postgres + redis), **`--profile monitoring` 없이**
- `CACHE_TYPE=redis`, `AUTH_SESSION_STORE=redis` (prod 기본값)
- 측정 대상은 **`http://localhost`(nginx 80번) 하나뿐**. 3000번은 FE 컨테이너 내부 포트라
  nginx를 건너뛴다
- Lighthouse **13.5.0 호스트 CLI**, 호스트 Chrome(HeadlessChrome/155),
  `--chrome-flags="--headless=new --no-sandbox --disable-gpu"`
- Playwright `--project=perf`
- 호스트: Windows 11, Docker Desktop

`monitoring` 프로필을 끈 이유는 Prometheus 스크레이프와 Grafana가 측정 내내 CPU를 먹는데
**Lighthouse의 LCP/TBT는 k6와 달리 그 오염을 흡수하지 못하기** 때문이다. `tikkit.seatmap.build`
Timer는 Prometheus 서버 없이도 읽을 수 있다 — `application.yml`의
`management.endpoints.web.exposure.include`가 `health,prometheus`라서 컨테이너 안에서
`/actuator/prometheus`를 직접 긁으면 된다(`/actuator/metrics`는 `access.default: none`으로 닫혀 있다).

### 2.1 Lighthouse 프리셋

| 프리셋 | throughput | rtt | CPU | 회차 |
|---|---|---|---|---|
| **desktop** (주 프리셋) | 10,240 kbps | 40 ms | 1x | 좌석맵 A 6 / B 6, 홈 4, 상세 3, 캐시 콜드 1, 스로틀 없음 1 |
| mobile | 1,638.4 kbps | 150 ms | **4x** | 좌석맵 1 |

`throttlingMethod`는 둘 다 `simulate`다(Lantern). desktop을 주 프리셋으로 쓴 이유는 mobile의
4x CPU 슬로다운이 006 2절이 경고한 "부하 생성기와 측정 대상이 같은 호스트" 문제를 4배로
증폭하기 때문이다. 이번 목적은 "모바일에서 얼마나 나쁜가"가 아니라 "FE 비용이 어디 있는가"다.

### 2.2 측정 군 — before/after가 아니라 베이스라인 2종

| 군 | `schedule_seats` | 카탈로그 | 시딩 |
|---|---|---|---|
| **A** | 2,000 | 공연 1 / 회차 1 | `01b-small-catalog.sql` 0.35s + `02` 0.36s |
| **B** | 6,026,894 | 공연 1,000 / 회차 3,000 | `01` 0.45s + `02` **1m58s** |

두 군을 모두 재는 건 **좌석맵뿐이다.** 근거 한 줄: `01b`는 회차만 1개로 줄이고 **좌석 2,000석은
`01`과 같게 유지**하므로 좌석맵 응답 바이트가 두 규모에서 같아야 한다. 규모 차이를 가를 수 있는
페이지는 좌석맵 하나이고, 목록·상세는 "공연 1개 vs 1,000개"라 애초에 같은 것을 두 번 재는 게 아니다.

이 전제가 맞는지는 7.1절에서 검증했다. **675,063바이트로 바이트 단위까지 같았다.**

`01`과 `01b`를 같은 DB에 공존시키면 안 된다. `01`의 멱등 가드가 공연=제목별 / 회차=공연별이라
섞으면 에러 없이 **조용히 어긋난다**(공연 0001만 회차 1개로 남는다). 군 전환은 `99-teardown`이다.

**A → B 순서인 이유**: 600만을 먼저 깔면 teardown 후에도 PG 통계와 OS 페이지 캐시 잔재가 A에
남는다. 작은 것 다음에 큰 것이라야 오염이 한 방향으로만 간다.

### 2.3 워밍업과 반복

- **워밍업을 Lighthouse로 하지 않는다.** 1회 11.7초인데 수렴까지 돌리면 워밍업만 수 분이다.
  대상 URL을 `curl`로 15~20회 때려 TTFB가 평탄해질 때까지 데우고 그 값은 버린다.
  A군 20회 후 6.0~9.7ms, B군 18회 후 6.4~8.4ms에서 평탄해졌다.
- 본 측정 3회 → 산포 `(max-min)/중앙값`이 10% 이내면 수렴. **좌석맵은 수렴하지 않아 6회로 늘렸다**(6.5절).
- 산포가 20%를 넘는 지표는 수치를 쓰되 **판단 불가**로 표시한다(008의 규율).

### 2.4 리셋

| 대상 | 회차 사이 | 군 전환 시 |
|---|---|---|
| BE Redis 캐시 | 유지 | `FLUSHALL` |
| 브라우저 캐시 | 자동 콜드 (Lighthouse가 매번 새 프로필) | 동일 |
| 좌석 상태 | 불필요 (좌석맵 조회는 점유하지 않는다) | — |
| backend 컨테이너 | 유지 | `restart` |
| **nginx** | 유지 | **`restart` 필수** — backend를 재시작하면 IP가 바뀌고 nginx는 기동 시점 IP를 물고 있다(6.3절) |

---

## 3. 무엇을 쟀나

### 3.1 전송량 3층 분해

브라우저가 보는 TTFB 안에 nginx 왕복이 **2회** 접혀 있다(브라우저 → nginx → Next → nginx → BE).
그래서 **Lighthouse TTFB와 006의 좌석맵 p95 6.72ms는 직접 비교할 수 없다.** 층을 갈라야 한다.

| 층 | 구간 | 방법 |
|---|---|---|
| ① | DB + 조립 | `/actuator/prometheus`의 `tikkit_seatmap_build_seconds_{count,sum}` 차분 |
| ② | BE → Next | `page.request.get()`으로 `/api/v1/schedules/{id}/seats`, **`Accept-Encoding` 있/없 두 번** |
| ③ | Next → 브라우저 | Lighthouse `network-requests` + Playwright `page.on("response")` |

③에는 함정이 있다. **초기 네비게이션에서 RSC 플라이트 페이로드는 별도 요청이 아니라 HTML에
인라인된다.** `page.on("response")`만 보면 "문서 1개 N KB"로 뭉개져서 "좌석이 두 번 실렸다"가
안 보인다. 그래서 `self.__next_f.push`를 담은 `<script>`들의 바이트를 따로 센다. 마커를 못 찾으면
**0으로 보고하지 않고 스펙을 실패시킨다** — "RSC 페이로드가 없다"는 틀린 결론이 조용히 나오는
쪽이 더 위험하다.

`rsc: 1` 헤더를 붙이면 플라이트 페이로드만 따로 받을 수 있지만, `next-router-state-tree`를 같이
보내지 않으면 서버가 세그먼트 단위 부분 갱신이 아니라 **전체 페이로드**를 준다(Next 16
`cdn-caching` 문서). 그래서 그 수치는 "실제 링크 클릭이 받는 양"이 아니라 **그 상한**이다.

### 3.2 좌석 클릭 응답성 — 측정 계약을 이번에 못 박았다

004가 남긴 "클릭 → DOM 반영 5~8ms"는 **어떻게 쟀는지가 문서에 없다.** 이번에 정의했다.

- `t0` 좌석 `<rect>`에 click 이벤트를 디스패치한 직후
- `t1` 그 좌석의 `aria-checked` 변경이 DOM에 커밋된 시각 (`MutationObserver`)
- `t2` 그 다음 프레임 직전 (`requestAnimationFrame`)

`t1-t0`(commit)과 `t2-t0`(frame)을 둘 다 남긴다. `aria-checked`를 보는 건 `seat-map.tsx`의
선택 가능 레이어가 선택 상태를 그 속성으로 표현하기 때문이다(`role="checkbox"`).

**측정을 전부 페이지 안에서 한다.** `page.click()` + `expect()`로 재면 Playwright의 WS 왕복이
5~8ms 신호를 삼킨다. 그 차이가 얼마인지는 4.7절에서 따로 보여준다.

---

## 4. 핵심 발견

### 4.1 전송량의 88%가 폰트 하나다

좌석맵 1회 로드의 전송 바이트(Lighthouse `network-requests`, A군·B군 동일):

| 타입 | 개수 | 전송 | 디코드 | 압축 |
|---|---|---|---|---|
| **Font** | **1** | **2,009.5 KB** | 2,009.5 KB | **X** |
| Script | 14 | 186.1 KB | 606.5 KB | O |
| Document | 1 | 32.7 KB | 659.2 KB | O |
| Other (favicon.ico) | 1 | 25.7 KB | 25.3 KB | X |
| Stylesheet | 1 | 9.2 KB | 45.6 KB | O |
| Fetch | 10 | 7.5 KB | 7.3 KB | X |
| **합계** | **29** | **2,277.0 KB** | | |

폰트 1개가 **전송량의 88.3%**다. 좌석맵 문서(전송 32.7KB)의 **61배**, 006이 Task 030으로 넘긴
BE 좌석맵 응답(240KB)의 **8.4배**다.

출처는 `app/globals.css:4` 한 줄이다.

```css
@import "pretendard/dist/web/variable/pretendardvariable.css";
```

이건 패키지가 제공하는 **전체 폰트** CSS로, `PretendardVariable.woff2` 하나를 통째로 받는다.
같은 패키지에 `pretendardvariable-dynamic-subset.css`가 들어 있고, 그쪽은 `unicode-range`로
쪼갠 `@font-face` 선언 묶음이라 브라우저가 **실제로 쓰는 글자 범위만** 내려받는다.
woff2는 내부 압축이 이미 걸려 있어서(위 표의 압축 = X) gzip으로는 줄일 수 없고 **서브셋이
유일한 레버**다.

> [!warning] 확인 필요
> 서브셋으로 바꿨을 때 실제로 몇 KB가 되는지는 **재지 않았다.** 이 Task는 측정 단계라 FE 코드를
> 고치지 않았다. 한글 페이지가 쓰는 음절이 어느 `unicode-range` 조각에 흩어지는지에 따라
> 결과가 크게 달라질 수 있다.

### 4.2 좌석맵의 FCP 251ms는 "빨라서"가 아니다

| 페이지 | FCP 중앙값 | LCP 중앙값 | 회차 산포(FCP) |
|---|---|---|---|
| 좌석맵 (B) | **250.5 ms** | 430.5 ms | 657% (이중분포) |
| 홈 (B) | **1,890 ms** | 2,090 ms | 0.2% |
| 공연 상세 (B) | **1,893 ms** | 2,212 ms | 0.1% |

좌석맵이 홈보다 FCP가 7.5배 빠른데, 좌석맵은 문서가 **20배 크다**(659KB vs 45KB). 뒤집힌 이유는
좌석맵의 첫 콘텐츠가 **글자가 아니라 `<rect>` 2,006개**라서다. 홈·상세는 첫 콘텐츠가 텍스트다.

즉 **좌석맵은 같은 폰트 비용을 똑같이 내면서 FCP·LCP 지표에만 안 잡힌다.** 좌석맵만 보고
"FE는 괜찮다"고 판단하면 안 된다는 뜻이고, 이번 측정에서 홈·상세를 같이 잰 값이 있어서 그게
드러났다.

### 4.3 FCP를 움직이는 건 서버가 아니라 전송량이다

같은 홈 페이지를 스로틀만 끄고 재면:

| 조건 | FCP | LCP | SI | TTFB |
|---|---|---|---|---|
| desktop 시뮬 (10,240 kbps) | 1,890 ms | 2,090 ms | 1,890 ms | 6 ms |
| **스로틀 없음** (`--throttling-method=provided`) | **80 ms** | **80 ms** | 82 ms | 7 ms |

23배 차이가 전부 전송 구간에서 나온다. 서버 응답(TTFB 6~7ms)과 렌더 비용(`bootup-time` 0ms,
`mainthread-work-breakdown` 134ms)은 어느 쪽에서도 지표를 못 움직인다. **FE 개선의 레버는
전송량 한 곳**이라는 뜻이다.

> [!warning] 확인 필요
> **desktop 시뮬에서 FCP가 250ms와 1,890ms로 갈리는 메커니즘을 확정하지 못했다.** 폰트
> 2,009KB를 10,240kbps로 받으면 1.57초인데 FCP 차이가 1,640ms라 수치가 잘 맞고, 그래서 처음엔
> "폰트가 FCP를 막는다"고 봤다. 그런데 두 가지가 그 설명과 어긋난다 —
> ① 패키지 CSS에 **`font-display: swap`이 이미 선언돼 있다.** 그러면 텍스트는 대체 폰트로
> 즉시 그려져야 하고 폰트가 FCP를 막을 이유가 없다.
> ② mobile 프리셋은 1,638.4kbps라 같은 폰트에 **약 9.8초**가 필요한데 FCP가 914ms로 나왔다.
> "FCP가 폰트 바이트를 기다린다"는 모델이면 설명이 안 된다.
> 확정된 것은 "전송량이 지표를 지배한다"와 "폰트가 전송량의 88%다"까지다. 둘을 인과로 이으려면
> 폰트만 바꿔서 다시 재야 하고 그건 개선 Task의 일이다.

### 4.4 좌석 배열이 두 번 실린다 — 숫자로

좌석맵 문서 659.24KB의 내부 구성(A·B 양 군 동일):

| 항목 | 크기 | 비고 |
|---|---|---|
| 인라인 RSC | **304.97 KB** | `<script>` 9개 / 전체의 46.3% |
| 그 외 인라인 script | 1.69 KB | |
| SSR 마크업 등 나머지 | **352.58 KB** | 좌석 `rect` 2,006개 (선택 가능 800개) |
| 합계 | 659.24 KB | |

같은 좌석 데이터가 **서버가 그린 SVG 마크업**과 **클라이언트가 복원할 RSC 페이로드**로 각각
들어 있다. `SeatSelector`가 Client Component라서 `seats` 배열 전체가 직렬화되기 때문이다
(`app/(main)/performances/[id]/schedules/[scheduleId]/seats/page.tsx`).

그래서 **BE 응답만 줄여도 브라우저 전송량은 안 줄어든다.** 006·007·008이 넘긴 "237.9KB를
줄여라"는 ② 층만 건드리는 일이고, ③ 층은 구조가 따로 해결해야 한다.

### 4.5 압축이 구간마다 정반대다

| 구간 | `Accept-Encoding` | 크기 | `content-encoding` |
|---|---|---|---|
| ② BE → Next | `gzip, deflate, br` | **240.12 KB** | (없음) |
| ② BE → Next | `identity` | **240.12 KB** | (없음) |
| ③ Next → 브라우저 | (브라우저 기본) | 659.24 → **32.7 KB** | 있음 (20:1) |

**BE 응답은 비압축이 확정됐다** — 헤더를 줘도 안 줘도 바이트가 같고 `content-encoding`이 없다.
계획 단계에서 `nginx/nginx.conf`에 `gzip` 지시자가 0건인 걸 보고 "237.9KB가 비압축으로 나갈
것"이라 봤는데, **절반만 맞았다.** nginx가 압축하지 않는 건 사실이지만 Next.js가 자체적으로
압축해서(`compress` 기본값 true) ③ 층은 이미 20:1로 줄어 있다.

즉 "gzip이 가장 싼 레버"는 **② 층에만** 남아 있다. 240KB가 nginx 1홉 안에서 왕복하는 비용이고,
브라우저와는 무관하다.

### 4.6 600만 행은 FE 지표에 보이지 않는다

| 항목 | A군 (2,000행) | B군 (6,026,894행) |
|---|---|---|
| 좌석맵 문서 바이트 | **675,063 B** | **675,063 B** |
| 문서 디코드 | 659.24 KB | 659.24 KB |
| 인라인 RSC | 304.97 KB | 304.97 KB |
| 층 ② BE 응답 | 240.12 KB | 240.12 KB |
| FCP 중앙값 | 251.5 ms | 250.5 ms |
| LCP 중앙값 | 456.5 ms | 430.5 ms |
| SI 중앙값 | 375 ms | 377 ms |
| 총 전송 바이트 | 2,331,326 | 2,331,136 (차이 0.1%) |
| 클릭 commit 중앙값 | 6.95 ms | 7.2 ms |
| `seatmap.build` Timer | 6.230 ms/req | 5.063 ms/req |

**바이트는 완전히 같고 지표는 산포 안에서 같다.** 좌석맵 응답 크기는 "회차당 좌석 수"에서만
나오고 회차 개수와 무관하므로 당연한 결과지만, 당연한 걸 재 두면 **이후 FE 측정에 600만 행
시딩(1분 58초)이 불필요하다**는 근거가 된다. 축소 시드는 0.7초다.

Timer가 B군에서 오히려 1.17ms 빠른데, 표본 20개라 노이즈 범위로 본다. 판단하지 않는다.

### 4.7 측정 장치가 신호보다 7.5배 컸다

| 측정 경로 | 값 | 표본 |
|---|---|---|
| 페이지 내부 시계 (`MutationObserver`) — commit | **6.95 ms** | 20 |
| 페이지 내부 시계 — frame | 16.7 ms | 20 |
| `page.click()` + `expect(aria-checked)` | **52 ms** | 5 |

Playwright API로 쟀으면 **7.5배 과대 측정**했을 것이다. 004의 "5~8ms"가 commit 범위
(6.4~7.9ms)와 일치하니 그쪽도 내부 시계 계열을 본 값으로 보이지만, 방법이 적혀 있지 않아
확인할 수는 없다. 이번부터는 3.2절의 계약이 코드에 박혀 있다.

### 4.8 선택 깊이별 클릭 비용이 평탄하다 — 개선 후보 하나가 근거를 잃었다

| 선택 좌석 수 | commit (A군) | commit (B군) |
|---|---|---|
| 0 → 1 | 6.9 ms | 7.1 ms |
| 1 → 2 | 7.2 ms | 7.0 ms |
| 2 → 3 | 6.7 ms | 6.9 ms |
| 3 → 4 | 7.1 ms | 6.6 ms |

`components/performance/seat-map.tsx`는 선택 가능 레이어에 `memo`가 없고, 좌석마다
`selectedSeatIds.includes(seat.id)`로 O(n)을 돈다. 그래서 "선택이 쌓일수록 느려질 것"을 기대하고
깊이별로 쪼개 쟀는데 **증가가 없다.**

선택 상한이 4라서다(BE `@Size(max = 4)`). `includes()`가 도는 배열 길이는 최대 4이고,
2,000석을 다시 그리는 비용이 그걸 완전히 가린다. **`memo` 추가와 `Set` 전환은 이 규모에서
측정 가능한 이득이 없다** — 개선 Task 후보에서 내린다.

### 4.9 폰트가 지배하면 바이트 무게를 무결성 검사로 쓸 수 없다

홈 1차 측정 3회 중 1회가 오염됐다. `/api/v1/performances`가 502를 받아 홈이 **데이터 없이**
렌더됐는데, 그 회차의 지표가 이랬다.

| | FCP | 총 전송 바이트 | 문서 디코드 |
|---|---|---|---|
| 정상 회차 | 1,891 ms | 2,314,946 | **44.96 KB** |
| 오염 회차 | 1,892 ms | 2,300,644 | **27.26 KB** |

**FCP가 1ms 차이고 총 바이트는 0.6% 차이다.** 폰트 2MB가 분모를 지배해서 "공연 카드가 통째로
빠진 페이지"와 정상 페이지가 구분되지 않는다. 걸러낸 건 **문서 디코드 크기**였다(44.96 vs
27.26KB, 17.7KB 차이).

측정 스크립트에 회차별 무결성 검사를 넣어야 한다는 뜻이고, 그 검사는 **총 바이트나 점수가
아니라 문서 본문 크기**여야 한다. 홈은 4회로 다시 재서 4회 모두 44.96KB를 확인했다.

---

## 5. 실제로 만든 것

### 5.1 `load-test/sql/01b-small-catalog.sql` (신규)

`01-bulk-catalog.sql`의 변종. **코드 2줄 + 주석 2줄만** 다르다(`diff --strip-trailing-cr`로 확인).

| 줄 | `01` | `01b` |
|---|---|---|
| 63 | `generate_series(1, 1000)` (공연) | `generate_series(1, 1)` |
| 82 | `generate_series(1, 3)` (회차) | `generate_series(1, 1)` |

`01`을 고치지 않고 따로 둔 이유는 006·007·008의 재현 방법 블록이 `01`을 그대로 참조하기
때문이다. 헤더에 **"좌석 2,000석은 `01`과 같게 유지해야 한다"**를 명시했다 — 2.2절의 측정 횟수
축소가 전부 거기 걸려 있다. 공연장 이름도 `01`과 같게 둬야 `99-teardown`이 지운다.

### 5.2 `load-test/sql/99-teardown.sql` (수정) — 삭제 전용 임시 인덱스

6절 첫 항목의 해법이다. 트랜잭션 안에서 인덱스 7개를 만들고 **커밋 전에 전부 DROP**한다.
다른 세션에는 보이지 않고, 실패해 롤백되면 같이 사라진다.

```sql
CREATE INDEX tmp_teardown_ss_reservation ON schedule_seats (reservation_id);
CREATE INDEX tmp_teardown_ss_seat        ON schedule_seats (seat_id);
CREATE INDEX tmp_teardown_ss_grade       ON schedule_seats (ticket_grade_id, schedule_id);
CREATE INDEX tmp_teardown_rs_sseat       ON reservation_seats (schedule_seat_id);
CREATE INDEX tmp_teardown_res_schedule   ON reservations (schedule_id);
CREATE INDEX tmp_teardown_res_grade      ON reservations (ticket_grade_id, schedule_id);
CREATE INDEX tmp_teardown_perf_venue     ON performances (venue_id);
```

**영구 추가하지 않는 이유**: `V4__create_seat_tables.sql:38`이 `idx(reservation_id)`를 의도적으로
뺐다. 모든 선점/해제가 그 컬럼을 UPDATE하므로 인덱스가 붙으면 HOT 업데이트가 깨진다
(`docs/ERD.md` 2절). **런타임 쓰기 성능을 위해 일회성 삭제 비용을 받는 트레이드오프**이고,
이 스크립트가 그 비용을 지불하는 자리다.

커밋 뒤에 `VACUUM (FULL, ANALYZE)`도 붙였다. DELETE는 디스크를 반납하지 않아서
`schedule_seats`가 1,037MB로 남고, 다음 측정이 그 bloat 위에서 돈다. 지금은 **2,728kB**로 떨어진다.

### 5.3 `tikkit-front/e2e/perf/` (신규 3파일)

| 파일 | 역할 |
|---|---|
| `target.ts` | `E2E_BASE_URL` 강제, 좌석맵 id를 환경변수로 받음, 표 출력·중앙값 |
| `payload-bytes.spec.ts` | 층 ②·③ 분해, HTML/인라인 RSC 분리, 진입 경로 2종 |
| `seat-interaction.spec.ts` | 3.2절 계약대로 클릭 → DOM 반영, 측정 장치 비용 비교 |

좌석맵 URL을 코드에 박지 않았다 — teardown 후 시퀀스가 이어져서 공연·회차 id가 매번 다르다
(A군 1009/3021/24071, B군 1010/3022/24072). 시딩 직후 psql로 뽑아 환경변수로 넘긴다.

`E2E_BASE_URL`이 없거나 `:3000`을 포함하면 **스펙이 직접 실패시킨다.** `playwright.config.ts`의
기본값 `localhost:3000`으로 조용히 떨어지면 nginx를 건너뛴 다른 서버를 재게 되고, 그건 004
10절 "함정 4"(e2e가 `next build` 안 한 옛 번들을 보고 있었다)와 같은 종류의 사고다.

### 5.4 기각한 것

- **`next/image` 적용** — 적용 대상 0개다. ROADMAP 항목 자체가 무효다(1절). 포스터 자산이
  도입된 뒤의 일이다.
- **`playwright-lighthouse`** — 호스트 CLI로 충분하고, 버전 고정이 `package.json` 한 줄로 끝난다.
- **`@next/bundle-analyzer`** — Script 14개 186KB는 폰트 2,009KB에 비하면 2순위다. 개선 Task로.
- **`web-vitals` 계측** — 측정 단계에 앱 코드를 안 건드린다는 범위 결정.
- **선택 레이어 `memo` + `Set` 전환** — 4.8절에서 근거를 잃었다.
- **`schedule_seats.reservation_id` 영구 인덱스** — 5.2절. V4의 설계 결정을 뒤집는 일이고,
  teardown은 임시 인덱스로 해결된다.

---

## 6. 관문

### 6.1 `99-teardown.sql`이 완주할 수 없었다 — 3시간을 먹은 지점

맥북에서 측정을 포기한 이유이자 이번에도 처음 3시간을 먹은 지점이다. 600만 행을 지우는
teardown이 **느린 게 아니라 끝나지 않는 상태**였다.

Postgres는 FK 자식 컬럼에 인덱스를 자동으로 만들지 않는다. 부모 행을 지울 때마다 "이걸
참조하는 자식이 남아있나"를 확인하는데, 그 컬럼에 인덱스가 없으면 자식 테이블을 **풀스캔**한다.
부모가 N행이면 N번이다. 로그에 그대로 찍혔다.

```
ERROR:  canceling statement due to user request
CONTEXT:  SQL statement "SELECT 1 FROM ONLY "public"."schedule_seats" x
                         WHERE $1 OPERATOR(pg_catalog.=) "reservation_id" FOR KEY SHARE OF x"
```

인덱스를 못 타는 FK가 5개였다.

| 부모 (지우는 쪽) | 자식 | 자식 컬럼 | 자식 크기 | 실측 |
|---|---|---|---|---|
| `reservations` (80,814행) | `schedule_seats` | `reservation_id` | 599 MB | **41분에도 미완** |
| `schedule_seats` (6,000,000행) | `reservation_seats` | `schedule_seat_id` | 15 MB | **10분에도 미완** |
| `seats` (2,000행) | `schedule_seats` | `seat_id` | 599 MB | 미완 |
| `ticket_grades` (12,000행) | `schedule_seats` | `ticket_grade_id, schedule_id` | 599 MB | — |
| `schedules` (3,000행) | `reservations` | `schedule_id` | 12 MB | — |
| `venues` (1행) | `performances` | `venue_id` | 304 kB | — |

두 가지가 겹쳐 있었다.

**(1) 복합 유니크가 있어도 선두 컬럼이 아니면 못 탄다.** `reservation_seats.schedule_seat_id`에는
`uk_reservation_seats_reservation_seat (reservation_id, schedule_seat_id)`가 있는데 두 번째
컬럼이라 쓸 수 없다. `schedule_seats.seat_id`도 `uk_schedule_seats_schedule_seat (schedule_id,
seat_id)`의 두 번째다. **"인덱스 있으니 괜찮다"는 착각이 여기서 난다.**

**(2) 트랜잭션 안의 DELETE는 테이블을 작게 만들지 않는다.** 먼저 지운 행도 dead tuple로 힙에
남아서 뒤따르는 풀스캔은 여전히 모든 페이지를 읽는다.

그리고 이건 **레포가 이미 밟고 문서화한 함정이었다.** `98-reset-reservations.sql`이 원인·증상·
대응까지 길게 적어놓고 마지막에 이렇게 덧붙였다.

> 완전 삭제는 99-teardown.sql이 한다(그쪽은 `schedule_seats`를 먼저 지워서 이 문제를 피한다).

(2) 때문에 **그 문장이 틀렸다.** 검증 없이 적힌 안심 문장 한 줄이 주석이 아예 없는 것보다
비쌌다. 해당 주석을 정정했다.

인덱스 7개를 만들고 다시 돌리니 600만 행이 **58.2초**에 끝났다. 인덱스 생성 자체는 600만 행에서 0.37~1.6초라 풀스캔 한 번보다 싸다. 축소 시드(2,000행) 제거는 0.471초다.

| | 수정 전 | 수정 후 |
|---|---|---|
| `99-teardown.sql` (600만 행) | 3시간+ 미완 | **58.2초** |
| `99-teardown.sql` (축소 시드 2,000행) | 측정 안 함 | 0.471초 |

### 6.2 `error.log` grep이 실행 불가능한 지시였다

계획에 "측정 후 nginx `error.log`에서 `buffered to a temporary file`을 grep"을 **필수 체크
항목**으로 넣어뒀다. 그런데 컨테이너 안에서는 이렇게 생겼다.

```
access.log -> /dev/stdout
error.log  -> /dev/stderr
```

`grep ... /var/log/nginx/error.log`는 `/dev/stderr`를 읽으므로 **항상 0건을 돌려준다.** 빈 파일에서
나온 0건은 증거가 아니라 측정 실패인데, 모양이 같아서 그냥 통과했을 수 있었다.
`docker compose logs nginx`로 봐야 한다. 재현 블록을 그렇게 고쳤다.

고쳐서 실제로 재니 **57만 줄 로그에 0건**이었다. `proxy_buffers 16 16k`(=256KB)가 240KB 응답을
담았다는 뜻이고, 여유가 16KB뿐이라 응답이 조금만 커지면 디스크로 떨어진다.

### 6.3 nginx가 낡은 upstream IP를 물고 502를 뱉는다

측정 시작 직후 `/`가 502를 줬다. 그런데 `docker compose ps`는 **6개 컨테이너 전부 healthy**였고
nginx 컨테이너 안에서 `wget http://frontend:3000/`은 200을 줬다.

`nginx/nginx.conf:16`에 답이 적혀 있다 — "upstream의 호스트명은 nginx 기동(또는 reload)
시점에 IP로 고정된다". frontend 컨테이너 IP가 바뀌었는데 nginx는 옛 IP를 쥐고 있었다.
`restart nginx`로 해결됐다.

008의 재현 방법도 같은 함정을 이미 적어뒀다("안 하면 nginx가 옛 IP를 물고 502를 뱉는다 —
005의 함정"). 새 발견은 아니지만, **헬스체크가 전부 통과하는데도 502가 나온다**는 점이
매번 사람을 속인다. 컨테이너 헬스체크는 자기 자신만 보고, nginx가 쥔 IP는 아무도 검사하지 않는다.

그래서 2.4절 리셋 표에 **nginx restart**를 넣었다. 원래 계획에는 backend restart만 있었다.

### 6.4 readiness 루프가 BE를 10초간 내려놓는다

backend 재시작 후 준비를 기다리려고 이렇게 돌렸다.

```bash
until curl -sf -o /dev/null http://localhost/api/v1/performances; do :; done
```

`sleep` 없는 타이트 루프다. 그 1분 동안 nginx 로그에 **502가 285건** 찍혔다.

`upstream tikkit_backend`에 `max_fails`/`fail_timeout`이 명시돼 있지 않아 nginx 기본값
**`max_fails=1`, `fail_timeout=10s`**가 적용된다. **요청 한 번만 실패해도 그 BE가 10초간
제외되고**, BE 2대가 동시에 제외되면 그 10초는 전부 `no live upstreams`로 502다. 준비 전 BE를
초당 수십 번 때리면 둘 다 계속 내려가 있다.

측정 전이라 수치에는 영향이 없었지만, **측정 중에도 산발적으로 같은 일이 생겼고 그게 4.9절의
오염 회차를 만들었다.** `nginx.conf`는 이 Task 범위 밖이라 고치지 않았고 10절에 넘긴다.
재현 블록의 루프에는 `sleep 1`을 넣었다.

### 6.5 Lighthouse FCP가 이중분포라 3회로는 수렴 판정이 안 된다

좌석맵 FCP 6회: `251 / 1896 / 249 / 250 / 1895 / 252` (A군). 산포 655%다.

008이 쓴 "단조 하강이면 워밍업 미수렴" 판정이 여기서는 안 먹는다. 단조 하강이 아니라 **두
값 사이를 오간다.** 3회만 쟀으면 어느 쪽이 중앙값이 되는지가 운에 달렸고, 운 나쁘면 "FCP
1,896ms"를 기준선으로 적었을 것이다. 6회로 늘리고 **이중분포 자체를 기록**하는 쪽을 골랐다.

B군도 같은 현상이 1/6 비율로 재현됐다. 원인은 4.3절의 `확인 필요`로 남겼다.

### 6.6 `npm` 스크립트에 환경변수 접두사를 쓸 수 없다

처음에 `package.json`에 이렇게 썼다.

```json
"perf": "E2E_BASE_URL=http://localhost playwright test --project=perf"
```

Windows에서 npm은 스크립트를 `cmd.exe`로 돌리기 때문에 이 접두사 문법이 깨진다. 접두사를
떼고 `E2E_BASE_URL` 강제는 스펙이 하게 했다(5.3절). 셸에서 `E2E_BASE_URL=... npm run perf`로
넘기는 건 정상 동작한다 — 접두사가 `package.json` 안에 있을 때만 문제다.

---

## 7. 수치

### 7.1 축소 시드 설계의 검증 — A/B 바이트 일치

| 항목 | A군 (2,000행) | B군 (6,026,894행) | 일치 |
|---|---|---|---|
| 좌석맵 문서 (curl) | 675,063 B | 675,063 B | **완전 일치** |
| 문서 디코드 | 659.24 KB | 659.24 KB | 일치 |
| 인라인 RSC | 304.97 KB | 304.97 KB | 일치 |
| SSR 마크업 등 | 352.58 KB | 352.58 KB | 일치 |
| 층 ② BE 응답 | 240.12 KB | 240.12 KB | 일치 |
| 좌석 `rect` | 2,006개 (선택 가능 800) | 2,006개 (선택 가능 800) | 일치 |

2.2절의 전제가 성립한다. 이후 FE 측정은 축소 시드(0.7초)로 충분하다.

### 7.2 Lighthouse desktop — 좌석맵

| run | 점수 | FCP | LCP | TBT | CLS | SI | TTFB | 총 바이트 | bootup | mainthread |
|---|---|---|---|---|---|---|---|---|---|---|
| A-1 | 100 | 251 | 411 | 0 | 0.0277 | 372 | 8 | 2,331,036 | 61 | 180 |
| A-2 | 80 | 1896 | 2136 | 0 | 0.0277 | 1896 | 7 | 2,331,231 | 57 | 162 |
| A-3 | 100 | 249 | 454 | 0 | 0.0277 | 373 | 6 | 2,331,500 | 57 | 162 |
| A-4 | 100 | 250 | 450 | 0 | 0.0277 | 377 | 8 | 2,331,639 | 68 | 175 |
| A-5 | 79 | 1895 | 2215 | 0 | 0 | 1895 | 7 | 2,330,995 | 67 | 170 |
| A-6 | 100 | 252 | 459 | 0 | 0.0277 | 369 | 7 | 2,331,421 | 59 | 173 |
| **A 중앙값** | 100 | **251.5** | **456.5** | **0** | 0.0277 | 375 | 7 | 2,331,326 | 60 | 171.5 |
| A 산포 | 21% | 655% | 395% | 0% | 100% | 407% | 29% | 0.0% | 18% | 10.5% |
| B-1 | 100 | 252 | 452 | 0 | 0.0277 | 378 | 12 | 2,331,605 | 60 | 174 |
| B-2 | 100 | 249 | 409 | 0 | 0.0277 | 379 | 10 | 2,330,787 | 10 | 164 |
| B-3 | 100 | 250 | 450 | 0 | 0.0277 | 368 | 7 | 2,330,689 | 59 | 177 |
| B-4 | 100 | 250 | 410 | 0 | 0.0277 | 369 | 6 | 2,331,983 | 59 | 169 |
| B-5 | 79 | 1894 | 2174 | 0 | 0 | 1894 | 6 | 2,330,459 | 60 | 161 |
| B-6 | 100 | 251 | 411 | 0 | 0.0277 | 376 | 10 | 2,331,486 | 57 | 165 |
| **B 중앙값** | 100 | **250.5** | **430.5** | **0** | 0.0277 | 377 | 8.5 | 2,331,136 | 59 | 167 |
| B 산포 | 21% | 657% | 410% | 0% | 100% | 405% | 71% | 0.1% | 85% | 9.6% |

**FCP·LCP·SI·CLS·bootup·TTFB는 산포가 20%를 넘어 판단 불가다**(6.5절). TBT는 6회 모두 0,
총 바이트는 산포 0.1%로 신뢰할 수 있다.

### 7.3 Lighthouse desktop — 홈 / 상세

| run | 점수 | FCP | LCP | TBT | CLS | SI | TTFB | 총 바이트 | mainthread | 문서 디코드 |
|---|---|---|---|---|---|---|---|---|---|---|
| 홈 r1 | 80 | 1891 | 2091 | 0 | 0.0034 | 1891 | 6 | 2,314,793 | 136 | 44.96 KB |
| 홈 r2 | 80 | 1888 | 2088 | 0 | 0.0034 | 1888 | 5 | 2,315,087 | 131 | 44.96 KB |
| 홈 r3 | 80 | 1891 | 2091 | 0 | 0.0034 | 1891 | 6 | 2,317,613 | 137 | 44.96 KB |
| 홈 r4 | 80 | 1889 | 2089 | 0 | 0.0034 | 1889 | 8 | 2,315,635 | 132 | 44.96 KB |
| **홈 중앙값** | 80 | **1890** | **2090** | **0** | 0.0034 | 1890 | 6 | 2,315,361 | 134 | |
| 홈 산포 | 0.0% | **0.2%** | **0.1%** | 0% | 0.0% | 0.2% | 50% | 0.1% | 4.5% | |
| 상세 1 | 79 | 1892 | 2212 | 0 | 0.0004 | 1892 | 9 | 2,304,725 | 114 | 34.30 KB |
| 상세 2 | 80 | 1893 | 2093 | 0 | 0.0013 | 1893 | 7 | 2,304,803 | 119 | 34.30 KB |
| 상세 3 | 79 | 1894 | 2214 | 0 | 0.0004 | 1894 | 7 | 2,304,545 | 122 | 34.30 KB |
| **상세 중앙값** | 79 | **1893** | **2212** | **0** | 0.0004 | 1893 | 7 | 2,304,725 | 119 | |
| 상세 산포 | 1.3% | **0.1%** | 5.5% | 0% | 225% | 0.1% | 29% | 0.0% | 6.7% | |

**홈·상세는 좌석맵과 달리 FCP 산포가 0.1~0.2%로 완전히 수렴한다.** 이중분포가 없다.
TTFB와 CLS는 절대값이 작아 비율 산포가 크게 나오지만 수치 자체는 1ms / 0.001 단위다.

### 7.4 보조 측정 (각 n=1 — 9절 한계 참조)

| 조건 | 점수 | FCP | LCP | TBT | CLS | SI | TTFB | 총 바이트 | bootup | mainthread |
|---|---|---|---|---|---|---|---|---|---|---|
| 좌석맵 mobile (4x CPU, 1,638 kbps) | 99 | 914 | 2114 | **22** | 0.0463 | 1113 | 9 | 2,327,200 | **271** | **678** |
| 좌석맵 Redis 캐시 콜드 | 80 | 1896 | 2136 | 0 | 0.0011 | 1896 | 7 | 2,332,571 | 60 | 184 |
| 홈 스로틀 없음 | 100 | **80** | **80** | 0 | 0.0034 | 82 | 7 | 2,314,748 | 4 | 133 |

**캐시 콜드가 웜과 구분되지 않는다**(TTFB 7ms vs 중앙값 8.5ms). 좌석맵이 캐시되지 않기
때문이고, 008이 좌석맵 캐싱을 기각한 결론과 일치한다. "600만 행이 FE에 안 보인다"가
캐시 히트로 생긴 착시가 아니라는 확인이기도 하다.

mobile은 CPU 4배 때문에 `mainthread`가 134 → 678ms, `bootup`이 0 → 271ms로 뛰고 TBT가
처음으로 0을 벗어난다(22ms). 그래도 "Good" 기준(200ms) 안이다.

### 7.5 층 ① BE 조립 시간 — `tikkit.seatmap.build` 차분

| 군 | 인스턴스 | count | sum | 평균 |
|---|---|---|---|---|
| A | backend1 | +10 | +0.059434261 s | 5.943 ms |
| A | backend2 | +10 | +0.065170242 s | 6.517 ms |
| **A** | **합계** | **+20** | **+0.124604503 s** | **6.230 ms** |
| B | backend1 | +10 | +0.049551088 s | 4.955 ms |
| B | backend2 | +10 | +0.051717023 s | 5.172 ms |
| **B** | **합계** | **+20** | **+0.101268111 s** | **5.063 ms** |

006의 좌석맵 p95 6.72ms와 같은 규모다. 즉 **좌석맵 요청 시간은 거의 전부 조립 구간**이고,
nginx 2홉과 Next 처리는 그 위에 얇게 얹힌다. 008 4.3절의 "응답의 1.4~2.2ms가 캐시 밖"과는
측정 방식이 달라(그쪽은 캐시 유무 분해) 직접 비교하지 않는다.

### 7.6 시딩·정리 비용

| 작업 | 규모 | 시간 |
|---|---|---|
| `01b-small-catalog.sql` | 공연 1 / 회차 1 / 좌석 2,000 | 0.349 s |
| `02` (A군) | `schedule_seats` 2,000 | 0.356 s |
| `01-bulk-catalog.sql` | 공연 1,000 / 회차 3,000 | 0.448 s |
| `02` (B군) | `schedule_seats` 6,000,000 | **1 m 58 s** (10만 행당 1.14 s) |
| `99-teardown.sql` (수정 후) | 600만 행 제거 + VACUUM FULL | **58.2 s** |
| `99-teardown.sql` (수정 후) | 축소 시드 2,000행 제거 + VACUUM FULL | 0.471 s |
| `99-teardown.sql` (수정 전) | 600만 행 제거 | **3시간+ 미완** |
| Lighthouse 1회 | | 11.7 s |

006이 `02`를 70초로 기록했는데 이 PC에서는 118초다. **006의 시딩 시간은 호스트에 따라 1.7배
차이 난다**는 뜻이고, teardown 비용은 006이 아예 기록하지 않았다.

`VACUUM (FULL, ANALYZE)` 후 `schedule_seats`가 **1,037 MB → 2,728 kB**로 떨어졌다.

### 7.7 검증 — 회귀 없음

| 항목 | 결과 |
|---|---|
| `playwright test --project=e2e` | **3 passed** (prod 스택 대상) |
| `--project=e2e --list`에 `perf` 포함 여부 | **0건** (의도대로 제외) |
| `--project=screenshots --list` | 1건 (영향 없음) |
| `--project=perf --list` | 3건 |
| `npx tsc --noEmit` | 0 |
| BE 테스트 | **돌리지 않았다** — `git status`에 `tikkit-back/` 변경 0건이라 검증할 대상이 없다 |

---

## 9. 한계

- **4.1~4.3을 인과로 잇지 못했다.** "폰트가 전송량의 88%"와 "전송량이 지표를 지배한다"는
  각각 확정됐지만, "폰트를 줄이면 FCP가 얼마나 내려가는가"는 재지 않았다. `font-display: swap`과
  mobile의 반례(4.3절 `확인 필요`) 때문에 단순 모델이 성립하지 않는다. **폰트만 서브셋으로
  바꿔 다시 재는 것이 개선 Task의 첫 측정이어야 한다.**
- **좌석맵 FCP 이중분포의 원인을 모른다.** 6회 중 1~2회가 1,896ms로 튀는데 두 모드가 왜
  갈리는지 설명하지 못했다. 그래서 좌석맵의 FCP·LCP·SI는 **판단 불가**로 표시했고, 개선
  Task가 이 지표로 before/after를 비교하려면 먼저 이걸 해결해야 한다.
- **mobile과 캐시 콜드가 n=1이다.** 중앙값도 산포도 없다. mobile 수치는 "CPU 4배면 mainthread가
  5배"라는 방향만 읽고 절대값으로 쓰지 않는다.
- **호스트와 측정 대상이 같은 PC다.** 006 2절이 경고한 그대로이고, Lighthouse는 k6보다 이
  오염에 민감하다. 4.9절의 오염 회차와 6.5절의 이중분포 중 일부가 여기서 왔을 수 있다.
- **Lighthouse는 항상 브라우저 캐시 콜드다.** 폰트 2MB는 해시 URL로 immutable 캐시되므로
  **재방문자는 이 비용을 내지 않는다.** 즉 4.1절의 88%는 첫 방문 기준이고, 재방문 비중이 높은
  서비스라면 우선순위가 달라진다. 재방문 시나리오는 재지 않았다.
- **목록·상세를 B군에서만 쟀다.** 2.2절 설계상 의도적이지만, "공연 1개면 홈이 더 빠른가"는
  답이 없다. 홈 문서가 44.96KB라 바뀔 여지가 작다고 봤을 뿐 실측은 아니다.
- **`rsc` 헤더 수치는 상한이다.** `next-router-state-tree`를 보내지 않아 전체 페이로드가 오고,
  실제 링크 클릭이 받는 양은 이보다 작다. 실제 값을 재려면 목록에서 링크를 클릭하는 경로로
  `?_rsc=` 요청을 잡아야 하는데 이번엔 하지 않았다(직접 진입 659.24KB vs `rsc` 258.95KB).
- **층 ②의 240KB가 왕복 시간에 얼마를 보태는지 모른다.** 바이트만 쟀고, 비압축 → gzip으로
  바꿨을 때의 지연 변화는 재지 않았다. 압축 CPU 비용이 전송 절감을 상쇄할 수도 있다.
- **teardown 임시 인덱스의 런타임 영향을 재지 않았다.** V4가 `idx(reservation_id)`를 뺀 이유인
  HOT 업데이트 손실이 실제로 얼마인지는 이 Task에서 확인하지 않았다. 임시로 만들고 지우는
  방식을 고른 것은 그 설계 결정을 **신뢰했기** 때문이고 검증한 것이 아니다.
- **`no live upstreams`를 근본 해결하지 않았다.** 6.4절. `nginx.conf`가 범위 밖이라 재현
  블록에 `sleep 1`을 넣는 우회만 했다. 측정 중 산발 실패가 계속 생길 수 있다.
- **저부하 단발 측정이다.** 006·008과 달리 동시 부하가 없다. FE 전송량은 부하와 무관하지만
  TTFB와 Timer 값은 포화 구간에서 달라진다 — 이 문서의 ms 값으로 확장성을 말할 수 없다.

---

## 10. 다음 단계

측정 결과가 정한 우선순위다. 전송 바이트 기준으로 줄 세웠다.

1. **폰트 서브셋** (2,009 KB → ?) — `app/globals.css:4`의 `@import`를
   `pretendardvariable-dynamic-subset.css`로 바꾸는 한 줄이다. 전송량의 88%를 건드리는
   가장 싼 레버이고, **바꾸기 전과 후를 같은 조건으로 재는 것이 개선 Task의 첫 작업**이다
   (4.3절 `확인 필요`를 닫는 측정도 여기서 같이 된다).
2. **좌석맵 Suspense 스트리밍** — `Promise.all` 3개를 쪼갠다. 좌석맵은 현재 `loading.tsx`만
   있어서 셋이 다 끝날 때까지 백지다. 1절의 세 ROADMAP 항목 중 유일하게 근거가 살아 있다.
3. **층 ② gzip** (240 KB 비압축) — 4.5절. BE 또는 nginx에서 `/api/` 응답을 압축한다.
   브라우저 전송량과는 무관하고 nginx↔BE 1홉 안의 문제다. 압축 CPU 비용을 같이 재야 한다.
4. **좌석맵 응답 축소** (DTO 필드 축약 / 좌표 배열화 / 구역·등급 필터) — 006·007·008이 넘긴
   숙제. 다만 4.4절대로 **② 층만 줄이면 ③ 층은 그대로다.** 브라우저 전송량을 줄이려면
   `SeatSelector`의 Client Component 경계를 같이 손봐야 한다.
5. **`favicon.ico` 25.7 KB** — `High` 우선순위 비압축이다. 작지만 비용 대비 가장 쉽다.
6. **`apiFetchRaw`의 `cookies()` 무조건 호출 분리** → `revalidate`/`cacheLife` 적용 가능해진다.
   선행 리팩터가 필요해 이번엔 손대지 않았다.
7. **`nginx.conf`의 `max_fails`/`fail_timeout` 명시** — 6.4절. 기본값 1회/10초는 측정과 운영
   둘 다에서 너무 공격적이다. 범위 밖이라 넘긴다.
8. **`next/image`** — 포스터 자산이 도입된 뒤. 현재 적용 대상 0개다.

**하지 않기로 한 것**: 선택 레이어 `memo` + `selectedSeatIds` Set 전환(4.8절에서 근거를 잃었다),
`schedule_seats.reservation_id` 영구 인덱스(5.4절).

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (007 6절). PowerShell이면 생략
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd <repo>

# 1. 스택 기동 — monitoring 프로필은 끈다 (2절)
#    이미 떠 있으면 prometheus/grafana가 살아 있지 않은지 확인한다 (Docker Desktop이 되살린다)
set -a && . ./.env && set +a
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml stop prometheus grafana 2>/dev/null
docker compose -f docker-compose.prod.yml ps
# -> 6개 서비스(frontend/nginx/postgres/backend1/backend2/redis) 전부 healthy

# 2. Lighthouse 버전 확인 — package.json에 정확 버전으로 고정돼 있다
cd tikkit-front && npm ci && npx lighthouse --version
# -> 13.5.0
cd ..

# 3. A군 시딩 (축소). 01과 01b를 공존시키면 조용히 어긋난다 (2.2절)
pg() { docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"; }
pg -f - < load-test/sql/01b-small-catalog.sql
# -> seats 2000 | performances 1 | schedules 1   (0.35초)
pg -f - < load-test/sql/02-bulk-schedule-seats.sql
# -> NOTICE: 완료 — 총 2000 행, 소요 00:00:00.03

# 4. 좌석맵 id는 하드코딩하지 않는다 — teardown 후 시퀀스가 이어져 매번 다르다
pg -At -F$'\t' -c "
SELECT p.id, s.id, tg.id, count(ss.id)
FROM venues v
JOIN performances p ON p.venue_id = v.id
JOIN schedules s ON s.performance_id = p.id
JOIN ticket_grades tg ON tg.schedule_id = s.id
JOIN schedule_seats ss ON ss.ticket_grade_id = tg.id AND ss.schedule_id = s.id
WHERE v.name = '[LOAD] 부하테스트 아레나' AND tg.grade = 'A'
GROUP BY 1,2,3 ORDER BY p.id, s.id LIMIT 1;"
# -> 1009  3021  24071  800     (A등급 800석)
export PERF_PERFORMANCE_ID=1009 PERF_SCHEDULE_ID=3021 PERF_GRADE_ID=24071
URL="http://localhost/performances/$PERF_PERFORMANCE_ID/schedules/$PERF_SCHEDULE_ID/seats?gradeId=$PERF_GRADE_ID&quantity=4"

# 5. 데우기. Lighthouse로 데우지 않는다 (2.3절). TTFB가 평탄해질 때까지
for i in $(seq 1 20); do curl -s -o /dev/null "$URL"; done
for i in $(seq 1 12); do curl -s -o /dev/null -w 'TTFB %{time_starttransfer}s %{size_download}B\n' "$URL"; done
# -> TTFB 0.006~0.009s / 675063B 고정. 단조 하강이면 더 데운다

# 6. Lighthouse. desktop이 주 프리셋이다. 좌석맵은 이중분포라 6회 (6.5절)
OUT=/tmp/lh && mkdir -p $OUT
for i in 1 2 3 4 5 6; do
  npx --prefix tikkit-front lighthouse "$URL" \
    --preset=desktop --only-categories=performance \
    --output=json --output-path="$OUT/A-seatmap-$i.json" \
    --chrome-flags="--headless=new --no-sandbox --disable-gpu" --quiet
done
# -> 1회 약 11.7초. 회차마다 Document의 resourceSize가 659.24KB인지 확인한다 (4.9절)
#    총 바이트나 점수로는 오염을 못 걸러낸다 — 폰트 2MB가 분모를 지배한다

# 7. perf 스펙. E2E_BASE_URL이 없으면 스펙이 실패한다 (5.3절)
cd tikkit-front
E2E_BASE_URL=http://localhost npx playwright test --project=perf --reporter=list
# -> 3 passed. stdout에 표 6개
#    층 ② 240.12KB (Accept-Encoding 유무 동일 = 비압축)
#    문서 659.24KB = 인라인 RSC 304.97 + 그 외 1.69 + 나머지 352.58
#    클릭 commit 중앙값 약 7ms, 측정 장치 비용 약 52ms
cd ..

# 8. 층 ① Timer. Prometheus 서버 없이 컨테이너 안에서 직접 긁는다 (2절)
snap() { for b in backend1 backend2; do
  docker compose -f docker-compose.prod.yml exec -T $b \
    sh -c 'wget -qO- http://localhost:8080/actuator/prometheus' \
  | grep -E '^tikkit_seatmap_build_seconds_(count|sum)' | sed "s/^/$b /"; done; }
snap
for i in $(seq 1 20); do curl -s -o /dev/null "http://localhost/api/v1/schedules/$PERF_SCHEDULE_ID/seats"; done
snap
# -> count 차분 20, sum 차분 약 0.12s => 약 6ms/req

# 9. B군으로 전환. nginx도 재시작해야 한다 (6.3절)
pg -f - < load-test/sql/99-teardown.sql
# -> venue_left 0 | schedule_seats_left 26894 | schedule_seats_size 2728 kB
#    느리면 [0/3]의 임시 인덱스가 빠진 것이다 (6.1절)
docker compose -f docker-compose.prod.yml exec -T redis redis-cli FLUSHALL
docker compose -f docker-compose.prod.yml restart backend1 backend2
docker compose -f docker-compose.prod.yml restart nginx
until curl -sf -o /dev/null http://localhost/api/v1/performances; do sleep 1; done
# -> sleep 1이 중요하다. 타이트 루프는 BE를 10초간 내려놓는다 (6.4절)

pg -f - < load-test/sql/01-bulk-catalog.sql
pg -f - < load-test/sql/02-bulk-schedule-seats.sql
# -> NOTICE: 완료 — 총 6000000 행, 소요 00:01:57
# 4~8번을 B군 id로 반복한다 (홈 http://localhost/ 4회, 상세 /performances/<id> 3회 포함)

# 10. nginx 버퍼링 확인. error.log 파일을 grep하면 안 된다 — /dev/stderr다 (6.2절)
docker compose -f docker-compose.prod.yml logs nginx | grep -c "buffered to a temporary file"
# -> 0  (proxy_buffers 256KB가 240KB 응답을 담았다. 여유는 16KB뿐)
docker compose -f docker-compose.prod.yml logs nginx | grep -c "no live upstreams"
# -> 0에 가까워야 한다. 크면 9번의 sleep을 빼먹은 것이다

# 11. 인덱스를 못 타는 FK 다시 뽑기 — 스키마가 바뀐 뒤 99-teardown을 손볼 때 쓴다 (6.1절)
pg -c "
WITH fk AS (SELECT c.conrelid AS child_oid, c.confrelid::regclass AS parent, c.conkey
            FROM pg_constraint c WHERE c.contype = 'f'), r AS (
SELECT fk.parent::text AS parent, fk.child_oid::regclass::text AS child,
  (SELECT string_agg(a.attname, ',' ORDER BY x.ord)
     FROM unnest(fk.conkey) WITH ORDINALITY AS x(att, ord)
     JOIN pg_attribute a ON a.attrelid = fk.child_oid AND a.attnum = x.att) AS child_cols,
  (SELECT string_agg(i.indexrelid::regclass::text, ', ') FROM pg_index i
    WHERE i.indrelid = fk.child_oid
      AND (i.indkey::int2[])[0:array_length(fk.conkey,1)-1] @> fk.conkey
      AND (i.indkey::int2[])[0:array_length(fk.conkey,1)-1] <@ fk.conkey) AS idx,
  pg_size_pretty(pg_relation_size(fk.child_oid)) AS child_size
FROM fk)
SELECT parent, child, child_cols, COALESCE(idx, '>>> 없음: 풀스캔 <<<') AS usable_index, child_size
FROM r ORDER BY (idx IS NULL) DESC, parent;"
# -> '없음'으로 나온 FK마다 99-teardown.sql [0/3]에 임시 인덱스가 있어야 한다

# 12. 회귀 — perf 스펙이 e2e에 끌려오지 않아야 한다
cd tikkit-front
npx playwright test --project=e2e --list | grep -c perf     # -> 0
E2E_BASE_URL=http://localhost npx playwright test --project=e2e --reporter=list
# -> 3 passed
npx tsc --noEmit                                            # -> 출력 없음
```
