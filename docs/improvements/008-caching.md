# 008. 캐싱 적용 — 캐시가 줄일 수 없는 것을 먼저 찾았다 (Task 029)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 029 (Phase 8: 성능 개선) |
| 대상 코드 | `CacheConfig`·`CacheNames`·`CacheProperties`·`RedisConnectionConfig`(신규), `CatalogCache`·`SeatmapResponseCache`(신규), `ScheduleService`·`PerformanceService`·`ReservationExpiryScheduler`, `Cache*Test` 5종(신규) |
| 작성 시점 | 2026-10-17 |
| before 기준선 | [007. 쿼리·인덱스 최적화](007-query-optimization.md) 7.1절 — 탐색 p95 2.48ms / 좌석맵 6.77ms / 예매 7.77ms. **같은 세션에서 다시 쟀다**(A군) |
| 결론 | *(측정 후 채움)* |
| 알게 된 것 | **서비스 레이어 `@Cacheable`은 직렬화 비용을 줄일 수 없다.** 캐시가 돌려주는 건 객체 리스트고, 컨트롤러가 반환하면 Jackson이 237.9KB를 다시 쓴다. 007이 지목한 이득의 출처를 캐시로 잡으려면 응답 바이트를 캐시해야 하고, 그러면 좌석 상태가 stale해진다 |
| 데이터 | `schedule_seats` 6,026,894행, 공연 1,008 / 회차 3,020 / 회원 503, `reservations` 55,429행(006 측정 당시 13,798 → 007 약 43,000 → 지금) |

---

## 1. 왜 만들었나

ROADMAP의 Task 029는 "Spring Cache + Redis로 공연 목록/상세 캐싱"이다. 그런데 앞선 두 Task가
이 Task로 넘긴 숙제가 그 한 줄보다 까다로웠다.

> 캐시 적용 후에는 **적중률을 따로 재야** p95 개선이 캐시 덕인지 확인할 수 있다.
> — [006](006-performance-baseline.md) 10절

> 4.5·4.6절이 보여준 건 **이 규모에서 DB가 병목이 아니라는 것**이다. 공연 목록이 0.55ms인데
> 캐시를 붙여도 p95에서 보일 여지가 작다. (…) **캐시의 이득은 DB 시간이 아니라 좌석맵
> 237.9KB의 직렬화 비용에서 나올 가능성이 크다. 무엇이 줄어드는지 먼저 정하고 재야 한다.**
> — [007](007-query-optimization.md) 10절

그리고 ROADMAP에는 이렇게도 적혀 있었다.

> 좌석·잔여 수량은 캐싱 대상에서 제외하고 이유를 문서화

**둘이 충돌한다.** 007이 지목한 이득의 출처가 ROADMAP이 제외하라는 바로 그 응답이다.
그래서 이 Task의 목표를 "캐시를 붙인다"가 아니라 **"캐시가 무엇을 줄이는지 수치로 분해하고,
줄일 수 있는 것만 채택한다"**로 잡았다. Task 022와 028이 인덱스를 하나도 추가하지 않고
끝났듯이, 일부가 기각으로 끝나는 것도 결과로 받는다.

### 1.1 이 서비스의 캐시 무효화는 왜 쉬운가

착수하고 코드를 훑으면서 evict 설계를 통째로 단순하게 만드는 사실 두 개를 확인했다.

**공연 데이터를 바꾸는 쓰기 API가 없다.** 전체 코드베이스의 POST/PUT/PATCH/DELETE 매핑은
4개뿐이고 전부 auth와 reservation이다. `Performance`·`Schedule`·`TicketGrade`·`Seat`·`Venue`
엔티티에는 setter도 update 메서드도 0개다. 관리자 API는 없고 `MemberRole.ADMIN`은 enum 값만
있다. 공연 데이터는 Flyway 시드와 부하 테스트 시더로만 들어온다.

**그래서 무효화 지점이 하나다.** `ReservationExpiryScheduler`가 60초마다 돌리는
`PerformanceRepository.recalculateDerivedFields()`가 `performances.status`·`start_date`·`end_date`를
재계산하고, 이 세 컬럼이 전부 목록·상세 응답에 들어 있다. 그 메서드는 `IS DISTINCT FROM`으로
**실제로 값이 바뀐 행 수를 반환**하므로, 그 값이 0보다 클 때만 캐시를 비우면 된다.

일반적인 캐싱 작업에서 제일 성가신 부분(어느 쓰기가 어느 캐시를 더럽히나)이 이 서비스에는
거의 없다. 대신 성가신 쪽은 다른 데 있었다 — 6절.

## 2. 측정 환경

007 7.1과 같은 조건으로 맞췄다. 조건이 하나라도 다르면 before/after를 비교할 수 없다.

- `docker-compose.prod.yml` 전체 스택(nginx + BE 2대 + postgres + redis), `--profile monitoring`
- nginx keepalive ON (006 4.6에서 기준선으로 고정한 상태)
- `schedule_seats` 6,026,894행, 부하 데이터 공연 1,000 / 회차 3,000
- k6는 compose 서비스로, `--no-deps`로 띄워 BE 재생성을 막는다
- 측정 사이마다 `load-test/sql/98-reset-reservations.sql`
- 각 군 3회, **중앙값**

### 2.1 측정 군

캐시의 효과만 보려면 "캐시 켜고 끄기" 두 군으로는 부족하다. 좌석맵 쿼리를 둘로 쪼갠 변경이
캐시와 무관하게 비용을 만들기 때문에, 코드 변경과 캐시 효과를 분리해야 한다.

| 군 | 코드 | `CACHE_TYPE` | 라벨 |
|---|---|---|---|
| A | `main` (쿼리 분리 전) | — | `t029-a-main` |
| A′ | 이 브랜치 | `none` | `t029-b-none` |
| B | 이 브랜치 | `caffeine` | `t029-c-caffeine` |
| C | 이 브랜치 | `redis` | `t029-d-redis` |
| D | 이 브랜치 | `redis` + 응답 바이트 캐시 | `t029-e-bytes` |

