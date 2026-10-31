# 005. 세션 확장 — 서버를 2대로 늘리는 순간 드러난 것들 (Task 026)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 026 (Phase 7: 운영 기반) |
| 기간 | 2026-10-12 |
| 결론 | **Redis 세션 채택.** JWT는 더 빠르지만(p95 1.72ms vs 3.76ms) 로그아웃을 서버에서 강제할 수 없고, 제대로 하려면(refresh 토큰 + 폐기 목록) 결국 Redis가 필요해서 기각 |
| 정정 | 처음 쓴 채택 근거("권한 회수가 불가능하다")가 과장이었다. 리뷰 지적을 받아 8.1에 정정을 남겼다 |
| 태그 | `v0.4.0-ops` |

---

## 1. 왜 만들었나

Task 008에서 인증을 **세션 기반(`HttpSession`)** 으로 만들었다. 그때 이유는 분명했다 —
서버가 1대인 MVP에서는 JWT보다 구현이 단순하고, 확장 문제는 나중에 다룬다
([ROADMAP](../ROADMAP.md) Task 008).

그 "나중"이 Task 024에서 명시적 제약으로 굳었다. `docker-compose.prod.yml`을 만들면서
알려진 한계에 이렇게 적혔다.

> **BE는 1개뿐**이고 2개로 늘리면 `@EnableScheduling` 배치가 두 번 돈다(Task 026에서 ShedLock 류 필요)

Task 025는 더 적극적이었다. 분위수를 `percentiles`가 아니라 `percentiles-histogram`으로
내보낸 이유가 이것이었다.

> 분위수는 수학적으로 합산이 불가능해서 BE가 2대가 되는 Task 026에서 쓸 수 없다

`application-prod.yml:41`은 `/actuator` 외부 차단을 "Task 026에서 nginx 투입"으로 넘겼고,
`monitoring/prometheus/prometheus.yml`에는 "BE를 2대로 늘리면 `backend2:8080`을 한 줄 더
적는다"가 주석으로 박혀 있었다.

**앞선 세 Task가 이 Task를 위해 자리를 비워뒀다.** 이번에 그 자리를 채운다.

---

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| 스택 | `docker-compose.prod.yml` — nginx 1 + BE 2 + FE 1 + PostgreSQL 15 + Redis 7.4 + Prometheus + Grafana |
| 호스트 | Windows 11, Docker Desktop 28.5.1 (WSL2) |
| 부하 | `curl` + `xargs` 병렬 20, 60초 (k6는 Task 027) |
| 측정 대상 | `GET /api/v1/members/me` — 인증이 필요한 가장 단순한 엔드포인트 |
| 지표 | Prometheus `scrape_interval: 10s`, `http.server.requests` 버킷 1ms~10s |

> [!warning] 1ms 미만 값은 값이 아니다
> 버킷 하한이 1ms라 그보다 빠른 응답은 전부 최하위 버킷에 들어가고, `histogram_quantile`이
> `[0, 1ms]`를 선형 보간한다. Task 025 4.1절이 겪은 함정 그대로다. 아래 표에서 `memory`의
> p50(0.88ms)과 401의 p95(0.95ms)가 여기 걸린다 — **"1ms보다 빠르다"까지만 읽어야 한다.**

측정은 전부 로컬 Docker 기준이고 표본이 적다. **절대값이 아니라 전략 간 상대 비교**로만 읽는다.

---

## 3. 재현 — 같은 쿠키인데 200과 401이 섞인다

`backend` 서비스 하나를 `backend1`/`backend2`로 쪼개고 앞에 nginx(라운드로빈)를 세웠다.
로그인 1회 → 같은 `JSESSIONID`로 `/members/me` 40회.

```
1차:  200 20건 / 401 20건
2차:  200 26건 / 401 14건
```

정확히 반씩은 아니다. frontend 헬스체크(10초 주기)가 같은 upstream의 라운드로빈 순번을
같이 소비해서 교대가 밀린다. **중요한 건 비율이 아니라 "같은 쿠키가 갈린다"는 사실이다.**

nginx 액세스 로그에 `$upstream_addr`과 `$cookie_JSESSIONID`를 넣으니 한 줄로 증명됐다.

```
"GET /api/v1/members/me HTTP/1.1" 200 upstream=172.21.0.6:8080 sess=4B664E7C072B661D1131E47508CA32CA  (26건)
"GET /api/v1/members/me HTTP/1.1" 401 upstream=172.21.0.5:8080 sess=4B664E7C072B661D1131E47508CA32CA  (14건)
```

`172.21.0.6`은 backend1, `172.21.0.5`는 backend2다. **상관관계가 1:1이다** — 세션을 만든
인스턴스는 전 요청 200, 다른 쪽은 전 요청 401이다.

### 3.1 이 프로젝트에서는 "브라우저와 BE 사이"가 아니다

구조를 짚어둘 필요가 있다. 브라우저는 BE를 직접 호출하지 않는다. Next 서버가 로그인 응답의
`Set-Cookie`를 파싱해 자기 도메인 쿠키로 저장하고, 이후 요청마다 `Cookie` 헤더로 중계한다
(`tikkit-front/lib/session.ts`, `lib/api/client.ts`). 그래서 경로가 이렇게 된다.

```
브라우저 → nginx → frontend(Next) → nginx → backend1 또는 backend2
```

한 요청이 nginx를 **두 번** 지난다. 불일치가 생기는 지점은 "Next 서버와 BE 사이"다.
이 구조가 뒤에서 sticky session을 기각하는 결정적 이유가 된다.

### 3.2 실제 앱에서도 깨진다

API 수치만으로 끝내지 않고 Playwright e2e를 prod 스택 대상으로 돌렸다.

