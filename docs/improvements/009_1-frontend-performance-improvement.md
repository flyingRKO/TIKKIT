# 009_1. FE 전송량 축소 — `@import` 한 줄로 전송량 77%, FCP 67%가 내려갔다 (Task 030_1)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 030_1 (Phase 8: 성능 개선) — **개선 단계**. 측정은 [009](./009-frontend-performance.md) |
| 대상 코드 | `tikkit-front/app/globals.css`(1줄), `tikkit-front/app/icon.svg`(신규)·`favicon.ico`(삭제), `tikkit-front/scripts/lighthouse-runs.mjs`(신규), `package.json`·`.gitignore` |
| before 기준선 | **이번에 다시 쟀다.** 009 7.2·7.3 표는 교차 확인용으로만 썼다 (2.2절) |
| 결론 | **폰트 `@import`를 서브셋으로 바꾼 한 줄이 전송량을 76.8%, FCP를 67.4% 줄였다**(홈 기준). 009가 "확인 필요"로 남긴 4.3절(폰트 → FCP 인과)과 6.5절(좌석맵 FCP 이중분포)이 **같은 변경 하나로 둘 다 닫혔다** |
| 알게 된 것 | **`font-display: swap`이 있어도 FCP는 폰트 바이트에 묶여 있었다.** 009가 "폰트가 FCP를 막을 이유가 없다"의 반례로 들었던 그 선언은 대역폭 경쟁을 막아주지 않는다. 그리고 **증상을 규명하는 것보다 원인 후보를 제거해 보는 쪽이 빨랐다** — 이중분포의 메커니즘을 파지 않고 폰트를 줄이니 분포 자체가 사라졌다 |
| 기각 | **층② gzip.** 압축은 19:1로 잘 걸리는데(247,885 → 13,086B) BE 왕복에 **+2.0ms(+29%)** 가 붙고, BE·nginx·Next가 한 호스트라 아낀 바이트로 얻는 게 없다 (3.6절) |
| 작성 시점 | 2026-10-19 |

---

## 1. 왜 만들었나

009는 **측정만** 하고 FE 앱 코드를 한 줄도 고치지 않은 채 끝났다. 그 측정의 결론이 한 줄이었다 —
좌석맵 1회 로드 전송 2,277KB 중 **폰트 하나가 2,009.5KB(88.3%)** 다.

그런데 009는 두 가지를 못 닫고 넘겼다.

| 009가 남긴 것 | 왜 못 닫았나 |
|---|---|
| 4.3절 `확인 필요` — "폰트를 줄이면 FCP가 내려가는가" | `font-display: swap`이 이미 있고, mobile에서 폰트에 9.8초가 필요한데 FCP가 914ms인 반례가 있었다 |
| 6.5절 — 좌석맵 FCP 이중분포(249 / 1,896ms) | 두 모드가 왜 갈리는지 설명하지 못해 FCP·LCP·SI를 "판단 불가"로 표시했다 |
| 4.1절 `확인 필요` — 서브셋의 실제 절감량 | 조각 92개 합계가 3.1MB라 "조각 수 × 34KB"가 2,009KB를 못 밑돌 수도 있었다 |

**이 Task는 그 세 가지를 레버 하나로 같이 닫았다.**

범위는 **전송 바이트를 줄이는 레버**로 한정했다. 009 10절이 줄 세운 8개 항목 중 구조 작업
(Suspense 스트리밍 / 좌석맵 응답 축소 / `SeatSelector` Client 경계 / `cookies()` 분리)은
근거가 다르다 — 문서는 전송 32.7KB뿐이라 반으로 줄여도 절감이 약 16KB이고, 그 작업들의 근거는
전송량이 아니라 확장성과 파싱·메모리다. **근거가 다른 변경을 같은 before/after에 섞으면 어느
쪽이 수치를 움직였는지 분리할 수 없어서** Task 030_2로 떼어냈다.

---

## 2. 측정 환경

009 2절과 같다 — `docker-compose.prod.yml` 전체 스택(nginx + BE 2대 + postgres + redis),
`--profile monitoring` 없이, 측정 대상은 `http://localhost`(nginx 80번) 하나,
Lighthouse 13.5.0 호스트 CLI `--preset=desktop --only-categories=performance`.

### 2.1 009 2절의 표기를 정정한다

009는 측정 환경을 "`CACHE_TYPE=redis`, `AUTH_SESSION_STORE=redis` (prod 기본값)"로 적었다.
**`CACHE_TYPE`은 틀렸다.** `.env:39`가 `CACHE_TYPE=none`이고 `docker-compose.prod.yml`의
기본값도 `${CACHE_TYPE:-none}`이다. prod 기본값은 `redis`가 아니라 `none`이다.