읽는 방법은 이렇다.

- **A vs A′** — 좌석맵 쿼리 분리의 비용 (캐시가 없을 때)
- **A′ vs C** — 캐시의 순이득
- **B vs C** — 로컬 캐시 vs 공유 캐시
- **C vs D** — 직렬화 비용의 상한
- **C의 균일 분포 vs 편중 분포** — 트래픽 분포 민감도

A를 007 수치로 대신하지 않고 다시 쟀다. 007이 "before/after 조건이 어긋나 있다"를 한계로
남겼고(그 사이 `reservations`가 계속 쌓였다), 지금은 55,429행으로 더 늘었다. 같은 세션에서
다시 재지 않으면 이번 비교도 같은 흠을 갖는다.

### 2.2 관문 — 워밍업 런이 없으면 조건이 안 맞는다

처음 A군을 쟀을 때 3회가 **10.25 / 7.63 / 7.41ms**로 흩어졌다. 006·007의 산포가 ±0.2ms였으니
조건이 다른 것이다.

원인은 **컨테이너 교체 직후의 차가운 JVM**이었다. 이 Task는 군마다 `CACHE_TYPE`을 바꿔
backend를 재생성해야 해서, 006·007과 달리 매번 JVM이 처음부터 시작한다. 006의 재현 방법이
`--no-deps`를 강조한 이유가 바로 "BE를 재생성하지 말라"는 것인데, 이 Task는 재생성을 피할 수
없다.

그래서 시나리오마다 **20초짜리 워밍업 런을 먼저 돌리고 버린다**. 같은 A군이
**6.79 / 6.63 / 6.31ms**로 수렴했고 007의 6.77ms와 겹친다. 워밍업을 넣지 않았으면 캐시를
켠 군이 "빨라졌다"고 나왔을 것이다 — **실제로는 두 번째 측정이라서 빨라진 것**이다.

## 3. 무엇을 캐시했고 무엇을 뺐나

응답을 "변하는 것"과 "변하지 않는 것"으로 가른 다음 변하지 않는 쪽을 후보로 올렸다.
그리고 측정 후 **후보 중 하나를 다시 뺐다** — 불변인 것만으로는 부족하고 **값이 작아야** 한다(4절).

**최종 채택**

| 캐시 | 키 | 값 | 항목 크기 | TTL | evict |
|---|---|---|---|---|---|
| `performanceList` | `category\|status\|page\|size` | `PerformanceListPage` | 690 B | 60s | 배치(변경 행 > 0) |
| `performanceDetail` | 공연 id | `PerformanceDetailResponse` | 수 KB | 60s | 배치(변경 행 > 0) |
| `gradePrices` | 회차 id | `List<TicketGradeRow>` (가격·등급) | 수백 B | 10m | 없음 (불변) |

**제외한 것과 이유**

- **잔여 수량**(`remainingQuantity`) — `schedule_seats`의 AVAILABLE COUNT다. 예매마다 바뀌고
  FE의 매진 판정(`remainingQuantity === 0`)이 여기 걸려 있다. 가격만 캐시하고 COUNT는 매번
  센다. 둘을 한 덩어리로 캐시하면 매진된 회차가 TTL 동안 "잔여 있음"으로 보인다.
- **좌석맵 전체** — 처음에는 뺄 이유가 "좌석 상태가 바뀌니까"뿐이었다. 응답 8필드 중 7개가
  불변이라 좌표·등급만 캐시하고 상태는 매번 읽는 구조를 만들어 실측했는데, **그쪽도 기각했다.**
  항목이 337,885 바이트여서 캐시를 쓰는 비용이 원래 쿼리를 넘었다 (4.1~4.2).
- **keyword 검색** — 사용자가 아무 문자열이나 넣을 수 있어서 키 공간이 무한하다.
  `@Cacheable(condition = "#keyword == null || #keyword.isBlank()")`로 통과시킨다.
- **없는 회차의 빈 결과** — 키가 경로 변수라 **사용자가 키 공간을 고를 수 있다**. 무작위 id를
  쏘는 것만으로 TTL 10분짜리 빈 항목이 쌓인다. `unless = "#result.isEmpty()"`로 막았다.

ROADMAP의 "좌석·잔여 수량은 캐싱 대상에서 제외"는 결과적으로 **그대로 지켜졌다.** 다만 이유가
처음 생각과 달랐다 — "재고라서"가 아니라 "값이 커서"다. 불변인 좌표까지 포함해서 뺐다.

### 3.1 캐시 전용 빈을 따로 둔 이유

`@Cacheable`은 스프링 프록시를 거쳐야 동작한다. 같은 클래스 안에서 호출하면 프록시를 타지 않고
**예외도 경고도 없이 캐시가 동작하지 않는다**(적중률만 영구히 0%다).
`ScheduleService.getTicketGrades()`는 캐시된 가격과 실시간 COUNT를 합쳐야 하므로 "캐시되는
조회"와 "그걸 쓰는 조립"이 같은 메서드에 있을 수 없다. 그래서 캐시 대상만 `CatalogCache`로 뺐다.

공연 목록·상세는 이 문제가 없어서 `PerformanceService`에 직접 붙였다 — 컨트롤러가 바깥에서
부르므로 프록시를 탄다. 대신 목록은 반환형을 바꿔야 했다(5.2).

### 3.2 `@EnableCaching(order = HIGHEST_PRECEDENCE)`

캐시 어드바이저와 트랜잭션 어드바이저의 기본 order는 **둘 다** `Ordered.LOWEST_PRECEDENCE`라서
어느 쪽이 바깥인지 보장되지 않는다. 트랜잭션이 바깥이면 캐시 히트에도 커넥션을 잡고 트랜잭션을
열었다 닫는다. 캐시를 가장 바깥에 둬서 히트 경로가 Hikari를 아예 거치지 않게 했다.

## 4. 핵심 발견

### 4.1 적중률 99.97%인데 캐시를 켠 쪽이 더 느렸다