```
2 passed, 2 failed
  ✗ booking-flow.spec.ts  가입 → 로그인 → 좌석 선택 → 선점 → 결제 → 취소
  ✗ screenshots.spec.ts   README용 주요 화면 캡처
```

둘 다 같은 자리에서 깨졌다 — 로그인 직후 `getByRole("button", { name: "로그아웃" })`이
보이지 않는다. 로그인은 성공했는데 다음 요청이 다른 인스턴스로 가서 "로그인 안 된 상태"로
렌더링된 것이다. 사용자 눈에는 "로그인했는데 새로고침하면 풀린다"로 보인다.

### 3.3 부하를 걸면 성공률이 46%다

60초 부하(병렬 20, 3,900건)에서:

| 항목 | 값 |
|---|---|
| 200 비율 | **46.3%** |
| backend1 | 200 약 1,357건 / 401 약 2건 |
| backend2 | **401 약 1,574건** (200은 0건) |

Grafana에 패널을 하나 추가했다 — `sum by (instance, status) (increase(...))`.
한쪽 인스턴스가 401만 내는 것이 그래프로 그대로 보인다.

### 3.4 배치도 양쪽에서 돈다 (그런데 데이터는 안 깨진다)

만료 대상 예약을 하나 만들고(`expires_at`을 과거로) 배치를 기다렸다.

```
10:33:06.662  backend2  선점 만료 배치 실행 — 반환된 좌석 수: 2
10:33:06.694  backend1  선점 만료 배치 실행 — 반환된 좌석 수: 0
```

32ms 차이로 양쪽이 돌았고 **두 번째는 0건**이다. 만료 쿼리 두 개가 모두 멱등이기 때문이다.

- `expirePendingReservations` — `WHERE status='PENDING' AND expires_at <= :now`
- `recalculateDerivedFields` — `WHERE (p.status, p.start_date, p.end_date) IS DISTINCT FROM (...)`

Task 019가 "조건부 UPDATE로 쓰고 ORM의 상태 관리를 안 믿는다"고 설계한 것이
여기서 안전망으로 작동했다. **의도한 효과는 아니었지만 공짜로 받은 보험이다.**

---

## 4. 핵심 발견

### 4.1 sticky session은 이 구조에서 둘 다 못 쓴다 — 그런데 이유가 서로 다르다

"코드 0줄 해법"을 먼저 재봤다. `nginx.conf`의 upstream에 한 줄만 추가하는 것이다.

**(1) `ip_hash` — 불일치는 사라지지만 로드밸런싱이 소멸한다**

같은 쿠키 40회가 전부 200이 됐다. 그런데 분포를 보니:

```
클라이언트 172.21.0.1 (호스트)      20회 → 172.21.0.6 (backend1)
클라이언트 172.21.0.8 (frontend)   22회 → 172.21.0.6 (backend1)
backend2가 받은 요청: 0건
```

처음 세운 가설은 "클라이언트가 Next 서버 하나뿐이라 한쪽으로 쏠린다"였다. **틀렸다.**
클라이언트 IP가 둘인데도 같은 인스턴스로 갔다. nginx 공식 문서가 답을 준다.

> The first three octets of the client IPv4 address, or the entire IPv6 address, are used as a hashing key.

**앞 3옥텟만 쓴다.** Docker compose 네트워크가 `/24` 하나라 그 안의 모든 컨테이너와 호스트가
같은 버킷으로 간다. 클라이언트가 몇 개인지와 무관하게 한 대로 쏠린다 —
BE를 2대 둔 이유가 100% 사라진다.

거기에 세션이 그 한 대에 묶인다. `docker compose stop backend1` 하니:

```
중단 전: 200 10/10
중단 후: 401 10/10
재시작 후: 401 10/10   ← 세션이 영구히 사라졌다
```

**(2) `hash $cookie_JSESSIONID consistent` — 라운드로빈보다 나쁘다**

세션 쿠키 단위로 고정하면 IP 문제가 없을 거라 봤다. 결과는 **401이 20/20**이었다.
라운드로빈(50% 실패)보다 못하다. 로그가 이유를 바로 보여준다.

```
"POST /api/v1/auth/login"      200 upstream=172.21.0.6 sess=-                                 ← 쿠키가 없다
"GET  /api/v1/members/me"  20x  401 upstream=172.21.0.5 sess=DEE166EEE172E3AF95C774B1D7AC8B9E  ← 다른 버킷
```

**세션을 만드는 요청에는 아직 그 세션 쿠키가 없다.** 로그인은 빈 값이 해시돼 backend1로
가서 거기에 세션을 만들고, 이후 요청은 발급된 쿠키 값으로 해시돼 backend2로 간다.
순서 문제라서 설정으로는 못 고친다(`AuthController`의 `changeSessionId()`도 같은 방향으로 작용한다).

"가장 단순한 해법을 먼저"를 지킨 덕에 **왜 안 되는지를 수치로 말할 수 있게 됐다.**
그냥 Redis로 갔으면 "sticky는 왜 안 썼냐"에 답할 근거가 없었다.

### 4.2 Spring Session은 의존성만 넣으면 끌 수 없다

전략을 런타임에 갈아끼우려면 Redis 세션을 켜고 끌 수 있어야 했다. 그런데 Boot 4에는
**저장소 선택 프로퍼티도, 비활성화 스위치도 없다.** 공식 문서가 이렇게 설계를 말한다.

> For each data store, a specific Spring Boot starter is provided. … The servlet
> auto-configuration replaces the need to use `@Enable*HttpSession`.

즉 **스타터를 넣는 것 자체가 선택**이다. 실제 활성 조건은 자동설정의
`@ConditionalOnBean(RedisConnectionFactory.class)`인데, `DataRedisAutoConfiguration`이
프로퍼티가 없어도 `localhost:6379` 기본값으로 그 빈을 만들어버린다.