이번 측정은 **양쪽 다 `CACHE_TYPE=none`** 으로 고정했다. 이 값이 이번 결론을 흔들지 않는 이유는
둘이다 — 좌석맵은 008이 캐싱을 기각해서 캐시 대상이 아니고, 홈·상세는 TTFB가 6~8ms라
DB 왕복을 없애도 FCP 1,892ms를 움직일 수 없다(009 4.3절이 "서버 응답은 어느 쪽에서도 지표를
못 움직인다"로 이미 확정했다).

### 2.2 before를 다시 쟀다

009 7.2·7.3 표를 before로 쓰지 않고 **같은 세션에서 before → 변경 → after를 연속으로 쟀다.**
007·008이 반복해서 부딪힌 한계가 "before/after 조건이 어긋나 있다"였고(`reservations` 행 수,
VACUUM, 재시작이 측정 사이에 섞였다), FE는 그 조건을 통제할 수 있다.

교차 확인은 됐다 — 다시 잰 홈 before가 FCP **1,892ms** / 전송 **2,314,727B**인데
009 7.3은 1,890ms / 2,315,361B였다. 폰트 전송도 2,057,991B로 009의 2,009.5KB와 같다.

### 2.3 측정 군은 A군(축소 시드)만

009 4.6절이 좌석맵 문서를 A군 2,000행과 B군 6,026,894행에서 **675,063바이트로 바이트 단위
동일**하게 확정했다. 그래서 B군 시딩 1분 58초를 생략하고 `01b-small-catalog.sql`(0.35초)만 썼다.
**이번이 009가 만든 축소 시드를 처음 써먹은 자리다.**

### 2.4 주 지표는 홈·상세다

| 페이지 | 역할 | 근거 |
|---|---|---|
| **홈** | 주 지표 | 009 7.3 FCP 산포 0.2% — 수렴 |
| **공연 상세** | 주 지표 | FCP 산포 0.1% |
| 좌석맵 | 전송 바이트 + 클릭 응답성 | FCP가 이중분포라 009가 "판단 불가" |

좌석맵 이중분포는 **먼저 규명하지 않았다.** 1,896ms 쪽 값이 홈·상세의 1,890ms와 거의 같고
폰트 다운로드 시간(2,009.5KB ÷ 10,240kbps ≈ 1.57초)과도 맞아서, 폰트를 줄이면 같이 풀릴
가능성이 있다고 보고 순서를 뒤로 뒀다. **그 판단이 맞았다**(3.3절).

### 2.5 회차와 수렴 판정

- 워밍업은 `curl` 20회로 TTFB가 평탄해질 때까지. Lighthouse로 데우지 않는다
- 홈 4회 / 상세 3회 / 좌석맵 6회를 before와 after 양쪽에서
- 산포 `(max-min)/중앙값`이 20%를 넘으면 수치를 쓰되 **판단 불가**로 표시 (008의 규율)
- 회차마다 문서 `resourceSize`로 무결성 검사 (009 4.9절의 요구)

---

## 3. 핵심 발견

### 3.1 `@import` 한 줄이 전송량 77%, FCP 67%를 줄였다

```css
- @import "pretendard/dist/web/variable/pretendardvariable.css";
+ @import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
```

`font-family`가 양쪽 다 `'Pretendard Variable'`이고 `font-weight: 45 920`,
`font-display: swap`도 같아서 `globals.css:62-66`의 `--font-sans`는 건드리지 않았다.

| 페이지 | 지표 | before | after(최종) | 변화 |
|---|---|---|---|---|
| **홈** | 점수 | 80 | **99** | |
| | FCP | 1,892 ms | **616 ms** | **-67.4%** |
| | LCP | 2,092 ms | **744 ms** | **-64.4%** |
| | 전송 합계 | 2,314,727 B | **536,216 B** | **-76.8%** |
| **상세** | 점수 | 80 | **100** | |
| | FCP | 1,892 ms | **536 ms** | **-71.7%** |
| | LCP | 2,092 ms | **668 ms** | **-68.1%** |
| | 전송 합계 | 2,305,418 B | **447,792 B** | **-80.6%** |
| **좌석맵** | 점수 | 80 (중앙값) | **100** (6/6) | |
| | FCP | 1,894 ms (판단 불가) | **492 ms** | 3.3절 |
| | 전송 합계 | 2,331,637 B | **468,964 B** | **-79.9%** |

홈·상세는 before·after 양쪽 모두 FCP 산포가 **0.3% 이하**로 수렴한다. 판단에 쓸 수 있는 수치다.

### 3.2 `font-display: swap`이 있어도 FCP는 폰트에 묶여 있다 — 009 4.3절을 닫는다

009는 "폰트가 FCP를 막는다"는 설명에 반례가 둘 있다고 적었다. ① 패키지 CSS에
`font-display: swap`이 이미 있다 ② mobile은 같은 폰트에 9.8초가 필요한데 FCP가 914ms다.

**결과는 "폰트를 줄이면 FCP가 내려간다"가 맞았다.** 홈 FCP가 1,892 → 616ms다.

`swap`이 막아주지 못한 이유는 **`swap`이 대역폭 경쟁을 막는 선언이 아니기** 때문이다.
`swap`은 "폰트가 늦으면 대체 글꼴로 먼저 그려라"는 지시이고, Lighthouse의 `simulate`
스로틀링(Lantern)은 네트워크를 재구성하면서 커넥션을 공유하는 리소스들의 경쟁을 반영한다.
폰트 2MB가 같은 커넥션 풀을 물고 있으면 **CSS·JS·문서 자체가 그 뒤에 줄을 선다.**
그러면 대체 글꼴로 그릴 준비조차 늦어진다.

> [!warning] 확인 필요
> 이건 "바이트를 줄이니 FCP가 내려갔다"는 관측에 맞는 설명이지만 **Lantern 내부를 들여다보고
> 확인한 것은 아니다.** mobile 반례(②)도 여전히 설명되지 않았다 — mobile은 이번에 재지 않았다.
> 확정된 것은 인과의 방향과 크기(-1,276ms)까지다.

### 3.3 좌석맵 FCP 이중분포의 정체가 폰트였다 — 009 6.5절을 닫는다

before 6회: `249 / 1896 / 1895 / 249 / 1892 / 1895` — 009 A군의 `251/1896/249/250/1895/252`와
같은 두 모드다. **그런데 비율이 뒤집혀서** 중앙값이 1,894ms로 느린 쪽에 붙었다
(009 A군은 251.5ms로 빠른 쪽에 붙었다).

> 009 6.5절이 "3회만 쟀으면 어느 쪽이 중앙값이 되는지가 운에 달렸다"고 경고했는데,
> **6회로도 중앙값이 뒤집힌다.** 이중분포는 회차를 늘려서 해결되는 문제가 아니었다.

after 6회: `494 / 491 / 370 / 493 / 491 / 493` — **두 모드가 사라졌다.**

| | before | after |
|---|---|---|
| 모드 | 249ms 쪽 2회 / 1,89x ms 쪽 4회 | 없음 (370~494ms 한 덩어리) |
| 폭 (max-min) | **1,647 ms** | **124 ms** |
| 점수 | 100 2회 / 79~80 4회 | **100 6회** |
| 산포 판정 | 87% 판단 불가 | 25.2% 판단 불가 |

산포는 여전히 20%를 넘어 "판단 불가" 표시가 유지되지만 **이유가 다르다.** before는 두 모드
사이를 오갔고, after는 491~494ms에 5회가 몰리고 1회만 370ms로 빠진다. 절대 폭이 13분의 1이다.

**해석**: 249ms 회차는 좌석 `<rect>` 2,006개가 폰트 경쟁을 피해 먼저 그려진 경우고,
1,896ms 회차는 못 피한 경우다. 좌석맵의 첫 콘텐츠는 글자가 아니라 `<rect>`라서
(009 4.2절) 폰트를 안 기다릴 수 있는데, 그게 매 회차 되는 게 아니었다.
폰트가 2MB에서 205KB로 줄자 경쟁 자체가 작아져 두 경우의 차이가 사라졌다.

**규명보다 제거가 빨랐다.** 009는 메커니즘을 파다가 "미규명"으로 남겼는데, 원인 후보를
제거해 보는 데는 `@import` 한 줄과 측정 6회면 됐다.

### 3.4 서브셋의 비용 — 요청 1개 → 8~11개, CSS +15KB

| 항목 | before | after (홈) | after (상세) | after (좌석맵) |
|---|---|---|---|---|
| 폰트 요청 수 | 1 | **11** | 8 | 8 |
| 폰트 전송 | 2,057,991 B | **289,305 B** (-85.9%) | 211,192 B (-89.7%) | 205,472 B (-90.0%) |
| 번들 CSS 전송 | 9,786 B | **24,965 B** (+15,179) | 동일 | 동일 |
| 번들 CSS 디코드 | 46,647 B | 95,176 B | 동일 | 동일 |

`@font-face`가 1개 → **92개**가 되면서 번들 CSS가 커졌다. 순 절감은
`1,768,686(폰트) + 25,466(favicon) - 15,179(CSS) = 1,778,973B`이고, 실제 홈 전송 차이가
`2,314,727 - 536,216 = 1,778,511B`로 462B 안에서 맞는다.

**계획 단계에서 가장 걱정한 리스크는 터지지 않았다.** 조각 92개 합계가 3.1MB라
"한글 음절이 넓게 흩어지면 2,009KB를 못 밑돈다"를 우려했는데, 실제로는 8~11조각만 왔다.
이유는 **Pretendard의 dynamic subset이 코드포인트 순서가 아니라 글자 사용 빈도 순으로
쪼개져 있고, 번호가 클수록 흔한 글자**이기 때문이다.

| 조각 | 담은 글자 | 로드 |
|---|---|---|
| 91 | 라틴·숫자·기본 기호 (`U+20-7E` 등) | O |
| 90 | 최빈 한글 음절 — 간/개/거/게/경/공/과/관/구/그/나/내… | O |
| 89, 88, 87 … 75 | 점점 덜 흔한 음절 | O (일부) |
| 2, 1 | 거의 안 쓰이는 음절 (`U+D6xx`, `U+D7xx` 쪽) | X |
| 0 | CJK 호환 한자·전각 기호 | X |

즉 한국어 페이지는 **92조각 중 8~11개만** 끌어온다. 조각당 약 26KB다.

그리고 요청이 11개로 늘어난 비용도 지표에 안 나타났다. nginx가 `listen 80;`(HTTP/1.1)이라
브라우저 커넥션 6개 제한에 걸려 2라운드로 직렬화될 텐데, 그래도 FCP가 1,276ms 내려갔다.
**조각 11개 × 26KB를 2라운드로 받는 것이 2MB를 1라운드로 받는 것보다 싸다.**

> [!warning] 확인 필요
> **조각 수는 페이지의 글자 다양성에 따라 늘어난다.** 홈이 11개(공연 카드 8장의 제목·장소),
> 상세·좌석맵이 8개다. 공연 1,000개 규모의 `/performances` 목록이나 검색 결과는 더 받을 수
> 있는데 **재지 않았다**(이번 측정은 A군 = 공연 9개다). 상한은 92조각 3.1MB이고, 어느 지점에서
> 서브셋이 불리해지는지 모른다.

### 3.5 `favicon.ico` 25.9KB → `icon.svg` 473B

스타터킷 기본 `app/favicon.ico`가 **25,931바이트**였다. `.ico`는 여러 해상도를 품은 컨테이너라
크고 압축도 안 되는데 `High` 우선순위로 받는다.

`app/icon.svg` 473바이트로 바꿨다. Next의 metadata 파일 규약이
`<link rel="icon" sizes="any" type="image/svg+xml">`를 자동으로 넣어서 `layout.tsx`는
건드리지 않았다. 색은 `globals.css`의 `--primary` `oklch(0.52 0.24 295)`를 sRGB로 변환해
`#7933E0`으로 박았다 — SVG는 CSS 변수를 못 읽는다.

| | before | after |
|---|---|---|
| favicon 전송 | 26,269 B | **803 B** |
| favicon 디코드 | 25,931 B | **473 B** |
| 홈 전송 합계 | 560,827 B (폰트 단계) | **535,634 B** |
| 홈 FCP | 618 ms | 617 ms (산포 안) |

**FCP는 안 움직였다.** `High` 우선순위여도 첫 페인트를 막지는 않고, 줄어든 건 바이트뿐이다.
폰트를 줄인 뒤라 25KB가 전송량의 4.5%여서 그래도 의미가 있었다 — before 기준으로는 1.1%다.

**측정 도구의 함정이 하나 있었다.** Lighthouse의 `Other` 타입에 favicon이 들어가는데,
`resourceType`이 없는 항목(헤더의 `/my/reservations` RSC 프리페치 **307 리다이렉트**)도
같이 잡혀서 "Other 2개 26,463B"로 뭉개졌다. 리다이렉트는 본문 0바이트인데 표에서는
favicon과 구분되지 않는다. 수집 스크립트가 `resourceType ?? "Other"`로 합치고 있었고,
`(타입 없음)`으로 따로 세게 고쳤다.

### 3.6 층② gzip은 기각했다 — 19:1로 줄지만 BE 왕복에 +2.0ms

009 4.5절이 "gzip 레버는 ② 층(BE → Next)에만 남아 있다"고 넘긴 항목이다.
`application.yml`에 `server.compression`을 켜서 실측했다.

**압축 자체는 잘 걸린다.**

| `Accept-Encoding` | `content-encoding` | 본문 |
|---|---|---|
| `gzip` | **gzip** | **13,086 B** |
| `identity` | (없음) | 247,885 B |

**19:1이다.** 그리고 계획 단계의 `확인 필요` 하나가 유리하게 닫혔다 — `apiFetchRaw`가
`Accept-Encoding`을 명시하지 않아서 "Node fetch가 헤더를 안 보내면 효과가 0"일 수 있었는데,
Next 컨테이너 안에서 직접 확인하니 **undici가 기본으로 보내고 `content-encoding: gzip`을
받아 투명하게 해제**한다. FE 코드 수정이 필요 없다.

**그런데 비용이 이득보다 컸다.** 300회 × 2라운드:

| `Accept-Encoding` | wall (ms/req) | `tikkit.seatmap.build` (ms/req) |
|---|---|---|
| `identity` | 7.338 / 6.764 | 4.253 / 4.106 |
| `gzip` | **9.290 / 8.896** | 4.164 / 4.080 |

- wall이 약 **7.05 → 9.09ms (+2.0ms, +29%)**
- **`seatmap.build` Timer는 불변이다**(4.08~4.25ms). 압축은 이 Timer 밖이므로 예상대로고,
  **늘어난 2.0ms가 전부 압축 + 응답 쓰기 구간**이라는 뜻이다
- cgroup CPU도 쟀는데 노이즈가 커서(identity 6.915 / 4.201, gzip 7.597 / 11.284 ms/req)
  판단에 쓰지 않았다. 컨테이너 안에서 만료 배치와 actuator 헬스체크가 같이 돈다

**기각 근거는 "느리다"가 아니라 "지금 구성에서 아낀 바이트가 아무것도 아니다"다.**
BE·nginx·Next가 같은 호스트의 compose 네트워크에 있어서, 240KB가 그 안을 지나는 비용은
이미 측정된 7ms 안에 들어 있다. 거기에 압축 CPU 2ms를 새로 내는 건 순손실이다.

같이 기대했던 부수 효과도 더 싼 해법이 있다 — nginx `proxy_buffers 16 16k`(256KB)에
240KB가 겨우 들어가 여유가 16KB뿐인 문제(009 재현 10번)는, 압축이 아니라 `proxy_buffers`를
키우는 쪽이 CPU를 안 쓴다.

**되돌렸다. `application.yml`은 변경 0이고 BE는 한 줄도 안 건드린 상태로 끝냈다.**
기본 비활성 토글로 남기지도 않았다 — 008 6절이 "기본 비활성 토글에도 테스트가 필요하다"를
비싸게 배운 지점이고, 쓰는 사람이 없는 knob을 늘릴 이유가 없다.

**다시 볼 조건은 분명하다**: Task 034에서 BE와 Next가 다른 VM으로 갈라지면 240KB가 실제
네트워크 비용이 된다(82 req/s면 19.7MB/s ≈ 158Mbps). 그때는 `server.compression` 3줄과
이 문서의 +2.0ms를 같이 놓고 판단하면 된다.

### 3.7 번들 분석 — 스크립트 191KB의 59%가 React다

폰트를 줄이자 **Script가 전송량의 1순위가 됐다.**

| 홈 리소스 타입 | before | 비중 | after | 비중 |
|---|---|---|---|---|
| Font | 2,057,991 B | **88.9%** | 289,305 B | **53.8%** |
| Script | 191,778 B | 8.3% | 191,793 B | **35.7%** |
| Stylesheet | 9,786 B | 0.4% | 24,965 B | 4.6% |
| Fetch | 16,892 B | 0.7% | 17,695 B | 3.3% |
| Document | 12,517 B | 0.5% | 12,130 B | 2.3% |
| favicon / icon | 26,269 B | 1.1% | 803 B | 0.2% |

**`@next/bundle-analyzer`는 이 프로젝트에서 쓸 수 없다.** 그건 webpack 플러그인인데
Next 16은 Turbopack으로 빌드한다(HTML에 `turbopack-*.js` 청크가 있다). 설치해서 `webpack`
훅에 꽂아도 Turbopack 빌드는 그 훅을 안 타서 **조용히 아무것도 안 나온다.**
Next 16.1+에 내장된 `next experimental-analyze --output`이 맞는 도구다 — 의존성이 필요 없어서
설치했던 것을 지웠다.

내장 분석기는 `.next/diagnostics/analyze`에 쓰는데 `modules.data`가 길이 접두사가 붙은
비공개 바이너리라(실험 기능) 역공학하지 않았다. 대신 **청크 파일을 직접 열어 라이브러리
마커로 귀속**했다. 홈이 실제로 받는 스크립트 13개 191,785B:

| 귀속 | 전송 | 비중 | 청크 |
|---|---|---|---|
| `react-dom` | 73,132 B | **38.1%** | `0laumrmadr87t.js` |
| `scheduler` | 40,880 B | **21.3%** | `0l4caf27dl1-p.js` |
| (마커 없음 — Next 런타임·앱 코드) | 40,079 B | 20.9% | 7개 |
| **`@base-ui/react`** | **34,562 B** | **18.0%** | `0k4fbzp1viepb.js`, `0zarujnb_ksf9.js` |
| `lucide-react` | 3,132 B | 1.6% | `0rrx~2mohw0th.js` |

**React만 114,012B(59.4%)다.** 이건 바닥이고 줄일 수 없다. 유일하게 손댈 여지가
`@base-ui/react` **34,562B(18%)** 인데, 홈까지 끌려오는 경로가 분명하다 —
`components/ui/button.tsx:1`의 `import { Button as ButtonPrimitive } from "@base-ui/react/button"`.
`Button`은 헤더·카드·폼 어디에나 있어서 전 페이지가 이 비용을 낸다.

그런데 그 컴포넌트는 스타일을 전부 `cva`로 직접 하고 있고 primitive에서 받는 건 render prop과
네이티브 버튼 처리 정도다. **네이티브 `<button>`으로 바꿀 수 있는지가 다음 Task의 1순위
후보다.** `@base-ui/react/alert-dialog`(`components/ui/alert-dialog.tsx`)는 접근성 로직이
실제로 필요해서 후보가 아니다.

**이 Task에서는 줄이지 않았다** — 범위가 분석이고, 폰트 before/after와 섞으면 인과가 깨진다.

> [!note] Task 030_2에서 정정됨 — **이 귀속은 4.2배 과대평가였다**
> 실제로 `@base-ui/react/button`을 네이티브 `<button>`으로 바꿔 보니 홈 Script 전송이
> 191,777 → **183,557B(-8,220B)**, 디코드 612,074 → 590,916B(-21,158B), 청크 13 → 12개였다.
> 위 표의 34,562B가 아니다. 이 절이 스스로 경고한 "한 청크에 여러 라이브러리가 섞이면 첫
> 마커로 전부 귀속"의 오차 배수가 그만큼이다. 근거는 `009_2-seatmap-structure.md` 3.7절.
>
> 다만 "네이티브 `<button>`으로 바꿀 수 있는가"의 답은 맞았다 — 바꿨고 회귀가 없었다.
> 옮겨야 했던 것은 base-ui가 네이티브 버튼에 넣어주던 `type="button"` 하나뿐이었다.

### 3.8 좌석맵 응답 바이트가 id 자릿수에 반응한다

다시 잰 좌석맵 문서가 **677,063B**인데 009는 **675,063B**였다. **정확히 2,000바이트** 차이고
좌석이 2,000석이니 **좌석당 1바이트**다.

원인은 `schedule_seats.id` 자릿수다. 이번 회차의 id가 `12,028,895~12,030,894`로 **8자리**인데,
009 A군은 7자리였다 — 009가 A군 다음에 B군 600만 행을 적재하면서 시퀀스가 1,200만까지 밀렸고,
그 뒤에 시드를 다시 깔면 id가 한 자리 더 길어진다.

**009 4.6절의 "A군과 B군이 바이트 단위 동일"은 두 측정이 같은 자릿수 구간에 있었기 때문에
성립한 것이다.** 그 결론 자체는 유효하지만(좌석맵 크기는 회차당 좌석 수에서만 나온다),
**다른 세션의 바이트 수를 그대로 비교하면 안 된다.** 이번 before/after는 같은 세션이라
영향이 없다.

부수적으로, 이건 Task 030_2의 "좌석맵 응답 축소"에 쓸 수 있는 관찰이다 — 응답에는 좌석당
`id`·`ticketGradeId` 두 개의 bigint가 들어가고, 그 자릿수가 바이트에 선형으로 붙는다.

---

## 4. 실제로 만든 것

### 4.1 `tikkit-front/scripts/lighthouse-runs.mjs` (신규)

009는 회차마다 CLI를 손으로 돌리고 JSON에서 지표를 눈으로 꺼냈다. 좌석맵만 6회 × 2군이라
추출이 측정보다 오래 걸렸고, 더 나쁜 건 **오염 회차를 사람이 걸러야 했다**는 점이다.

```bash
LH_URL=http://localhost LH_RUNS=4 LH_LABEL=after-home LH_EXPECT_DOC_BYTES=46105 npm run lh
```

하는 일 셋:

1. N회 돌리고 FCP/LCP/SI/TBT/CLS/TTFB/전송 합계/문서 크기를 표로 낸다. 중앙값과 산포
   `(max-min)/중앙값`을 계산하고 **20% 초과를 "판단 불가"로 표시**한다 (008의 규율을 코드로)
2. **무결성 검사** — `LH_EXPECT_DOC_BYTES` 대비 문서 `resourceSize`가 5% 밖이면 그 회차를
   집계에서 뺀다. 009 4.9절이 요구한 검사다: 데이터가 통째로 빠진 홈 회차와 정상 회차의
   FCP 차이가 **1ms**, 총 전송 바이트 차이가 **0.6%** 였다. 폰트 2MB가 분모를 지배해서
   총 바이트로는 구분되지 않고, 걸러낸 건 문서 본문 크기였다
3. **폰트 요청을 개별로 나열**한다. 서브셋은 요청 1개 → N개가 되는 변경이라 합계만 보면
   "바이트는 줄었는데 요청이 늘어 직렬화됐다"를 놓친다

결과는 006/008/009와 같이 **stdout 표로만** 낸다. 원본 JSON은 `.lighthouse/`에 남기고
`.gitignore`에 넣었다 — 회차당 약 1MB다.

`npx` 대신 `lighthouse/cli/index.js`를 현재 node로 직접 돌린다. Windows에서 `npx`는 `.cmd`라
`shell: true`가 필요하고, 그러면 URL 따옴표 처리가 `cmd.exe`에 달려서 조용히 어긋날 여지가
생긴다(009 6.6절과 같은 종류의 함정이다).

### 4.2 `tikkit-front/app/icon.svg` (신규) / `app/favicon.ico` (삭제)

3.5절.

### 4.3 기각한 것

- **층② gzip** — 3.6절. `application.yml`을 되돌렸다
- **`@next/bundle-analyzer`** — 3.7절. Turbopack 빌드에서 무효다. 설치했다가 지웠다
- **`next/font/local` 전환** — 서브셋과 다른 레버다. 92개 `@font-face`를 직접 선언해야 하고,
  같은 커밋에 섞으면 원인 분리가 안 된다. 서브셋만으로 -86%가 나왔으니 지금은 근거가 없다
- **직접 서브셋 제작(`pyftsubset` + KS X 1001)** — 범위 확정 때 "측정하고 멈춘다"로 정했다.
  한 줄 교체로 -86%가 나왔으므로 다음 레버는 폰트가 아니라 번들이다
- **`apiFetchRaw`에 `Accept-Encoding` 추가** — undici가 이미 보낸다(3.6절). 불필요했다

---

## 5. 관문

### 5.1 frontend를 재빌드하면 backend1이 같이 재생성된다 → nginx가 502를 뱉는다

`docker compose up -d --build frontend`를 돌렸는데 `backend1`까지 `Recreated`됐다.
compose의 `frontend` → `depends_on: backend1 (healthy)` 때문이다. 그러면 backend1의
컨테이너 IP가 바뀌고, **nginx는 기동 시점 IP를 물고 있어서** 그쪽으로 가는 요청이 다 죽는다
(009 6.3절과 같은 함정인데, 그쪽은 군 전환 시였고 이번엔 **FE만 고쳐도** 걸린다).

변경 하나당 재빌드가 한 번이고 이 Task는 변경이 3개(폰트/favicon/gzip)라 매번 걸렸다.
그래서 재빌드 뒤 절차를 고정했다.

```bash
docker compose -f docker-compose.prod.yml up -d --build frontend
docker compose -f docker-compose.prod.yml restart nginx
until curl -sf -o /dev/null http://localhost/healthz; do sleep 1; done
```

`sleep 1`이 중요하다 — `upstream`에 `max_fails`가 없어서 기본값 1회/10초이고, 타이트 루프는
**요청 한 번 실패로 BE를 10초간 내려놓는다**(009 6.4절).

### 5.2 `curl -K`로 여러 URL을 줄 때 `-o`는 첫 URL에만 적용된다

압축 CPU를 재려고 설정 파일에 `url = "..."`를 300줄 넣고 `curl -s -o /dev/null -K urls.txt`로
돌렸다. **첫 응답만 `/dev/null`로 가고 나머지 299개가 stdout으로 쏟아졌다** — 240KB × 299다.

설정 파일 안에서 URL마다 출력을 지정해야 한다.

```
url = "http://localhost/api/v1/schedules/6022/seats"
output = "/dev/null"
```

그리고 `-w '%{size_download}'`가 이 구성에서 `0`을 돌려줘서 쓸 수 없었다. 응답이 실제로
서버에 닿았는지는 `tikkit_seatmap_build_seconds_count` 델타가 **정확히 +300**인 것으로
확인했다 — 측정 수단을 응답 쪽이 아니라 서버 카운터 쪽에 둔 것이 결과적으로 더 나았다.

### 5.3 `bc`가 없다

Git Bash에 `bc`가 없어서 산술이 전부 빈 문자열로 나왔다. 조용히 틀린 게 아니라
`bc: command not found`가 찍혀서 금방 잡혔지만, `awk 'BEGIN{printf ...}'`로 바꿨다.

---

## 6. 수치

### 6.1 홈 (4회)

| 단계 | 점수 | FCP | LCP | SI | TBT | CLS | TTFB | 전송 합계 | FCP 산포 |
|---|---|---|---|---|---|---|---|---|---|
| **before** | 80 | **1,892** | 2,092 | 1,892 | 0 | 0.0034 | 6 | **2,314,727** | 0.1% |
| 폰트 서브셋 | 99 | 618 | 746 | 618 | 0 | 0.0034 | 7 | 560,827 | 0.8% |
| + favicon | 99 | 617 | 746 | 617 | 0 | 0.0034 | 7 | 535,634 | 1.0% |
| **after (최종)** | **99** | **616** | **744** | 616 | 0 | 0.0034 | 6 | **536,216** | 0.3% |

### 6.2 공연 상세 (3회)

| 단계 | 점수 | FCP | LCP | SI | TBT | CLS | TTFB | 전송 합계 | FCP 산포 |
|---|---|---|---|---|---|---|---|---|---|
| **before** | 80 | **1,892** | 2,092 | 1,892 | 0 | 0.0013 | 5 | **2,305,418** | 0% |
| 폰트 서브셋 | 100 | 536 | 669 | 536 | 0 | 0.0013 | 7 | 473,453 | 0.4% |
| **after (최종)** | **100** | **536** | **668** | 536 | 0 | 0.0013 | 7 | **447,792** | 0.3% |

### 6.3 좌석맵 (6회) — 회차별

| | run1 | run2 | run3 | run4 | run5 | run6 | 중앙값 | 산포 |
|---|---|---|---|---|---|---|---|---|
| **before** 점수 | 100 | 80 | 80 | 100 | 80 | 80 | 80 | |
| **before** FCP | 249 | 1896 | 1895 | 249 | 1892 | 1895 | **1,894** | 87% (판단 불가) |
| **before** LCP | 454 | 2136 | 2135 | 454 | 2132 | 2135 | 2,134 | 78.8% (판단 불가) |
| **after** 점수 | 100 | 100 | 100 | 100 | 100 | 100 | **100** | |
| **after** FCP | 494 | 491 | 370 | 493 | 491 | 493 | **492** | 25.2% (판단 불가) |
| **after** LCP | 574 | 651 | 490 | 667 | 666 | 653 | 652 | 27.2% (판단 불가) |

전송 합계는 before 2,331,637B / after 468,964B, 양쪽 산포 0.3% 이하다.
TBT는 12회 모두 0이다.

### 6.4 폰트 요청

| 페이지 | before | after | 조각 번호 |
|---|---|---|---|
| 홈 | 1개 / 2,057,991 B | **11개 / 289,305 B** | 79, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91 |
| 상세 | 1개 / 2,057,991 B | 8개 / 211,192 B | 83, 85, 86, 87, 88, 89, 90, 91 |
| 좌석맵 | 1개 / 2,057,991 B | 8개 / 205,472 B | **75**, 83, 85, 87, 88, 89, 90, 91 |

조각 하나는 21,151~38,295B이고 평균 약 26KB다. 세 페이지가 공유하는 건 83·85·87~91 일곱 개고,
나머지는 페이지마다 다르다 — **조각 집합은 포함 관계가 아니다.** 좌석맵만 **75번**을 받는데,
그쪽에만 있는 글자(구역·등급 라벨, `aria-label`의 좌석명) 때문이다. 즉 페이지를 추가하면
받는 조각이 단조 증가하지 않고 새 꼬리가 붙을 수 있다.

### 6.5 층② / 층③ (perf 스펙)

| 층 | 항목 | before | after |
|---|---|---|---|
| ② | BE 응답 (`Accept-Encoding` 유무 동일) | 242.08 KB 비압축 | **242.08 KB 비압축** (gzip 기각) |
| ③ | 문서 전송 / 디코드 | 32.87 / 661.19 KB | 32.88 / 661.28 KB |
| ③ | └ 인라인 RSC | 306.92 KB (46.4%) | 306.98 KB (46.4%) |
| ③ | └ SSR 마크업 등 | 352.58 KB | 352.61 KB |
| ③ | script | 14개 186.11 KB | 14개 186.11 KB |
| ③ | font | 1개 2,009.46 KB | **8개 198.32 KB** |
| ③ | stylesheet | 9.2 KB | 24.02 KB |

**층②와 문서 구조는 이 Task가 건드리지 않았다** — 전부 Task 030_2의 대상이다.

### 6.6 좌석 클릭 응답성

| 구간 | before | after |
|---|---|---|
| 첫 클릭 (하이드레이션 직후) | 11.9 ms | 10.5 ms |
| 토글 commit 중앙값 (좌석 20개) | **7.0 ms** | **6.7 ms** |
| 토글 frame 중앙값 | 16.7 ms | 16.6 ms |
| 선택 깊이별 commit (0→1 … 3→4) | 6.4 / 6.9 / 7.0 / 6.9 | 7.0 / 7.1 / 7.0 / 7.2 |
| 측정 장치 비용 (`page.click()` + 속성 확인) | 44 ms | 50 ms |

산포 안에서 같다. 이 Task는 좌석 렌더 구조를 안 건드렸으니 예상대로고, **회귀가 없다**는
확인이다. 깊이별 평탄함도 유지돼서 009 4.8절("`memo` + `Set` 전환은 이득이 없다")이 그대로다.

### 6.7 검증 — 회귀 없음

| 항목 | 결과 |
|---|---|
| `npx tsc --noEmit` | **0** |
| `npm run lint` | **0** |
| `playwright test --project=e2e` | **3 passed** (prod 스택 대상) |
| `--project=e2e --list`에 `perf` 포함 | **0건** (의도대로 제외) |
| `npm run perf` | **3 passed** |
| `npm run build` | 성공 (Turbopack, 1.5초) |
| BE 테스트 | **돌리지 않았다** — `application.yml`을 되돌려서 `tikkit-back/` 변경이 0건이다 |

---

## 7. 한계

- **전송량 1순위가 여전히 폰트다.** after 홈 536KB 중 289KB(53.8%)가 폰트다. 서브셋으로
  -86%를 얻었지만 끝이 아니고, 다음 레버는 직접 서브셋(KS X 1001 2,350자 단일 파일)이다.
  **범위 확정 때 "측정하고 멈춘다"로 정해서 하지 않았다.**
- **조각 수의 상한을 모른다.** 3.4절 `확인 필요`. 글자 다양성이 높은 페이지(공연 1,000개
  목록, 검색 결과)를 재지 않았다. A군 = 공연 9개 기준이다.
- **재방문자는 이 개선을 못 느낀다.** Lighthouse는 항상 브라우저 캐시 콜드고 woff2는 해시
  URL로 immutable 캐시된다. **-1.78MB는 첫 방문 기준이다**(009 9절과 같은 한계).
  오히려 요청이 1개 → 11개로 늘어 재방문 시 조건부 요청이 많아지는데 재지 않았다.
- **폰트 → FCP 인과의 메커니즘을 Lantern 내부로 확인하지 않았다.** 3.2절 `확인 필요`.
  mobile 반례도 그대로 남아 있다(mobile은 이번에 안 쟀다).
- **좌석맵 FCP가 아직 "판단 불가"다.** 산포 25.2%. 두 모드는 사라졌지만 5회가 491~494ms에
  몰리고 1회가 370ms로 빠지는 이유를 모른다. 폭이 124ms라 Task 030_2의 before/after에
  쓸 수는 있을지 다시 봐야 한다.
- **층② gzip 기각을 포화 구간에서 확인하지 않았다.** 저부하(BE CPU 한 자리 %)에서 +2.0ms를
  쟀고, DB가 병목인 구간에서는 판단이 달라질 수 있다 — 008이 좌석맵 캐싱 기각에 남긴 것과
  같은 유보다. Task 033/034의 재측정 후보다.
- **압축 CPU를 cgroup 카운터로 분리하지 못했다.** 노이즈가 커서 wall time으로 판단했다.
  wall에는 nginx 몫도 섞여 있다(압축하면 nginx가 옮기는 바이트는 줄어든다).
- **번들 귀속이 청크 문자열 검색이다.** `next experimental-analyze`의 모듈 그래프를
  읽지 않고 청크 파일에서 `react-dom`·`base-ui` 같은 마커를 grep했다. 한 청크에 여러
  라이브러리가 섞이면 첫 마커로 전부 귀속되므로 **비중은 근사다**(특히 "마커 없음" 20.9%).
- **favicon에 `.ico` 폴백을 두지 않았다.** Safari 16 미만에서 아이콘이 안 보인다.
  폴백을 두면 양쪽 요청이 다 나가서 절감이 사라지므로 의도적으로 뺐다.
- **A군만 쟀다.** 009 4.6절 근거로 B군을 생략했는데, 그 근거는 좌석맵에 대한 것이고
  홈·상세의 "공연 9개 vs 1,008개"는 이번에도 답이 없다.
- **호스트와 측정 대상이 같은 PC다.** 006 2절부터 이어지는 한계다.

---

## 8. 다음 단계

전송 기준으로 다시 줄 세운 것이다. **1순위가 폰트에서 번들로 바뀌었다.**

1. **`@base-ui/react/button` → 네이티브 `<button>`** (34,562 B, 홈 스크립트의 18%) —
   3.7절. 스타일은 이미 `cva`가 다 하고 있어서 primitive가 주는 게 적다. 전 페이지가 내는
   비용이라 효과 범위가 넓다
2. **직접 폰트 서브셋** (289 KB → ?) — KS X 1001 단일 파일 + `next/font/local`.
   요청 수도 11개 → 1개로 줄어 HTTP/1.1 직렬화가 같이 사라진다. 다만 폰트 파일을 레포에
   커밋해야 하고 빌드 단계가 늘어난다
3. **Task 030_2 (구조)** — 좌석맵 Suspense 스트리밍, 좌석맵 응답 DTO 축소,
   `SeatSelector` Client 경계, `apiFetchRaw`의 `cookies()` 분리 → `revalidate`/`cacheLife`.
   근거는 전송량이 아니라 확장성과 파싱·메모리다
4. **`nginx.conf`에 `max_fails`/`fail_timeout` 명시** — 5.1절. 기본값 1회/10초가 측정마다
   걸린다. 009도 범위 밖으로 넘겼고 이번에도 넘겼다
5. **`proxy_buffers` 상향** — 240KB에 여유 16KB뿐이다. 3.6절대로 압축보다 싼 해법이다
6. **HTTP/2** — 폰트 조각 11개가 커넥션 6개 제한에 걸린다. 지금도 이득이 났지만 h2c를 켜면
   직렬화가 사라진다. TLS가 없는 로컬에서는 `listen 80 http2;`가 필요하고 Task 034의 일이다

**하지 않기로 한 것**: 층② gzip(3.6절 — Task 034에서 재판단), `@next/bundle-analyzer`
(Turbopack에서 무효), 선택 레이어 `memo` + `Set` 전환(009 4.8절에서 근거를 잃었고 6.6절에서
평탄함이 재확인됐다).

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (007 6절). PowerShell이면 생략
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 1. 스택 기동 — monitoring 프로필은 끈다 (2절). CACHE_TYPE=none 확인 (2.1절)
cd <repo>
grep CACHE_TYPE .env            # -> CACHE_TYPE=none
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
# -> 6개 서비스(frontend/nginx/postgres/backend1/backend2/redis) 전부 healthy

# 2. Lighthouse 버전 확인 — package.json에 정확 버전으로 고정돼 있다
npx --prefix tikkit-front lighthouse --version    # -> 13.5.0

# 3. A군 시딩 (축소). B군은 쓰지 않는다 (2.3절)
set -a && . ./.env && set +a
pg() { docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"; }
pg -f - < load-test/sql/01b-small-catalog.sql     # -> seats 2000 | performances 1 | schedules 1
pg -f - < load-test/sql/02-bulk-schedule-seats.sql # -> 완료 2000 행, 약 0.04초

# 4. 좌석맵 id는 하드코딩하지 않는다 — 시퀀스가 이어져 매번 다르다 (3.8절)
pg -qAt -F$'\t' -c "
select p.id, s.id, tg.id, count(ss.id)
from venues v
  join performances p on p.venue_id = v.id
  join schedules s on s.performance_id = p.id
  join ticket_grades tg on tg.schedule_id = s.id
  join schedule_seats ss on ss.schedule_id = s.id and ss.ticket_grade_id = tg.id
where v.name = '[LOAD] 부하테스트 아레나' and tg.grade = 'A'
group by p.id, s.id, tg.id;"
# -> 2010  6022  36075  800        (이 문서의 측정값)
export PERF_PERFORMANCE_ID=2010 PERF_SCHEDULE_ID=6022 PERF_GRADE_ID=36075
SEAT_URL="http://localhost/performances/$PERF_PERFORMANCE_ID/schedules/$PERF_SCHEDULE_ID/seats?gradeId=$PERF_GRADE_ID&quantity=4"

# 5. 데우기 + 무결성 기대값 뽑기. 이 size_download가 LH_EXPECT_DOC_BYTES다 (4.1절)
for u in http://localhost/ http://localhost/performances/2010 "$SEAT_URL"; do
  for i in $(seq 1 20); do curl -s -o /dev/null "$u"; done
  curl -s -o /dev/null -w "%{size_download}B ttfb %{time_starttransfer}\n" "$u"
done
# -> 46105B / 33584B / 677153B, ttfb 0.006~0.009s로 평탄

# 6. Lighthouse. 홈 4 / 상세 3 / 좌석맵 6 (2.5절). 1회 약 11.7초
cd tikkit-front
LH_URL=http://localhost LH_RUNS=4 LH_LABEL=after-home LH_EXPECT_DOC_BYTES=46105 npm run lh
LH_URL=http://localhost/performances/2010 LH_RUNS=3 LH_LABEL=after-detail LH_EXPECT_DOC_BYTES=33584 npm run lh
LH_URL="$SEAT_URL" LH_RUNS=6 LH_LABEL=after-seatmap LH_EXPECT_DOC_BYTES=677153 npm run lh
# -> 오염 회차는 스크립트가 "<-- 오염"으로 표시하고 집계에서 뺀다

# 7. perf 스펙. E2E_BASE_URL이 없으면 스펙이 직접 실패시킨다
E2E_BASE_URL=http://localhost npm run perf       # -> 3 passed

# 8. 폰트 조각 번호와 unicode-range 확인 (3.4절)
node -e '
const css=require("fs").readFileSync("node_modules/pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css","utf8");
const b=css.split(/\/\* \[(\d+)\] \*\//).slice(1);
for(let i=0;i<b.length;i+=2){const m=b[i+1].match(/unicode-range:\s*([^;]+);/);
  if([0,1,90,91].includes(Number(b[i]))) console.log(b[i], m[1].slice(0,80));}'
# -> 91이 라틴·숫자, 90이 최빈 한글, 0·1이 거의 안 쓰이는 글자

# 9. 번들 귀속 (3.7절). 청크 해시가 Docker 빌드와 같아야 한다
npm run build
node -e '
const fs=require("fs");
const items=JSON.parse(fs.readFileSync(".lighthouse/after-home/run-1.json","utf8"))
  .audits["network-requests"].details.items.filter(i=>i.resourceType==="Script");
for(const i of items){const n=i.url.replace(/^.*\//,"").replace(/\?.*$/,"");
  let mk="-"; try{const s=fs.readFileSync(".next/static/chunks/"+n,"utf8");
    for(const m of ["react-dom","scheduler","base-ui","lucide"]) if(s.toLowerCase().includes(m)){mk=m;break;}}catch(e){mk="(로컬에 없음)";}
  console.log(String(i.transferSize).padStart(7), n.padEnd(28), mk);}'

# 10. 층② 압축 재판단용 (3.6절). application.yml에 server.compression을 켠 뒤
#     같은 빌드에서 gzip과 identity를 비교하면 압축 몫만 분리된다
: > /tmp/urls.txt
for i in $(seq 1 300); do
  printf 'url = "http://localhost/api/v1/schedules/6022/seats"\noutput = "/dev/null"\n' >> /tmp/urls.txt
done   # output을 URL마다 써야 한다 — -o는 첫 URL에만 적용된다 (5.2절)
for enc in identity gzip; do
  curl -s -H "Accept-Encoding: $enc" -K /tmp/urls.txt >/dev/null 2>&1   # 워밍업
  t0=$(date +%s%N); curl -s -H "Accept-Encoding: $enc" -K /tmp/urls.txt >/dev/null 2>&1; t1=$(date +%s%N)
  awk -v e="$enc" -v t0="$t0" -v t1="$t1" 'BEGIN{printf "%-8s wall %6.3f ms/req\n", e, (t1-t0)/1e6/300}'
done
# -> identity 약 7.0 / gzip 약 9.1 ms/req. bc는 Git Bash에 없다 (5.3절)

# 11. 재빌드할 때마다 nginx를 재시작한다 (5.1절)
docker compose -f docker-compose.prod.yml up -d --build frontend
docker compose -f docker-compose.prod.yml restart nginx
until curl -sf -o /dev/null http://localhost/healthz; do sleep 1; done
# -> sleep 1이 중요하다. 타이트 루프는 max_fails 기본값에 걸려 BE를 10초간 내려놓는다
```