좌석맵 배치(좌표·등급)를 캐시한 결과다.

| 군 | 좌석맵 전체 p95 | 생성(DB+조립) p95 | 적중률 |
|---|---|---|---|
| A. `main` (단일 쿼리) | **6.63 ms** | — | — |
| A′. 분리, 캐시 off | 8.62 ms | 6.89 ms | — |
| B. Caffeine (로컬) | **4.98 ms** | 2.79 ms | 97.0% |
| C. Redis (공유) | 7.22 ms | 5.50 ms | **99.97%** |

Redis 캐시는 거의 전부 히트했는데도 캐시가 없던 A보다 **+0.6 ms 느리다.** 같은 코드에서
캐시만 끈 A′(8.62 ms)보다는 1.4 ms 빠르니 캐시가 일을 안 한 건 아니다 — **캐시가 아끼는 것보다
캐시를 쓰는 비용이 컸다.**

비용의 출처는 캐시 항목 크기다. `redis-cli strlen`으로 재면 한 항목이 **337,885 바이트**다
(좌석 2,000개 × 약 169 B, Redis 할당 기준 384 KB). 히트 경로가 하는 일은 "DB 안 가기"가 아니라
**"330 KB를 네트워크로 받아 2,000개 record로 역직렬화하기"**이고, 그게 Postgres에서 2,000행을
읽는 것보다 비쌌다.

Caffeine이 2.79 ms로 가장 빠른 이유도 같은 지점이다 — 힙에 객체 그래프를 그대로 두니
직렬화가 **0**이다. Redis 히트와의 차이 2.71 ms가 통째로 직렬화·전송 비용이다.

### 4.2 그래서 좌석맵 캐시를 기각했다 — 지금 수치 때문만이 아니다

4.1만 보면 "Caffeine을 쓰면 되지 않나"가 된다. 그런데 Caffeine이 이긴 이유(직렬화를 피한다)가
그대로 이 캐시의 한계이기도 하다.

**비용이 좌석 수에 비례해 커진다.** 전송량과 역직렬화 비용이 둘 다 좌석 수에 선형이다.
2,000석에서 Redis가 이미 지는데, 공연장이 더 크면 격차가 벌어진다. Caffeine은 전송을 피하지만
2,000개 객체 그래프를 힙에 올리므로 **보관 가능한 회차 수가 힙에 묶이고** JVM 전체와 경쟁한다.
회차 3,000개면 Redis 기준으로도 1.15 GB다(384 KB × 3,000).

즉 이 캐시는 **규모가 커지면 나빠지는 쪽**이다. 그래서 "지금 조금 느리다"가 아니라
"방향이 반대다"로 기각했다. 좌석맵의 확장성 해법은 캐싱이 아니라 응답 자체를 줄이는 것이고
(237.9 KB / 2,000석), ROADMAP은 그 문제를 이미 Task 030(FE 렌더링)에 두고 있다.

쿼리 분리도 같이 되돌렸다. 캐시가 없으면 분리는 순손실이다 — 왕복이 1 → 2로 늘고
`schedule_seats` 2,000행을 두 번 읽어서 6.63 → 8.62 ms(**+30%**)가 된다. 빠지는 건
`seats` 조인(Task 028 실측 0.58 ms)과 2,000행 정렬(0.05 ms)뿐이라 애초에 교환비가 나빴다.

> [!warning] 확인 필요
> 이 기각은 **"이 규모에서"** 가 아니라 **"값이 큰 캐시 일반"** 에 대한 판단이다. 다만 반례가
> 있을 수 있다 — DB가 포화에 가까워지면 "DB를 안 가는 것" 자체의 가치가 올라가므로, 역직렬화
> 비용을 내고도 이득일 수 있다. Task 033 breakpoint에서 다시 볼 후보로 남긴다.

### 4.3 서비스 레이어 `@Cacheable`은 직렬화 비용을 줄일 수 없다

007이 Task 029로 넘긴 가설은 "이득이 좌석맵 237.9 KB의 직렬화에서 나올 것"이었다.
그런데 `@Cacheable`로는 그 비용을 **구조적으로** 잡을 수 없다. 캐시가 돌려주는 건 객체 리스트고,
컨트롤러가 그걸 반환하면 Jackson이 2,000건을 다시 JSON으로 쓴다.

Timer로 응답 시간을 "만드는 시간"과 "나머지"로 갈라 확인했다.

| 군 | 전체 p95 | 생성 p95 | 차이 = 직렬화 + 서블릿·nginx |
|---|---|---|---|
| A′ (캐시 off) | 8.62 ms | 6.89 ms | 1.73 ms |
| B (Caffeine) | 4.98 ms | 2.79 ms | 2.19 ms |
| C (Redis) | 7.22 ms | 5.50 ms | 1.72 ms |

차이가 어느 군에서나 **1.7~2.2 ms**로 일정하다. 캐시가 건드리는 건 앞쪽(생성)뿐이고,
뒤쪽은 캐시를 무엇으로 바꿔도 그대로 남는다. 좌석맵 응답 시간의 **약 30%** 가 캐시로 줄일 수
없는 부분이라는 뜻이다.

### 4.4 그 가설을 실제로 재려면 바이트를 캐시해야 하고, 그래도 안 빨라졌다

직렬화를 건너뛰는 유일한 방법은 **직렬화된 바이트를 캐시해서 그대로 내려주는 것**이다.
`ApiResponse`까지 포함해 직렬화한 뒤 `ResponseEntity<byte[]>`로 반환하도록 만들고 TTL 2초로 켰다
(바이트가 동일하므로 FE 계약은 안 바뀐다).

| 군 | 좌석맵 p95 | 바이트 캐시 적중률 |
|---|---|---|
| C. Redis | 7.22 ms | — |
| D. Redis + 응답 바이트 | **7.21 ms** | 26.7% |