그래서 이렇게 풀었다.

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
```

그리고 `tikkit.auth.session-store=redis`일 때만 `SessionRedisConfig`가 `LettuceConnectionFactory`를
직접 만든다. Spring Session 쪽 `@ConditionalOnBean`이 그걸 따라오므로 **프로퍼티 하나로
전체가 켜지고 꺼진다.** Redisson이 스타터를 피한 것([003](003-redis-distributed-lock.md) 3절)과
같은 패턴이고, 같은 이유로 안전하다.

**함정**: 자동설정을 제외하니 전혀 다른 곳이 깨졌다.

```
NoSuchBeanDefinitionException: No bean named 'redisTemplate' available
  Error creating bean with name 'redisReferenceResolver'
```

`DataRedisRepositoriesAutoConfiguration`이 Spring Data Redis 리포지토리 지원을 올리면서
`redisTemplate`을 **이름으로** 찾는데, 그 빈을 만들던 게 방금 제외한 자동설정이다.
증상 메시지는 세션과 아무 관계없어 보인다. 우리는 `@RedisHash`를 쓰지 않으므로 같이 제외했다.

### 4.3 쿠키 이름을 통합 테스트로 검증할 수 없었다

Spring Session의 기본 쿠키명은 `SESSION`이다. FE가 `/JSESSIONID=([^;]+)/`로 파싱하므로
(`tikkit-front/lib/api/auth.ts`) 이름이 바뀌면 **로그인이 조용히 실패한다** —
응답은 200이고 쿠키만 저장되지 않는다. 그래서 `server.servlet.session.cookie.name: JSESSIONID`를
명시했다.

그런데 MockMvc 테스트에서는 여전히 `SESSION`이 나왔고 `HttpOnly`·`SameSite`도 빠졌다.
Boot 소스를 보니 이유가 있었다. `SessionAutoConfiguration`은 쿠키 프로퍼티를
`DefaultCookieSerializer`로 매핑하는 코드를 **`EmbeddedWebServerConfiguration`**
(`@ConditionalOnNotWarDeployment`)에만 두고, WAR 분기는 대신
`ServletContext.getSessionCookieConfig()`를 읽는다.

`@SpringBootTest`의 MOCK 환경은 임베디드 서버가 없어 **WAR 분기를 탄다.** `MockServletContext`의
빈 설정이 쓰이는 것이다. 실제 컨테이너에서는 정상이었다.

```
Set-Cookie: JSESSIONID=NGU3YjQzYmUtNzlkMy00ODIyLWE1YWQtNDkzMzk3ZTZjNTM0; Path=/; HttpOnly; SameSite=Lax
```

그래서 테스트는 쿠키를 이름으로 찾지 않고, **쿠키명 회귀는 FE 로그인을 그대로 태우는 e2e에 맡겼다.**
여기서 교훈은 "테스트가 통과했다"와 "실제로 동작한다"가 갈리는 조건이 있다는 것이고,
그 조건이 하필 프레임워크 자동설정의 분기였다.

### 4.4 004_2가 예고한 문제는 실재하지 않았다

Task 024가 넘긴 항목 중에 이런 게 있었다.

> `proxy.ts`의 `new URL("/login", request.url)`이 `X-Forwarded-Host`를 안 보고 컨테이너 내부
> host로 리다이렉트하는 문제가 터질 수 있다

실측해보니 터지지 않았다. Location 헤더가 **상대 경로**로 나온다.

```
$ curl -i http://localhost/my/reservations
HTTP/1.1 307 Temporary Redirect
location: /login?redirect=%2Fmy%2Freservations
```

`Host` 헤더를 바꿔 보내도, nginx를 우회해 컨테이너에 직접 쳐도 상대 경로다. Next가
같은 origin인 리다이렉트를 상대 경로로 바꿔 내려보내기 때문에, 호스트를 무엇으로 추정했든
브라우저가 자기가 방문한 origin 기준으로 해석한다. e2e 2개도 그대로 통과한다.

**예고가 틀렸다기보다, 왜 안 터졌는지를 확인한 것이 결과다.** 넘겨받은 경고를 측정 없이
"고쳐두는" 커밋을 하지 않은 것이 이번의 수확이다.

### 4.5 `$host`는 포트를 버린다 — Server Action이 500으로 터질 자리

처음 nginx 설정에 `proxy_set_header Host $host;`를 썼다. 지금은 문제가 없는데
구조적으로 위험하다.

Next의 Server Action은 `origin` 헤더와 `host`(또는 `x-forwarded-host`)를 비교해서 다르면
`Invalid Server Actions request.`로 **500**을 낸다. 로그인·로그아웃이 전부 Server Action이다.
`$host`는 포트를 버리므로(`$http_host`는 포함) nginx를 80이 아닌 포트로 옮기는 순간
`origin: localhost:8081` vs `x-forwarded-host: localhost`가 되어 **로그인 자체가 깨진다.**
그리고 에러 메시지로는 원인에 도달하기 어렵다.

로컬 80 포트 충돌은 흔한 일이라 미리 `$http_host`로 바꿨다. 80에서는 두 값이 같으므로
동작 변화는 없다.

### 4.6 "커넥션 재사용 때문에 재현이 약해진다"는 내 가정이 틀렸다

처음 `nginx.conf`에 이렇게 적었다.

> keepalive를 일부러 주지 않는다. 커넥션을 재사용하면 Next 서버의 fetch가 한쪽 BE에 계속
> 붙어서 세션 불일치가 "간헐적으로만" 보인다.

**틀렸다.** nginx는 HTTP/1.1 프록시에서 **요청 단위로** 업스트림을 고른다. Next의 fetch가
커넥션을 유지해도 라운드로빈은 요청마다 돈다.

실제 영향은 반대쪽이다. `keepalive`가 없으면 nginx→BE가 매 요청 새 TCP라서 **핸드셰이크
비용이 지연 측정에 섞인다.** 004_3의 좌석맵 p95가 3.07ms 스케일인 서비스에서는 무시할 수 없다.
이번 측정은 전 전략을 keepalive 없이 통일해서 비교의 공정성은 유지했지만, **절대값에는
그 비용이 포함돼 있다.**

### 4.7 대시보드가 BE 2대에서 조용히 틀린 값을 낸다

Task 025가 겪은 "그래프도 숫자도 단위도 멀쩡해 보이면서 틀린다"가 그대로 재발했다.

| 패널 | 2대에서 | 조치 |
|---|---|---|
| 프로세스 CPU | `sum(process_cpu_usage)` → 최대 **2.0** (축은 max:1) | `sum()` 제거, instance별 시리즈 |
| Hikari `max` | `sum(...)` → **20** (풀은 10) | `max(...)` |
| Hikari active/idle/pending | 합산만 보여 한쪽 포화를 못 본다 | `sum by (instance)` |
| JVM 힙·스레드 | 합산만 보여 한쪽 누수를 못 본다 | `sum by (instance)` |
| HTTP p95 | **정상** — 버킷 합산은 수학적으로 맞다 | 그대로 |
| 예매 카운터 | **정상** — Counter 합산은 맞다 | 그대로 |

p95가 멀쩡한 것은 Task 025가 `percentiles` 대신 `percentiles-histogram`을 고른 결과다.
**그때 "Task 026에서 못 쓴다"고 적어둔 판단이 실제로 값을 했다.**

---

## 5. 실제로 고친 것

### 5.1 인프라 — BE 2대 + nginx

`docker-compose.prod.yml`의 `backend` 하나를 `backend1`/`backend2`로 쪼갰다.

- **`container_name`을 뺐다.** 고정명은 애초에 복제가 불가능하다(그래서 Task 024가
  "BE는 1개뿐"이라는 한계를 남겼다). 이제 compose가 `tikkit-prod-backend1-1` 식으로 붙인다
- **`--scale`이 아니라 서비스 2개를 명시 선언했다.** `--scale`은 같은 서비스명 하나에 IP가
  여러 개 붙어서 Prometheus `static_configs`의 `instance` 라벨이 갈리지 않는다.
  그러면 "어느 인스턴스가 세션을 들고 있나"를 대시보드에서 구분할 수 없고, 그게 이 Task의 측정 전부다
- **환경변수·헬스체크를 YAML 앵커로 공유했다.** 한쪽만 다르면 "세션이 어디 있나"가 아니라
  설정 차이를 측정하게 된다. 구조적으로 막았다
- **BE·FE 호스트 포트를 닫았다.** 외부 진입점은 nginx(80) 하나다 —
  "`/actuator/prometheus`가 호스트에 열려 있다"는 004_3 9절의 지적이 이걸로 닫힌다

nginx는 경로로 분기한다.

```nginx
location /actuator { return 404; }                 # 외부 차단
location /api/     { proxy_pass http://tikkit_backend; }   # backend1/backend2
location /         { proxy_pass http://tikkit_frontend; }  # Next
location = /healthz { return 200 "ok\n"; }         # 업스트림을 타지 않는 헬스체크
```

의존성 순서에 함정이 하나 있었다. `frontend`의 헬스체크가 `/` 페이지를 받아보는데, 그 페이지는
Server Component가 `API_BASE_URL`(=nginx)로 BE를 호출한다. 즉 **frontend가 healthy해지려면
nginx가 먼저 떠 있어야 한다.** nginx의 `depends_on`을 `frontend: service_healthy`로 두면
서로를 기다리는 교착이 된다. `service_started`로 두고(컨테이너가 있으면 DNS 이름은 해석된다)
frontend의 `start_period`를 15s → 30s로 늘렸다.

> [!warning] nginx는 upstream 호스트명을 기동 시 1회만 해석한다
> `resolve` 파라미터를 안 썼으므로 backend 컨테이너를 재생성하면 nginx가 옛 IP를 물고 **502**를
> 뱉는다. 전략을 바꿔 컨테이너를 다시 만들 때 `restart nginx`를 빼먹으면
> "Redis 세션이 안 된다"로 오진한다. 측정 절차에 고정 스텝으로 넣었다.

Prometheus는 nginx를 **거치지 않고** `backend1:8080`, `backend2:8080`을 직접 긁는다.
프록시를 거치면 (a) `/actuator`가 404이고 (b) 스크레이프가 두 인스턴스 중 한쪽으로만 가서
`instance` 라벨이 매번 바뀐다.

### 5.2 전략 토글

`tikkit.auth.session-store` 프로퍼티(`memory` | `redis`)를 만들고, 전략 선택을
**`AuthStrategyConfig`의 switch 한 곳**에 모았다.

전략별 설정 클래스를 `@ConditionalOnProperty`로 흩지 않은 이유: 전략은 "SecurityContext를
어디서 읽는가 + 로그인 때 무엇을 남기는가 + 세션 정책"이 한 세트다. 빈마다 조건을 달면
**조합이 어긋난 상태(예: `STATELESS`인데 세션 저장소를 쓰는)로도 기동이 된다.**
한곳에 모으면 컴파일러가 enum 분기 누락을 잡아준다.

`AuthController.login/logout`에 인라인이던 세션 조작(`changeSessionId()`, `saveContext()`,
`SecurityContextLogoutHandler`)은 `LoginCredentialWriter`로 뽑았다. 컨트롤러는
`writer.write(authentication, req, res)` 한 줄이 됐고, **JWT를 붙이고 떼는 동안 한 줄도
바뀌지 않았다.**

`MEMORY`와 `REDIS`가 **같은 구현을 쓴다.** Spring Session이 HttpSession 구현만 갈아끼우므로
세션 API를 부르는 코드는 저장소를 모른다. 이게 비교표의 "코드 변경량"에서 JWT와 갈리는 지점이다.

### 5.3 ShedLock

- provider는 Redis가 아니라 **JDBC**. (a) JWT를 채택하면 Redis가 스택에서 사라지는데
  Redis provider면 그때 배치가 같이 깨진다 — DB는 어느 전략에서도 있다.
  (b) JDBC provider만 `usingDbTime()`을 제공한다. 락 판정에 **DB 서버 시계**를 써서
  "노드 시계 차이가 `lockAtLeastFor`보다 크면 중복 실행된다"는 ShedLock의 알려진 함정을
  원천에서 없앤다
- 버전 **7.10.1**. README 호환 매트릭스에서 Spring Boot 4.x를 테스트한 라인이 7.x뿐이다(6.x는 3.5/3.4까지)
- **`lockAtLeastFor = 55s`가 핵심이다.** 작업이 수십 ms에 끝나므로 락을 즉시 놓으면 같은 주기
  안에서 다른 인스턴스가 다시 집어서 **중복 실행이 "해결된 척" 하다가 그대로 남는다**
- `lockAtMostFor = 5m`. 락을 들고 죽었을 때 풀리는 시한이다. 작업 시간보다 한참 길게 잡아
  "느린 실행"을 "죽음"으로 오판하지 않게 했다
- 마이그레이션 번호를 V7이 아니라 **`V6_1`** 로 붙였다. V7(인덱스, Task 028)과
  V8(`queue_enabled`, Task 031)이 [ERD](../ERD.md) 이력 표와 ROADMAP에 예약돼 있어 번호를
  밀면 두 문서를 같이 고쳐야 한다. `V3_1`·`V3_2`와 같은 서브번호 관행이다

### 5.4 대시보드

4.7의 집계 오류를 고치고 패널을 하나 추가했다.

```promql
sum by (instance, status) (increase(http_server_requests_seconds_count{uri="/api/v1/members/me"}[1m]))
```

이 Task의 측정 계기다. 해결 전에는 한쪽 instance가 401만 내는 것이, 해결 후에는 그 시리즈가
사라지는 것이 그래프로 보인다.

---

## 6. 관문 — 두 인스턴스가 같은 DB에 Flyway를 동시에 돌린다

범위 밖이지만 확인해야 했던 것. `compose up`에서 backend1과 backend2가 **동시에** 기동하면서
같은 PostgreSQL에 마이그레이션을 적용한다. 깨지면 세션 문제를 보기도 전에 기동이 실패한다.

```sql
SELECT version, success, count(*) FROM flyway_schema_history GROUP BY version, success;
```

| version | success | count |
|---|---|---|
| 1, 1.1, 2, 3, 3.1, 3.2, 4, 4.1, 5, 5.1, 6, **6.1** | t | **각 1** |

각 버전이 정확히 1회만 적용됐다. Flyway가 스키마 히스토리 잠금으로 직렬화한다는 설계가
실제로 작동했다. `backend2.depends_on: backend1 service_healthy`로 직렬화할 필요가 없었다.

---

## 7. 수치

### 7.1 전략 비교

| | memory | sticky(ip_hash) | sticky(cookie) | **redis** | jwt |
|---|---|---|---|---|---|
| 세션 불일치 | **발생** | 해소 | **악화** | 해소 | 해소 |
| 200 비율 (부하 60초) | **46.3%** | 100% | **0%** | 99.8% | 99.8% |
| 인스턴스 분포 | 50:50 | **100:0** | 쏠림 | 27:13 | 25:15 |
| p50 | 0.88ms* | - | - | 3.01ms | 1.31ms |
| p95 | 1.40ms (200만 1.58ms) | - | - | **3.76ms** | **1.72ms** |
| p99 | 1.68ms | - | - | 4.78ms | 2.01ms |
| 처리량 | 48.9 RPS | - | - | 52.7 RPS | 52.1 RPS |
| 로그아웃 서버 강제 | 가능(그 인스턴스분만) | 동일 | 동일 | **가능** | **불가 — 쿠키 삭제뿐** |
| 회원 삭제 즉시 반영 | 가능 | 가능 | 가능 | 가능 | **엔드포인트에 따라 갈림**(8.1) |
| 인스턴스 중단 시 세션 | 소멸 | 소멸 | 소멸 | 유지 | 유지 |
| 만료 방식 | 슬라이딩 | 슬라이딩 | 슬라이딩 | 슬라이딩 | **절대(exp)** |
| 기동 의존성 | 없음 | 없음 | 없음 | **Redis 필수** | 없음 |
| 자격증명 크기 | 32B | - | - | 48B | **179B** |
| 힙 최대 | 62.9MB | - | - | 67.1MB | **52.7MB** |
| 코드 변경량 | - | **0줄** | 0줄 | 적음(공용 구현) | 많음(4파일 신규) |

(*) 1ms 미만은 버킷 경계 보간 추정값이다 — 2절의 경고 참조.

### 7.2 배치 중복

| | before | after |
|---|---|---|
| backend1 실행 (2분 반) | 2회 | **2회** |
| backend2 실행 (2분 반) | 2회 | **0회** |
| 만료 대상이 있을 때 | `좌석 수: 2` / `좌석 수: 0` | 한 쪽만 |
| backend1 중단 후 | - | backend2가 2회 인수, `locked_by` 교체 확인 |

페일오버까지 확인했다. `locked_by`가 `870e41bd9be3` → `2571e27c59cf`로 바뀌었다 —
**단일 장애점이 아니다.**

### 7.3 비용

| 항목 | Task 025 | Task 026 | 차이 |
|---|---|---|---|
| BE 기동 시간 | 4.92~5.19초 | **6.76~6.88초** | +1.6~1.9초 |
| BE 이미지 (`docker save`) | 141MB | **147MB** | +6MB |
| 컨테이너 수 (기본) | 4 | **5** (BE 2 + nginx) | +1 |
| 테스트 | 129개 | **132개** | +3 |

기동이 1.8초 느려진 건 Spring Session + Lettuce + ShedLock의 빈 등록이다. 컨테이너가
1개만 늘어난 것은 BE가 2대가 되면서 frontend·backend 포트를 닫고 nginx를 더한 결과다.

---

## 8. 왜 Redis 세션인가

JWT가 더 빠르다(p95 1.72ms vs 3.76ms). 힙도 14MB 적고 인프라도 0이다. 그래도 Redis를 골랐다.

1. **로그아웃이 서버에서 무력하다.** 로그아웃 후 같은 토큰으로 `/members/me`를 부르면
   **200이 나온다**(테스트로 고정했다). 사용자가 로그아웃 전에 쿠키 값을 복사해 뒀거나 토큰이
   유출됐다면 만료까지 막을 방법이 없다. 막으려면 폐기 목록을 서버에 둬야 하는데 그러면
   stateless가 아니게 되고, **Redis를 쓰면서 JWT의 단점만 남는다**
2. **만료 방식이 다르다.** 세션은 요청마다 밀리는 슬라이딩, JWT `exp`는 발급 기준 절대 시각이다.
   오픈런 중 좌석을 고르고 있는 사용자가 30분이 되어 끊기는 건 그 자체로 장애다
3. **+2.0ms는 이 워크로드에서 받아들일 수 있다.** 좌석맵 p95가 3.07ms([004_3](004_3-monitoring.md))인
   서비스에서 인증 1회에 2ms를 더 쓰는 것이고, Hikari `active` 최대가 1이라 병목은 다른 데 있다
4. **Redis는 어차피 들어온다.** Task 029(캐싱)·031(대기열)이 Redis를 전제한다.
   "인프라 1대 추가"가 이 Task만의 비용이 아니다 — [003](003-redis-distributed-lock.md)에서
   "Task 029·031에서 다시 쓴다"며 남겨둔 compose의 `redis`를 드디어 쓴다
5. **코드가 세션 API를 그대로 쓴다.** JWT는 필터·인코더·디코더·쿠키 관리를 새로 써야 했다
   (정리 커밋이 `43 insertions, 502 deletions`였다)

### 8.1 처음 쓴 근거가 틀렸다 — "회수가 아예 안 된다"는 과장이었다

이 절의 1번을 처음에는 **"강제 로그아웃(권한 회수)이 티켓 예매에서 선택이 아니다 — JWT는
회원을 DB에서 지워도 인증이 통과한다"** 로 썼다. 리뷰에서 "그 테스트는 404가 나왔는데
그럼 차단된 거 아니냐"는 지적을 받고 코드를 다시 봤다. **지적이 맞았다.**

삭제 반영 여부가 **엔드포인트가 DB를 보는지에 달려 있다.**

| 엔드포인트 | 회원 존재 확인 | 삭제된 회원의 토큰 |
|---|---|---|
| `GET /members/me` | `findById().orElseThrow(NOT_FOUND)` | **404 — 막힌다** |
| `GET /reservations` | 없음. `searchMine`이 `WHERE member_id = ?`뿐 | **200 + 빈 목록** |
| `GET /reservations/{id}`, `pay`, `cancel` | 없음. `findMineWithDetails(id, memberId)` 소유권 비교만 | 404 (소유 건이 없어서) |
| `POST /reservations` | `getReferenceById`(프록시, SELECT 안 함) | FK 위반 → 500 |

즉 "JWT는 차단이 전혀 안 된다"가 아니다. 내가 테스트로 고른 `/members/me`가 마침 DB를 보는
쪽이었고, 그래서 **그 테스트는 "인증이 통과한다"의 증거이지 "차단이 불가능하다"의 증거가 아니다.**

더 큰 문제는 **"권한 회수"라는 요구 자체가 지금 TIKKIT에 없다**는 것이다.
`hasRole` / `hasAuthority` / `@PreAuthorize` / `@Secured` 검색 결과가 **0건**이고,
`SecurityConfig`는 `authenticated()`만 쓴다. `MemberRole`은 존재하지만 어디서도 인가에
쓰이지 않는다 — **회수할 권한을 아무도 검사하지 않는다.** 채택 근거 1번을 코드 확인 없이
"예매 서비스니까 당연히 필요하겠지"로 세운 것이 실수였다.

**비교 자체에도 결함이 있었다.** 실무 JWT는 보통 짧은 access token(5~15분) + refresh token
조합이고, 회수는 "refresh token 폐기 → access token은 몇 분 내 자연사"로 처리한다. 공백이
분 단위로 줄어든다. 이번에 구현한 것은 **refresh 없는 30분 단일 토큰**이다
([PRD](../PRD.md)가 "JWT/리프레시 토큰"을 MVP 범위 밖으로 명시해서 단순한 형태로 만들었다).
**즉 회수 축에서는 실무보다 약한 형태와 비교했다.**

그런데 이 사실이 결론을 뒤집지 않고 오히려 더 깔끔한 논거를 준다 —
**JWT를 제대로 하려면 refresh token 저장소와 폐기 목록이 필요하고, 그 저장소는 결국 Redis다.**
그러면 Redis를 쓰면서 토큰 두 종류를 관리하는 셈이 된다. Redis 세션은 같은 보증을
더 적은 부품으로 얻는 경로다.

**측정하기 쉬운 축(지연)은 재고, 판단의 중심축(회수 요구)은 가정으로 둔 것**이 이번의 교훈이다.
`hasRole` 0건을 처음에 확인했으면 비교표의 그 칸을 다르게 썼을 것이다.

### 8.2 `repository-type: indexed`

8절 1번과 같은 이유로 골랐다. 기본값 `default`에는 세션 조회 색인이 없어서
`findByPrincipalName()`을 쓸 수 없다 — 특정 회원의 세션을 서버가 찾아 지우는 동작 자체가
불가능해진다. 대가는 **기동이 Redis에 묶인다는 것**이다(indexed는 기동 시점에 `CONFIG SET`으로
keyspace notification을 켠다). Redisson 스타터를 피한 이유와 같은 모양의 비용을 의도적으로 지불했다.

다만 8.1을 반영하면 이 선택의 근거도 약해진다. 지금 이 색인을 쓰는 운영 코드는 0건이고
통합 테스트 하나가 쓴다. **"쓸 수 있다"와 "쓰고 있다"는 다르다** — 강제 로그아웃 API가 실제로
필요해질 때까지는 `default`로 두고 기동 의존성을 피하는 선택도 정당하다. Task 034에서
다시 본다.

정리 커밋에서 건드린 운영 코드는 `AuthStrategyConfig`의 case 두 줄과 `SecurityConfig`의
if 블록 하나뿐이었다. 나머지는 파일 삭제로 끝났다 — **전략 선택을 switch 한 곳에 모은 결정이
Task 020의 교훈("실험은 지우기 쉽게 둔다")을 이번에도 지켜줬다.**

---

## 9. 한계

- **JWT 비교가 refresh 토큰 없는 형태였다**(8.1). 실무 JWT는 짧은 access + refresh 조합이고
  회수 공백이 분 단위다. **회수 축의 비교 결과는 "refresh 없는 30분 단일 토큰"에 대해서만 유효하다.**
  지연·힙·쿠키 크기 비교는 영향이 없다(access token 하나를 검증하는 비용은 같다)
- **삭제 반영이 엔드포인트마다 다르다는 것은 코드 확인까지만 했다.** `GET /reservations`가
  삭제된 회원의 토큰에 200+빈 목록을 준다는 것은 `searchMine`이 `WHERE member_id = ?`뿐이라는
  읽기에서 나온 결론이고, **재현하지 않았다.** JWT를 이미 제거한 뒤에 발견해서 측정을
  미뤘다 — 다시 재려면 JWT 커밋을 복원해야 한다
- **`role` 변경이 토큰에 반영되지 않는 문제는 측정하지 않았다.** 애초에 TIKKIT에 role 기반
  인가가 0건이라(8.1) 재볼 대상이 없었다. role 인가를 도입하는 시점에 이 축을 다시 봐야 한다
- **`memory` 토글이 남아 있다.** 실험 잔재가 아니라 개발·테스트용이다("Redis 없이도 기동·테스트가
  돌아야 한다"는 003의 결정). 다만 **운영에서 실수로 `memory`로 띄우면 불일치가 그대로 재현된다.**
  compose 기본값을 `redis`로 바꿔 사고 경로를 줄였지만, 기동 시 경고를 띄우는 식의 방어는 없다
- **쿠키 `secure`는 여전히 false다.** 평문 HTTP라 켜면 e2e가 전부 깨진다(Task 034)
- **단일 Redis다.** Redis가 죽으면 전 사용자가 로그아웃된다. 세션을 Redis에 모은 대가로
  장애 반경이 "한 인스턴스"에서 "전체"로 바뀌었다 — 이건 교환이지 개선이 아니다.
  복제·Sentinel은 다루지 않았다
- **측정 표본이 적고 로컬이다.** 각 전략을 60초 1회씩만 쟀다. p95 차이(1.72 vs 3.76ms)는
  방향이 분명하지만 절대값으로 읽으면 안 된다. 제대로 된 베이스라인은 k6를 쓰는 Task 027이 만든다
- **`keepalive`를 켜지 않은 상태의 수치다.** nginx→BE가 매 요청 새 TCP라 핸드셰이크 비용이
  전 전략에 똑같이 섞여 있다(4.6). 상대 비교는 유효하지만 절대값은 과대 추정이다
- **1ms 미만 구간은 측정 불가다.** 버킷 하한이 1ms라 `memory`의 p50은 값이 아니라 보간이다.
  내리려면 카디널리티를 또 늘려야 한다(004_3 5.2절: URI 1종당 54 시리즈)
- **쿠키명을 통합 테스트로 고정하지 못했다**(4.3). FE 로그인이 조용히 깨지는 회귀를
  e2e에만 의존하고 있다
- **ShedLock 페일오버를 `lockAtMostFor` 경로로는 못 재봤다.** 정상 종료 후 `lock_until`이
  55초 뒤라서 그 경로로만 확인했다. "락을 들고 죽었을 때 5분 뒤 풀리는가"는 측정하지 않았다
- **sticky session 수치가 비대칭이다.** ip_hash와 쿠키 해시는 불일치 여부·분포만 쟀고
  p95는 재지 않았다. 둘 다 구조적으로 기각이라 지연을 비교할 의미가 없었다
- **`AUTH_SESSION_STORE`를 바꿀 때 nginx 재시작을 사람이 기억해야 한다.** 빼먹으면 502가
  나고 전략 문제로 오진한다. 스크립트로 묶지 않았다

---

## 10. 다음 단계

Phase 7이 끝난다. `v0.4.0-ops` 태그를 만든다.

- **[미배정] 8.1의 공백 2개**: (a) `GET /reservations`가 삭제된 회원의 토큰에 200+빈 목록을
  준다는 것을 **재현**해야 한다(지금은 코드 읽기까지만 했다). (b) role 기반 인가를 도입할 때
  "토큰이 발급 시점 권한을 들고 다닌다"를 그때 측정한다. 둘 다 JWT 복원이 필요해서 미뤘다.
  **`findById` 유무로 차단 여부가 갈리는 것 자체가 설계 결함**이기도 하다 — 인증 주체가 유효한지는
  엔드포인트가 아니라 한 곳에서 판단해야 한다(세션 방식에서는 세션 삭제가 그 한 곳이다)
- **[Task 034] `repository-type` 재검토**: 8.2 참조. `indexed`를 쓰는 운영 코드가 0건이라
  `default`로 내리면 기동 의존성이 사라진다. 강제 로그아웃 API를 실제로 만들 때 결정한다
- **[Task 027] k6 베이스라인**: 이번 p95는 `curl`+`xargs` 기준이다. 램프업·think time·응답
  검증이 있는 측정으로 다시 잡아야 하고, 그때 `keepalive`를 켠 값도 같이 재서 4.6의
  핸드셰이크 비용을 분리한다. 부하 지속 시간은 `scrape_interval`의 몇 배로 둬야 한다
- **[Task 028] 쿼리·인덱스 최적화**: `shedlock` 테이블이 생겼지만 인덱스는 PK뿐이다.
  1분마다 1행을 읽고 쓰는 수준이라 지금은 볼 것이 없다
- **[Task 029] 캐싱**: Lettuce가 이미 클래스패스에 있다. Spring Cache를 붙일 때
  `DataRedisAutoConfiguration`을 제외해둔 것(4.2)이 걸림돌이 되는지 확인해야 한다 —
  캐시 자동설정도 `RedisConnectionFactory`를 보는데, 지금은 세션 전략이 켜질 때만 그 빈이 있다
- **[Task 031] 대기열**: Redisson(raw)과 Lettuce(starter)가 한 클래스패스에 공존한다.
  ZSET 대기열을 어느 쪽으로 쓸지 정하고 하나로 줄이는 게 좋다
- **[Task 034] 배포**: nginx가 그대로 리버스 프록시가 된다. TLS·도메인이 들어오면
  쿠키 `secure`를 켜고, `$http_host`로 바꿔둔 것(4.5)이 그때 값을 한다.
  Redis 단일 장애점도 그때 다시 본다

**범위 밖에서 확인한 것**: `tikkit-front/lib/session.ts`의 `SESSION_MAX_AGE_SECONDS`는
로그인 시점에 1회 고정이라, BE 세션이 요청마다 30분씩 밀리는 것과 어긋난다.
"BE는 살아있는데 Next 쿠키가 먼저 죽는" 상황이 가능하다. 이 Task와 무관해서 손대지 않았다.

---

## 재현 방법

```bash
# 0. 준비
cp .env.example .env
docker compose -f docker-compose.prod.yml down -v    # 시드가 상대 날짜라 매번 비우고 시작

# 1. 단위·통합 테스트 (당시 132개)
#    Task 042_1이 쿼리 카운트를 queryCountTest로 떼어냈다 — 전부 돌리려면 check다
cd tikkit-back && ./gradlew check && cd ..
# -> BUILD SUCCESSFUL, 실패·에러·건너뜀 0
#    RedisSessionAuthIntegrationTest가 Testcontainers Redis를 띄워 강제 로그아웃까지 검증한다

# 2. 재현 — 일부러 memory로 띄운다
sed -i 's/^AUTH_SESSION_STORE=redis$/AUTH_SESSION_STORE=memory/' .env
docker compose -f docker-compose.prod.yml --profile monitoring up -d --build
# -> 8개 서비스 전부 healthy

SID=$(curl -s -i -X POST http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@tikkit.com","password":"Password1!"}' \
  | grep -i '^set-cookie' | sed -n 's/.*JSESSIONID=\([^;]*\).*/\1/p')

for i in $(seq 1 40); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/v1/members/me \
    -H "Cookie: JSESSIONID=$SID"
done | sort | uniq -c
# -> 200과 401이 섞여 나온다 (3절)

# 3. 증거 — 같은 sess 값이 upstream에 따라 갈린다
docker compose -f docker-compose.prod.yml logs nginx --no-log-prefix | grep "members/me"
# -> 200 upstream=172.21.0.6 sess=4B66...  /  401 upstream=172.21.0.5 sess=4B66...

# 4. /actuator 차단 확인
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/actuator/health   # -> 404
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"up"'      # -> 2개 (BE를 직접 긁는다)

# 5. 배치 중복 (ShedLock 전 상태를 보려면 @SchedulerLock을 떼야 한다)
docker compose -f docker-compose.prod.yml logs backend1 backend2 | grep "선점 만료 배치"
# -> ShedLock 적용 상태에서는 한 인스턴스에만 찍힌다
docker exec tikkit-postgres-prod psql -U tikkit_user -d tikkit_db \
  -c "SELECT name, locked_by, lock_until FROM shedlock;"

# 6. 해결 — redis로 되돌린다
sed -i 's/^AUTH_SESSION_STORE=memory$/AUTH_SESSION_STORE=redis/' .env
docker compose -f docker-compose.prod.yml up -d --force-recreate backend1 backend2
docker compose -f docker-compose.prod.yml restart nginx   # upstream DNS 1회 해석 때문에 필수
# 2번의 40회 루프를 다시 -> 40건 전부 200

docker exec tikkit-redis-prod redis-cli --scan --pattern 'tikkit:session*'
# -> sessions:<id>, expirations:<ts>, index:...PRINCIPAL_NAME_INDEX_NAME:user@tikkit.com

# 7. 대시보드
# http://localhost:3001  (익명 Viewer)
# "세션 불일치 — 인증 필요 요청의 인스턴스 × 상태코드" 패널에서 401 시리즈가 사라지는 것을 본다

# 8. e2e (nginx가 진입점이라 포트가 80이다)
cd tikkit-front && E2E_BASE_URL=http://localhost npm run test:e2e
# -> 3 passed
```