**차이가 없다.** 히트하면 직렬화를 건너뛰지만, 미스하면 평소 직렬화에 더해 238 KB를 Redis에
쓰는 비용이 붙는다. 적중률 26.7%에서는 그 둘이 상쇄된다.

적중률이 낮은 이유는 **도착률 대비 TTL**이다. 좌석맵 시나리오는 10.7 req/s를 회차 60개에
흘리므로 한 회차당 약 5.6초에 한 번 들어오는데 TTL이 2초다. 같은 설정의 예매 시나리오는
82 req/s라 회차당 0.73초여서 적중률이 48.4%로 올라갔다. 즉 이 캐시는 **"TTL보다 자주 들어오는
키"에서만 의미가 있다.**

stale 비용은 작았다 — 예매 시나리오에서 `SOLD_OUT` 10건 / 성공 5,014건(0.2%), 진짜 에러 0%.
그래도 채택하지 않았다. 006·007이 기록했듯 이 설정에서는 좌석 경쟁이 거의 일어나지 않고,
**stale 좌석의 비용은 정확히 오픈런에서 커진다** — 확장성 방향이 반대다.

### 4.5 캐시 효과는 트래픽 분포에 크게 의존한다

`browse.js`는 공연 1,000개와 50페이지를 균일 무작위로 훑는다. Task 027이 Postgres 버퍼 캐시를
속이지 않으려고 일부러 그렇게 짠 것인데(006 10절), 조회 캐시를 재는 데는 **최악의 조건**이다.
그래서 앞쪽 2페이지에 80%를 몰아주는 `browse-skewed.js`를 추가해 나란히 쟀다 (C군, 같은 코드).

| 분포 | 탐색 p95 | `performanceList` | `performanceDetail` | `gradePrices` |
|---|---|---|---|---|
| 균일 (`browse`) | 2.80 ms | 93.4% | **41.6%** | 51.8% |
| 편중 (`browse-skewed`) | **2.37 ms** | 94.4% | **77.6%** | 92.4% |

상세 적중률이 41.6% → 77.6%로 뛰고 p95가 **-15%** 다. 실제 예매 서비스의 트래픽은 오픈 임박한
몇 개 공연에 쏠리므로, 균일 분포 수치는 캐시 효과의 **하한**으로 읽어야 한다.

### 4.6 그런데 어느 분포에서도 캐시가 p95를 개선하지 못했다 — 그리고 그게 기각 사유는 아니다

| 군 | 탐색 p95 (균일) | 탐색 p95 (편중) |
|---|---|---|
| A. `main` (캐시 코드 없음) | **2.20 ms** | — |
| A′. 캐시 off | 2.63 ms | — |
| C. Redis | 2.80 ms | 2.37 ms |

A와 A′의 차이(+0.43 ms)가 먼저 눈에 걸린다. **캐시를 꺼도 느려진다** — `NoOpCacheManager`여도
`@Cacheable` 인터셉터는 매 호출마다 돌고(`get` → null → 메서드 실행 → `put`),
`CatalogCache` 프록시 홉이 하나 더 붙는다. 캐시 인터셉터는 공짜가 아니다.

그 위에서 Redis 캐시가 아끼는 DB 시간이 그 비용을 넘지 못했다. 007이 이걸 예고했다 —
"공연 목록이 0.55 ms인데 캐시를 붙여도 p95에서 보일 여지가 작다."

**그래도 기각하지 않았다.** 이 측정 구간은 서버가 거의 놀고 있는 저부하다(006 기준 BE CPU
최대 4.2%, Hikari `pending` 0). 그 구간에서 지연을 지배하는 건 요청당 고정비이고, DB 작업을
줄인 효과는 원래 p95로 드러나지 않는다. 캐시의 값은 지연이 아니라 **용량**이다 — 히트가
희소 자원(DB 커넥션·CPU)을 흔한 자원(Redis 왕복)으로 바꾼다.

그리고 4.2와 달리 **방향이 맞다.** 항목이 수백 바이트~수 KB라서 규모가 커져도 캐시 비용은
거의 그대로인데, 카탈로그가 커지면 원래 쿼리가 나빠진다 — 007 4.7이 offset 980에서 플랜이
뒤집히는 걸 봤고 4.8은 키워드 검색이 Seq Scan이며 `pg_trgm`은 3자 미만 한글에 못 쓴다고 적었다.
공연이 1,000개에서 100,000개가 되면 캐시가 아끼는 값이 커진다.

그래서 **채택 근거가 현 시점 p95가 아니라는 것을 명시하고** 남겼다. 검증은 포화 구간에서
해야 하고 그건 Task 033이다.

## 5. 실제로 만든 것

### 5.1 토글 — `tikkit.cache.type`

`none` | `caffeine` | `redis`, 기본 `none`. `tikkit.auth.session-store`와 같은 모양으로 맞췄다.

기본값을 `none`으로 둔 이유는 두 가지다. 하나는 "Redis 없이도 기동·테스트가 돌아야 한다"는
기존 결정([003](003-redis-distributed-lock.md) 3절)을 지키는 것 — 통합 테스트가 캐시를 모르는
상태로 그대로 돈다. 다른 하나는 **캐시가 채택을 판정하는 대상**이라는 것이다. 스택을 띄우는
것만으로 켜지면 "캐시 없는 기준선"을 다시 재기 어렵다.

`none`은 `NoOpCacheManager`라서 `@Cacheable`이 전부 통과만 한다. 조건부 빈으로 어노테이션을
지우지 않고도 캐시가 완전히 꺼진다.

### 5.2 `Page`를 캐시할 수 없어서 반환형을 바꿨다

`PageImpl`은 기본 생성자가 없고 `Pageable`을 품고 있어서 Jackson으로 역직렬화할 수 없다.
직렬화는 되지만 꺼낼 때 깨진다.

우회 방법이 둘이었다. mixin이나 커스텀 역직렬화기를 붙이는 쪽은 Spring Data의 내부 구조에
의존하게 되고 버전이 올라갈 때 조용히 깨진다. 반대로 서비스가 캐시 친화적인 타입을 돌려주고
컨트롤러가 응답으로 조립하면 캐시는 평범한 record 하나만 알면 된다. 후자를 골랐다 —
`PerformanceListPage(List<PerformanceSummaryResponse> content, long totalElements)`.

덤으로 3.1의 self-invocation 함정을 피한다. `list()` 자체에 `@Cacheable`을 붙일 수 있어서
캐시 전용 메서드를 따로 둘 필요가 없다. `PageResponse`에는 `Page` 없이 조립하는 생성자를
추가했고, 기존 `PageResponse(Page<T>)`는 예약 목록이 쓰므로 남겼다.

### 5.3 연결 팩토리를 세션과 공유한다

Task 026은 `RedisConnectionFactory`를 `tikkit.auth.session-store=redis`일 때만 만들었다.
캐시가 들어오면서 조건이 하나 더 필요해졌다 — 로컬 개발에서 `session-store=memory` +
`cache.type=redis` 조합을 써야 한다(세션까지 Redis로 올리면 `repository-type: indexed`가
기동 시점에 `CONFIG SET`을 쏘므로 통합 테스트가 Redis를 기다린다).

`@ConditionalOnProperty`는 여러 개를 붙이면 AND로 묶이므로 `AnyNestedCondition`으로 OR를
만들었다. `SessionRedisConfig`는 `RedisConnectionConfig`로 합치고 Javadoc을 옮겼다.

`application.yml`의 `DataRedisAutoConfiguration` 제외는 **그대로 뒀다.** 제외를 풀면 프로퍼티
없이도 localhost:6379 팩토리가 생겨서 토글이 무력해진다.

### 5.4 기각한 것

이 Task에서 만들었다가 **측정 후 지운 것**이 셋이다. 코드는 지우고 수치는 여기 남긴다
(Task 020이 분산 락 실험을 정리한 방식과 같다).

- **좌석맵 배치 캐시와 쿼리 분리** (4.1~4.2). 적중률 99.97%에서도 캐시 없는 원본보다 느렸고,
  비용이 좌석 수에 비례해 커진다. `ScheduleSeatLayoutRow`, `findSeatLayoutByScheduleId`,
  `findSeatStatusesByScheduleId`를 지우고 단일 쿼리로 되돌렸다.
- **좌석맵 응답 바이트 캐시** (4.4). 적중률 26.7%에서 p95 차이가 0.01 ms였다.
  `SeatmapResponseCache`와 `tikkit.cache.seatmap-response-ttl` 토글을 지웠다.
- **Caffeine(로컬 캐시) 선택지** (4.1). 좌석맵에서는 가장 빨랐지만 그 대상이 사라졌다.
  남은 캐시는 전부 수백 바이트~수 KB라 직렬화를 피해 얻는 이득이 없고, 공연 파생값 evict는
  만료 배치가 `@SchedulerLock`으로 한 인스턴스에서만 돌아서 로컬 캐시면 **반쪽만 적용된다**
  (다른 BE가 TTL 60초까지 옛 `status`를 내려준다). 의존성과 `CacheType.CAFFEINE`을 지웠다.
  다시 재야 하면 의존성 한 줄을 되살리면 된다.

**남긴 것은 세 캐시와 인프라다.** 토글·직렬화 설정·적중률 지표·Grafana 패널은 Task 031
대기열이 Redis를 쓸 때 그대로 재사용된다.

`seatLayout`을 지우면서 따라온 변화가 하나 더 있다. 그 캐시가 있을 때는 회차 3,000개를 다
훑으면 Redis가 1.15 GB(384 KB × 3,000)까지 커져서 `maxmemory`가 **반드시** 필요했고, 세션과
같은 인스턴스를 쓰므로 축출 정책이 세션을 날릴 위험이 있었다. 지금은 캐시 전체가 수십 MB라
`maxmemory`는 OOM 가드로만 남는다 (8절).

## 6. 관문 — 캐시는 조용히 틀린다

이 Task에서 가장 많은 시간을 쓴 곳은 설계가 아니라 **"동작하는 것처럼 보이는데 아닌" 상태**를
찾아내는 일이었다. 네 개가 전부 예외도 경고도 없이 지나갔다.

### 6.1 TTL이 전혀 걸려 있지 않았다

`RedisCacheManager.builder()`를 이렇게 짰다.

```java
.cacheDefaults(base)
.withInitialCacheConfigurations(perCache)   // 이름별 TTL
.initialCacheNames(perCache.keySet())       // 미리 만들기
```

설정 코드만 읽으면 멀쩡하다. 그런데 Redis에서 `PTTL`을 찍으면 **네 캐시가 전부 `-1`(만료
없음)** 이었다. `initialCacheNames(names)`는 주어진 이름을 **현재 `cacheDefaults`로** 미리
만들기 때문에, 앞 줄에서 준 캐시별 TTL을 그대로 덮는다.

`withInitialCacheConfigurations`만으로도 그 이름들이 기동 시점에 만들어지므로
`initialCacheNames`를 지우면 끝이다. 문제는 그게 아니라 **확인하지 않으면 모른다**는 것이다.
이걸 못 잡았으면 "목록·상세는 TTL 60초"라고 이 문서에 적어두고 영구 캐시를 운영했을 것이다.
evict가 실패했을 때의 안전망이 없는 상태인데 없는 줄 모르는 쪽이 더 나쁘다.
`CacheTtlTest`가 설정이 아니라 **Redis 키의 남은 수명**을 직접 읽어 고정한다.

### 6.2 캐시 키에 SpEL의 `null` 문자열화를 기대했다

```java
key = "#category + '|' + #status + '|' + #page + '|' + #size"
```

`category`가 `null`일 때 `"null|ON_SALE|0|20"`이 될 거라고 생각했는데 아니었다. 캐시는
동작하는데 내가 기대한 키가 아니라서, 키를 직접 꺼내 보는 테스트가 깨졌다. Elvis로 `'-'`를
명시했다 — `(#category ?: '-')`. `'-'`는 enum 값에 나올 수 없는 문자라 `category=null`과
특정 카테고리가 같은 키로 뭉칠 수 없다.

### 6.3 타입 검증기가 `BigDecimal`을 막았다

`GenericJacksonJsonRedisSerializer`에 default typing을 켜려면
`PolymorphicTypeValidator`가 필요하다. `enableUnsafeDefaultTyping()`은 JSON에 적힌 어떤 클래스든
역직렬화하므로 쓰지 않았고(Redis에 쓰기 권한을 가진 쪽이 임의 클래스를 올리는 고전적인 gadget
경로다), 허용 목록을 우리 패키지와 `java.util.`로 좁혔다.

그러자 등급 가격 조회가 터졌다.

```
Could not resolve type id 'java.math.BigDecimal' as a subtype of java.math.BigDecimal:
Configured PolymorphicTypeValidator denied resolution
```

**타입 id는 final이 아닌 모든 값에 붙는다.** 우리 DTO만 허용하면 `BigDecimal`(가격)과
`Instant`·`LocalDate`(회차 시각, 공연 기간)에서 막힌다. `java.math.`와 `java.time.`을 더했다.

### 6.4 `clear()`와 `FLUSHDB`가 즉시 반영되지 않는다

테스트가 실행마다 다른 조합으로 깨졌다. 증상은 "캐시에 넣었는데 꺼내면 없다"였고 직렬화
문제로 한참 오진했다. 쓰기를 1,000회 재시도 루프로 재 보니 **put은 0~1회 안에 보인다** —
쓰기는 멀쩡했다.

범인은 **삭제가 늦게 도착하는 것**이었다. 키를 덤프해서 시간 순으로 비교하니, 앞 테스트의
`@BeforeEach`가 쏜 `FLUSHDB`가 다음 테스트가 캐시를 채운 **뒤에** 처리되면서 방금 넣은 값을
지우고 있었다. `Cache.clear()`도 같다 — 실측하면 clear 직후의 `get`이 값을 그대로 돌려주고
그 다음 호출에서야 사라진다.

테스트는 두 가지로 고쳤다. 하나는 **키 공간을 테스트마다 분리**하는 것 — 근본 원인이
`truncateAll()`의 `RESTART IDENTITY`라서 공연·회차 id가 매번 1부터 다시 시작하고 캐시 키가
재사용된다. id 구간을 1,000씩 밀면 늦게 도착한 삭제가 지난 테스트의 키만 건드린다. 다른 하나는
`clear()`를 트리거한 테스트가 **자기 안에서 완료를 기다리는** 것이다(`awaitCacheMiss`) —
`clear()`는 키 하나가 아니라 캐시 이름 전체를 패턴으로 지우므로 키 분리로는 막을 수 없다.

> [!warning] 확인 필요
> 왜 void를 반환하는 명령만 늦는지는 규명하지 못했다. 같은 커넥션에서 응답을 읽는 명령
> (`DBSIZE`)을 뒤에 붙여 순서를 강제해도 재현됐다. 운영에서는 문제가 되지 않는다 — evict를
> 거는 배치가 60초 주기이고 지연은 ms 단위다. 최악의 경우 evict 직후 채워진 값이 뒤늦은 삭제에
> 날아가는데 그건 **캐시 미스 한 번일 뿐 틀린 응답이 아니다.**

### 6.5 기본 비활성 토글의 히트 경로가 터져 있었다

응답 바이트 캐시(4.4)를 켜고 부하를 걸었더니 **진짜 에러율이 27%** 로 찍혔다. 캐시 값이
`byte[]`인데 그 캐시의 값 직렬화가 Jackson + default typing이어서, 넣을 때 base64 문자열로 한 번
더 감싸지고 꺼낼 때 `get(key, byte[].class)`가 타입 검사에 걸린 것이다.

**미스 경로는 멀쩡했다.** 그래서 기동도, 수동 요청 한 번도, 통합 테스트도 통과했다 —
깨지는 건 두 번째 요청부터였고 그게 적중률만큼의 비율로 섞여 나왔다.

고치는 건 한 줄이었다. `SerializationPair.byteArray()`를 쓰면 된다. 다만 `raw()`를 먼저 골랐다가
`Cached value is not of required type [[B]: java.nio.HeapByteBuffer`로 같은 자리에서 또 터졌다 —
이름이 비슷한 셋(`raw` / `byteArray` / `byteBuffer`) 중 `byte[]`를 그대로 주고받는 건
`byteArray()`뿐이다.

원인은 직렬화 설정이 아니라 **테스트를 쓰지 않은 것**이다. "기본값이 꺼져 있으니 나중에"라고
넘겼는데, 토글로 켜는 경로도 켜면 도는 코드다. 지웠지만(5.4) 그 전에 테스트를 먼저 썼다.

### 6.6 범위 밖: ShedLock이 테스트를 조용히 건너뛴다

evict 테스트에서 배치를 두 번 호출했는데 두 번째가 아무 일도 하지 않았다.
`@SchedulerLock(lockAtLeastFor = "55s")`가 정상 종료 후에도 55초간 락을 들고 있고,
`AbstractConcurrencyTest.truncateAll()`의 테이블 목록에 `shedlock`이 없어서 그 행이 남는다.
증상이 "evict가 안 됐다"로 나타나 캐시 문제로 오진하기 쉽다. 테스트에서 `DELETE FROM shedlock`을
넣어 해결했다.

## 7. 수치

*(측정 후 채움)*

## 8. 메모리 — 좌석맵을 뺀 뒤로는 문제가 아니다

처음 설계에서 `maxmemory`는 **반드시** 필요한 설정이었다. `seatLayout` 항목이 Redis 할당 기준
384 KB(`strlen` 337,885 B)라서, 부하 데이터의 회차 3,000개가 TTL 10분 안에 다 올라오면
**1.15 GB**가 된다. 상한이 없으면 Redis가 OOM으로 죽고 그러면 **세션까지 같이 날아가 전 사용자가
로그아웃된다**([005](005-session-scaling.md) 9절의 "단일 Redis" 한계가 그대로 커진다).

그 캐시를 기각하면서(4.2) 숫자가 달라졌다. 남은 캐시는 `performanceList` 690 B,
`performanceDetail` 수 KB, `gradePrices` 수백 B다. 카탈로그 전체가 올라와도 수십 MB고,
실측에서도 측정 전 구간 피크가 **43.3 MB**였다(`maxmemory` 512 MB, `evicted_keys` **0**).

그래서 지금 `maxmemory`는 **OOM 가드**로만 남는다. `allkeys-lru`의 대가(**세션 키도 축출
대상이다**)는 상한에 닿지 않으므로 발동하지 않는다. 다만 Task 031 대기열이 ZSET을 올리면
메모리가 다시 커지므로 그때 세션과 분리할지 판단한다.

**분리가 공짜가 아닌 이유**를 같이 적어둔다. 캐시 전용 Redis를 띄우면 `RedisConnectionFactory`
빈이 둘이 되고, Spring Session 자동설정의 `@ConditionalOnBean(RedisConnectionFactory.class)`가
캐시 쪽 팩토리에도 걸린다. 즉 **"캐시만 Redis로 켠다"가 세션까지 켜버린다** — Task 026이 만든
"프로퍼티 하나로 세션 저장소 전체가 켜지고 꺼진다"는 구조가 깨진다. 논리 DB를 나눠도 해결되지
않는다(축출은 인스턴스 단위다). 분리하려면 자동설정 조건을 우리가 다시 설계해야 한다.

## 9. 한계

- **이 측정 구간에서는 캐시의 이득이 원래 보이지 않는다.** 006 기준으로 BE CPU 최대 4.2%,
  Hikari `pending` 0인 저부하다. 지연을 지배하는 건 요청당 고정비이고 DB 작업을 줄인 효과는
  p95에 드러나지 않는다. **그래서 채택 근거가 지금 수치가 아니라 용량이다**(4.6) — 그 말은
  이 Task가 채택을 수치로 입증하지 못했다는 뜻이기도 하다. 검증은 Task 033이다.
- **캐시도 워밍업 대상인데 처음에 몰랐다.** 20초 워밍업으로 쟀더니 탐색 p95가
  4.99 → 3.60 → 2.69 ms로 계속 내려갔다. 측정 사이에 DB는 리셋하지만 캐시는 TTL까지 남아서,
  회차를 거듭할수록 적중률이 올라간 것이다. 워밍업을 본 측정과 같은 길이(90초)로 늘려
  "연속 3회가 평탄해질 때까지"를 조건으로 다시 쟀다. **캐시 없는 A군이 20초로 평탄했던 것은
  JVM만 데우면 됐기 때문이다** — 저장소를 켠 군과 끈 군의 워밍업 요구가 다르다.
- **적중률은 시나리오 구간으로 잘라 읽었다.** `cache_gets_total`은 인스턴스 기동 후 누적이라
  시나리오별로 가르려면 Prometheus에서 시간 창을 잡아야 한다(`increase(...)`). 창 경계가
  워밍업과 겹치면 값이 흔들리므로 소수점까지 믿을 값은 아니다.
- **`before/after` 조건이 완전히 같지 않다.** `reservations`가 006 당시 13,798행 → 007 약
  43,000행 → 이번 측정 시작 55,429행이고, 예매 시나리오를 돌릴 때마다 더 쌓였다(리셋이 삭제가
  아니라 `CANCELLED`다). A군을 같은 세션에서 다시 재서 007보다는 조건을 맞췄지만, A와 최종군
  사이에도 예매 측정 6회만큼의 드리프트가 있다.
- **좌석맵 기각의 근거가 "값이 크면 캐시가 손해"까지만이다.** 어디서 뒤집히는지(몇 KB부터
  이득인지)는 재지 않았다. 항목 크기를 바꿔가며 쟀어야 경계를 말할 수 있다.
- **로컬 캐시의 2대 불일치를 실측하지 않았다.** Caffeine을 기각한 근거 중 "배치 evict가 한
  인스턴스에만 적용된다"는 코드 구조에서 나온 판단이고, Task 026이 세션 불일치를 재현한 것처럼
  compose 2대로 재현하지는 않았다. 기각 결론이 바뀌지 않아서 더 파지 않았다.
- **`Cache.clear()`가 왜 늦게 반영되는지 규명하지 못했다**(6.4). 테스트는 키 공간 분리와
  폴링으로 돌아가게 했고, 운영에서는 evict 지연이 ms 단위라 캐시 미스 한 번으로 끝난다.
- **편중 분포 수치는 기준선과 비교할 수 없다.** `browse-skewed.js`는 이 Task에서 새로 만든
  스크립트라 006·007이 같은 분포로 잰 값이 없다. 균일 분포와 나란히 두는 용도로만 읽는다.
  쏠림 파라미터(상위 2페이지 80%)도 실제 트래픽에서 측정한 값이 아니라 가정이다.
- **응답 바이트 캐시의 적중률이 낮은 조건에서만 측정했다**(4.4). TTL을 늘리면 적중률이 올라가
  결과가 달라질 수 있지만, 그쪽은 stale 창이 더 커져서 운영 후보가 아니다. 그래서 "이 TTL에서
  이득이 없다"까지만 말할 수 있다.
- **범위 밖 발견**: 테스트 컨텍스트가 늘면서 **Postgres 커넥션이 고갈됐다.** 캐시 토글 조합마다
  스프링 컨텍스트가 하나씩 생기는데 `AbstractConcurrencyTest`가 Hikari 풀을 30으로 올려 둬서
  기본 `max_connections`(100)를 넘겼고, 증상이 캐시와 무관한 동시성 테스트에서
  `FATAL: sorry, too many clients already`로 나타났다. 캐시 테스트의 풀을 5로 줄여 해결했지만,
  **컨텍스트를 더 추가하면 같은 벽에 다시 부딪힌다** — 근본 해법은 테스트 컨테이너의
  `max_connections`를 올리는 것이다.

## 10. 다음 단계

- **[Task 030] FE 렌더링**: 좌석맵 응답이 여전히 **237.9 KB / 2,000석**이다. 이 Task가 캐싱으로
  풀어보려다 실패한 문제고(4.2~4.4), 응답 시간의 약 30%가 캐시로 줄일 수 없는 직렬화·전송
  구간이다. **크기를 줄이는 것이 유일하게 방향이 맞는 해법이다.**
- **[Task 033] 오픈런 부하 검증**: 이 Task의 채택 근거가 거기서 검증된다. 재야 할 것 셋 —
  ① 캐시 on/off의 **한계 처리량**(p95가 아니라 포화점), ② 포화 구간에서 좌석맵 캐시가 뒤집히는지
  (DB가 병목이면 역직렬화 비용을 내고도 이득일 수 있다, 4.2의 warning), ③ `browse-skewed`를
  상승 부하로 돌려 적중률이 처리량에 미치는 영향.
- **[Task 031] Redis 대기열**: 이 Task가 남긴 인프라를 그대로 쓴다 — 연결 팩토리
  (`RedisConnectionConfig`, 세션·캐시 OR 조건에 대기열 조건을 더하면 된다), 직렬화 설정,
  적중률 지표 패턴. 메모리는 그때 다시 본다(8절).
- **[미배정] 캐시가 이득이 되는 항목 크기의 경계**: 690 B는 이득, 337,885 B는 손해라는 두 점만
  있다. 그 사이 어디서 뒤집히는지 모른다. 새 캐시를 넣을 때마다 판단이 필요해지므로 한 번
  재두면 규칙이 된다.
- **[미배정] 테스트 컨테이너 `max_connections`**: 9절 마지막 항목. 지금은 캐시 테스트 풀을 줄여
  피했을 뿐이고, 컨텍스트가 하나 더 늘면 다시 깨진다.
- **[미배정] 쿼리 카운트를 CI에 걸지 않았다**: 007이 남긴 그대로다. 이번에 테스트가 10개에서
  늘었지만 여전히 `./gradlew test`에 섞여 돌 뿐이고, "문장 수가 늘었다"를 PR에서 눈에 띄게
  만드는 장치는 없다.

**범위 밖에서 발견한 것**:

- **`@Cacheable`은 캐시를 꺼도 비용이 있다**(4.6). `NoOpCacheManager`여도 인터셉터는 매 호출마다
  돌고 프록시 홉이 붙는다. 탐색 p95가 캐시 off 상태에서 2.20 → 2.63 ms였다. 캐시 적용 범위를
  넓힐 때 "꺼두면 공짜"라고 생각하면 안 된다.
- **기본 비활성 토글에도 테스트가 필요하다**(6.6). 응답 바이트 캐시는 기본값이 꺼져 있어서
  테스트를 쓰지 않았고, 그래서 **히트 경로가 깨진 걸 부하 측정에서야 알았다**(진짜 에러 27%).
  미스는 멀쩡했기 때문에 기동도 스모크도 통과했다.

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (007 6절). PowerShell이면 생략.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 1. 테스트 — 캐시 off 기준값과 Redis 컨테이너를 띄우는 캐시 테스트가 같이 돈다
cd tikkit-back && ./gradlew test
#   *QueryCountTest       : 캐시 off의 문장 수 (목록 1/2, 상세 2, 등급 2, 좌석맵 2)
#   CacheQueryCountTest   : Redis 캐시의 미스/히트 문장 수 + 적중률 지표
#   CacheTtlTest          : PTTL을 직접 읽어 캐시별 TTL 확인 (6.1절)
#   CacheSerializationTest: record·BigDecimal·Instant 왕복
#   CacheEvictionTest     : 배치가 파생값을 바꿀 때만 evict
#   CaffeineCacheTest     : 로컬 캐시 경로 (Redis 없이 돈다)

# 2. 스택 기동 + 대량 데이터 (006 "재현 방법" 1~3번과 동일)
cd .. && docker compose -f docker-compose.prod.yml --profile monitoring up -d --build

# 3. 군별 측정. CACHE_TYPE을 바꿀 때마다 backend를 재생성하고 nginx를 재시작한다
#    (안 하면 nginx가 옛 IP를 물고 502를 뱉는다 — 005의 함정)
CACHE_TYPE=redis docker compose -f docker-compose.prod.yml up -d backend1 backend2
docker compose -f docker-compose.prod.yml restart nginx

# 4. 시나리오마다 **워밍업을 먼저 돌리고 버린다** (2.2절). 이게 없으면 1회차가 2~3ms 높다
docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps \
  -e DURATION=20s -e RAMP_UP=5s -e RAMP_DOWN=5s \
  k6 run -o experimental-prometheus-rw /scripts/seatmap.js

# 5. 본 측정 3회, 사이에 98-reset
K6_RUN_LABEL=t029-d-redis docker compose -f docker-compose.prod.yml --profile load run --rm --no-deps \
  k6 run -o experimental-prometheus-rw --summary-export=/results/t029-d-redis-seatmap-1.json \
  /scripts/seatmap.js
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f - < load-test/sql/98-reset-reservations.sql

# 6. 적중률과 메모리
curl -s localhost:8080/actuator/prometheus | grep cache_gets
docker exec tikkit-redis-prod redis-cli info memory | grep used_memory_human
docker exec tikkit-redis-prod redis-cli --scan --pattern 'tikkit:cache:*' | head
docker exec tikkit-redis-prod redis-cli pttl 'tikkit:cache:performanceDetail::1'
```

Grafana(`localhost:3001`)의 **캐시 적중률**과 **좌석맵 생성 시간 vs 전체 응답 p95** 패널을
같은 타임라인으로 본다. 적중률 시리즈가 아예 없으면 `CACHE_TYPE=none`이거나
`enableStatistics()`가 빠진 것이다.
