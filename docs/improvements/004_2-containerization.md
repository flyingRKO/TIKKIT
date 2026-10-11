# 004_2. 컨테이너화 및 로컬 운영 스택 — 운영 이미지로 띄웠을 때만 드러나는 것들 (Task 024)

| | |
|---|---|
| 관련 Task | Task 024 (Phase 7: 운영 기반) |
| 대상 코드 | `tikkit-back/Dockerfile`, `tikkit-front/Dockerfile`, `.dockerignore` 2개, `application-prod.yml`, `WebConfig.java`, `GlobalExceptionHandler.java`, `next.config.ts`, `playwright.config.ts`, `docker-compose.prod.yml`, `.env.example`, `ci.yml` |
| before 기준선 | [004_1. Spring Boot 4 업그레이드](./004_1-spring-boot-4-migration.md) — 기동 4.42초, 테스트 123개, e2e 3개 |
| 결론 | **dev 기동과 다른 점 4건이 전부 "경고로만 있던 것"에서 나왔다.** springdoc을 끈 자리가 500을 뱉고, Next의 워크스페이스 루트 추정이 standalone 레이아웃을 바꾸고, devtools가 기동 실패를 가리고 있었다. 셋 다 `next start`/`bootRun`으로만 돌릴 때는 증상이 없었다 |
| 작성 시점 | 2026-10-11 |

## 1. 왜 만들었나

ROADMAP Phase 7의 전제가 **로컬 Docker로 재현·측정한다**는 것인데(ROADMAP:253) 저장소에
Dockerfile이 하나도 없었다. `tikkit-back/docker-compose.yml`은 PostgreSQL + Redis만 띄우는
개발 인프라이고 앱 서비스가 없다.

이 Task가 만드는 `docker-compose.prod.yml`이 Phase 7~9 전체의 측정 하네스가 된다.
Task 025는 여기에 Prometheus·Grafana를 얹고, Task 026은 BE 2개 + nginx를 얹어 세션 불일치를
재현하고(compose 없이는 재현 자체가 불가능), Task 034는 이 파일을 VM에 그대로 적용한다.

004_1이 Task 024로 넘긴 항목도 둘 있었다 — Dockerfile base image를 Boot 4 기준으로 한 번만
쓰는 것, 그리고 springdoc 3.x의 `/swagger-ui.html` 기본 노출을 운영에서 끄는 것(ROADMAP:268).

**완료 기준은 처음부터 "prod 이미지로 띄운 스택에서 Playwright e2e 3개 통과"로 잡혀 있었다**
(ROADMAP:275). 이미지가 빌드되는 것과 그 이미지로 예매가 되는 것은 다른 얘기다.

## 2. 측정 환경

| 항목 | 값 |
|---|---|
| Docker | 28.5.1 / compose 2.40.3-desktop.1 (Windows 11, WSL2 백엔드) |
| BE 이미지 | 빌드 `eclipse-temurin:21-jdk` → 런타임 `eclipse-temurin:21-jre-alpine` |
| FE 이미지 | `node:22-bookworm-slim` (3스테이지) |
| 앱 버전 | Spring Boot 4.1.1 / Gradle 8.14.4 / Next 16.2.3 / React 19.2.4 |
| DB | PostgreSQL 15.18 (compose, prod 전용 볼륨 `pgdata-prod`) |
| 시드 | Flyway `db/seed` 3개 (`V1_1`, `V4_1`, `V5_1`) — 환경변수로 토글 |

절대 수치는 로컬 Docker 기준이라 **상대 비교와 성립·불성립 판정**으로만 읽어야 한다.

## 3. 사전 조사 — Boot 4의 jarmode는 더 이상 layertools가 아니다

Boot 3.3에서 `-Djarmode=layertools`가 `-Djarmode=tools extract`로 교체됐다. 공식 4.1.1
레퍼런스(`packaging/container-images/dockerfiles.html`)와 v4.1.1 태그의 `ExtractCommand.java`
소스로 확인했다.

명령만 바뀐 게 아니라 **`--launcher` 유무로 실행 방식이 갈린다.**

```java
// ExtractCommand.java (v4.1.1)
if (options.containsKey(LAUNCHER_OPTION)) {
    extractArchive(fileResolver);                 // BOOT-INF 그대로 풀기 → JarLauncher 필요
} else {
    JarStructure jarStructure = getJarStructure();
    extractLibraries(fileResolver, jarStructure, options);   // BOOT-INF/lib/*.jar → <layer>/lib/*.jar
    createApplication(jarStructure, fileResolver, options);  // 새 thin jar 생성
}
```

`--launcher`를 주지 않으면 `IndexedJarStructure.createLauncherManifest()`가 `Main-Class`를
`Start-Class`로 치환하고 `Class-Path`에 `lib/*.jar`를 넣은 thin jar를 만든다. 실측도 같았다.

```
Main-Class: com.tikkit.api.TikkitApplication
Class-Path: lib/flyway-database-postgresql-12.4.0.jar ...
```

즉 **런타임은 loader를 거치지 않고 JVM 표준 클래스패스로 뜬다.** 인터넷에 흔한
`ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]`는 이 구성에서
틀린 명령이다. Boot 3.2+에서 그 클래스의 패키지가 `...loader.launch`로 바뀐 것도 맞지만,
우리 Dockerfile에는 쓸 일이 없다.

`layered {}` 블록이 없으므로 기본 4레이어가 그대로 나온다. 그중 둘은 **빈 디렉터리**다
(`--launcher` 미사용 + 스냅샷 의존성 0건). `COPY`는 실패하지 않으며 공식 예제도 4줄을 그대로 둔다.

## 4. 핵심 발견 — 경고로만 있던 것들이 빌드를 깨뜨린다

이 Task에서 드러난 문제 4건은 공통점이 있다. **전부 이전에도 존재했지만 `bootRun`/`next start`로
돌릴 때는 증상이 없었다.** 컨테이너화가 "경고"를 "실패"로 승격시켰다.

### 4.1 springdoc을 끄면 그 자리가 500을 뱉는다

`application-prod.yml`에 `springdoc.api-docs.enabled=false`를 넣고 확인했더니 404가 아니라
**500 + ERROR 스택트레이스(요청당 약 80줄)** 가 나왔다.

```
GET /v3/api-docs -> 500
ERROR c.t.a.c.e.GlobalExceptionHandler : 예상하지 못한 예외가 발생했습니다.
org.springframework.web.servlet.resource.NoResourceFoundException:
  No static resource v3/api-docs for request '/v3/api-docs'.
```

`GlobalExceptionHandler`의 `@ExceptionHandler(Exception.class)`가 `NoResourceFoundException`을
받아버린 것이다. 이 예외는 "404를 내라"는 프레임워크 신호인데 catch-all이 먹어버렸다.

**왜 그동안 안 보였나가 더 중요하다.** 모르는 경로는 `SecurityConfig`의
`anyRequest().authenticated()`가 먼저 401로 끊는다.

```
GET /nonexistent-path  -> 401   (시큐리티가 먼저 끊음)
GET /v3/api-docs       -> 500   (permitAll인데 핸들러가 없음)
```

이 구멍에 빠지는 것은 **permitAll이면서 핸들러가 없는 경로**뿐이고, springdoc을 끄기 전까지는
그런 경로가 존재하지 않았다. 보안 설정이 예외 처리의 결함을 덮고 있었던 셈이다.

운영에서 스캐너가 `/swagger-ui.html`을 긁으면 로그가 ERROR로 덮이고 Task 025의 5xx 지표도
오염된다. `NoResourceFoundException` 핸들러 5줄을 추가해 404로 바꿨다(기존 `ErrorCode.NOT_FOUND`
재사용). `SecurityConfig`는 건드리지 않았다 — springdoc이 꺼지면 그 permitAll은 404로 떨어져
무해해진다.

### 4.2 루트의 빈 package-lock.json이 standalone 레이아웃을 바꾼다

`output: "standalone"`을 켜고 빌드하니 `.next/standalone/server.js`가 아니라
**`.next/standalone/tikkit-front/server.js`** 가 나왔다. Dockerfile을 그대로 썼으면
`CMD ["node", "server.js"]`가 파일을 못 찾고 죽는다.

```
⚠ Warning: Next.js inferred your workspace root, but it may not be correct.
 We detected multiple lockfiles and selected the directory of
 C:\Users\wogns\workspace\TIKKIT\package-lock.json as the root directory.
```

루트의 `package-lock.json`은 **의존성 0개의 빈 파일**이고 초기 커밋(`afbda98`) 이후 한 번도
바뀌지 않은 잔재다. 루트에 `package.json`도 없으니 워크스페이스도 아니다. 그런데 Next는
lockfile 위치로 워크스페이스 루트를 추정하기 때문에, 이 파일 하나가 산출물 구조를 결정한다.

이 경고는 **전부터 떠 있었다.** `next start`로만 돌릴 때는 `.next/standalone`을 쓰지 않으니
아무 영향이 없었다. `outputFileTracingRoot: __dirname`으로 추적 루트를 고정했다 —
lockfile을 지우는 것보다 이쪽을 고른 이유는, 레이아웃이 다른 디렉터리의 파일 유무에
흔들리지 않게 하는 것이 목적이기 때문이다.

### 4.3 devtools가 기동 실패를 가리고 있었다

prod 프로필에서 DB 접속 정보에 기본값을 주지 않았으니 환경변수 누락 시 기동이 실패해야 한다.
`bootRun`으로 확인했더니 컨텍스트는 분명히 실패하는데 **`BUILD SUCCESSFUL`(exit 0)** 로 끝났다.

```
$ SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun; echo "exit=$?"
... BeanCreationException: ... 'url' must start with "jdbc"
BUILD SUCCESSFUL in 7s
exit=0
```

스택트레이스에 `DevToolsDataSourceAutoConfiguration`이 섞여 있는 게 단서였다. devtools는
`developmentOnly`라 bootJar에 들어가지 않으므로 컨테이너에서 다시 쟀다.

```
$ docker run --name t -e SPRING_PROFILES_ACTIVE=prod tikkit-backend:local
$ docker inspect t --format '{{.State.ExitCode}}'
1
```

**컨테이너는 정직하게 1로 죽는다.** compose가 "정상 종료"로 오해할 일이 없다. 로컬 기동과
컨테이너 기동이 다르게 보이는 지점이고, 로컬 결과만 믿었으면 "기본값을 안 주는 설계"가
무력하다고 잘못 판단했을 것이다.

단 에러 메시지는 `Could not resolve placeholder`가 아니라 `'url' must start with "jdbc"`다 —
플레이스홀더가 리터럴로 남아 Hikari가 거부하는 경로라 원인을 한 단계 가린다.

### 4.4 .next/static 복사를 빼먹으면 화면은 뜨고 스타일만 사라진다

ROADMAP:275가 "`output: 'standalone'`의 정적 파일 복사 누락"을 미리 경고해뒀는데, 실제 증상을
명령 하나로 재현했다.

```
$ PORT=3002 node .next/standalone/server.js      # .next/static 복사 없이
GET /performances                           -> 200
GET /_next/static/chunks/11q~mmj865chz.css  -> 404
GET /_next/static/media/Pretendard...woff2  -> 404
```

이 프로젝트에서 특히 아픈 이유는 **파비콘과 폰트가 둘 다 `.next/static/media`에만 있다는
것**이다. `public/` 디렉터리가 아예 없고(그래서 공식 Next 템플릿의
`COPY /app/public ./public`은 빌드가 깨진다), 파비콘은 `app/favicon.ico`로 App Router 규약을
타고, Pretendard는 `app/globals.css`의 `@import`로 node_modules를 거쳐 들어온다.

**그리고 e2e 3개는 이 상태에서도 통과한다.** 테스트가 `getByRole`·`getByText`로 DOM만 보기
때문이다. 자산 404는 응답 코드를 직접 재는 것 외에는 잡히지 않는다.

## 5. 실제로 고친 것

### 5.1 BE — layered jar 2스테이지

| 결정 | 이유 |
|---|---|
| 빌드 `eclipse-temurin:21-jdk` | `build.gradle`의 toolchain(21)과 일치해 Gradle이 툴체인을 따로 안 받는다. `gradle:8.14.4-jdk21`을 안 쓴 이유는 Gradle 버전 선언이 래퍼와 Dockerfile 두 곳으로 늘어나기 때문 — CI도 래퍼를 쓰므로 진실은 한 곳이어야 한다 |
| 런타임 `eclipse-temurin:21-jre-alpine` | JDK 도구가 없어 공격면이 작고, **busybox wget이 들어 있어 compose healthcheck에 `apk add`가 필요 없다**(curl은 없다) |
| alpine musl 위험 없음 | jar/class만 복사하고 네이티브 라이브러리를 안 쓴다. `ZoneId.of("Asia/Seoul")`은 OS `tzdata`가 아니라 JDK 번들 `tzdb.dat`을 읽는다 |
| `--application-filename application.jar` | 기본값이 uber jar 파일명(`TIKKIT-0.0.1-SNAPSHOT.jar`)이라 ENTRYPOINT가 버전 문자열에 묶인다 |
| 의존성 파일만 먼저 COPY | `gradle/`·`gradlew`·`settings.gradle`·`build.gradle`을 복사하고 `./gradlew dependencies`로 캐시를 굳힌 뒤 `src`를 복사한다 |
| `chmod +x gradlew` | git index는 `100755`지만 Windows 작업 트리에 권한 개념이 없다 |
| exec 형식 ENTRYPOINT | PID 1이 JVM이 되어 SIGTERM이 그대로 전달된다. `sh -c`로 감싸 `$JAVA_OPTS`를 펼치면 신호 전달이 깨진다 — JVM 옵션은 `JAVA_TOOL_OPTIONS`로 주면 JVM이 알아서 읽는다 |

### 5.2 FE — standalone 3스테이지

| 결정 | 이유 |
|---|---|
| `node:22-bookworm-slim` | CI frontend 잡과 같은 Node 22. alpine이 아니라 slim을 고른 이유는 tailwindcss v4(`@tailwindcss/oxide`)와 lightningcss가 네이티브 바이너리를 쓰기 때문. musl 변수를 없애는 대가로 약 60MB를 더 쓴다 |
| 빌드 스테이지에 devDependencies 포함 | `tailwindcss`/`@tailwindcss/postcss`/`typescript`가 devDependencies인데 `next build`가 쓴다. `--omit=dev`를 주면 빌드가 깨진다 |
| `API_BASE_URL`을 빌드 ARG로 안 받음 | `NEXT_PUBLIC_*` 사용이 0건이라 번들에 인라인될 값이 없고, API를 쓰는 페이지는 전부 `cookies()`를 거쳐 동적 렌더링이라 빌드 중 BE를 때리지 않는다. **같은 이미지를 환경만 바꿔 재사용할 수 있다** |
| `COPY /app/public` 제거 | `public/` 디렉터리가 없다 (4.4절) |
| `ENV HOSTNAME="0.0.0.0"` | standalone `server.js`가 이 값을 보고 바인딩한다 |

### 5.3 설정 — prod 프로필 신설

datasource가 `application-dev.yml`에만 있었으므로 prod 프로필에서 통째로 다시 썼다.
**기본값을 일부러 주지 않았다** — dev는 `${DB_URL:jdbc:postgresql://localhost:5432/tikkit_db}`처럼
fallback이 있는데, 운영에서 같은 짓을 하면 환경변수 누락이 "localhost의 엉뚱한 DB를 Flyway로
마이그레이션하는" 사고가 된다.

springdoc은 환경변수 토글 없이 `false`로 박았다. 운영에서 켤 시나리오가 없고, 뚫어두면
그게 사고 경로가 된다.

CORS는 `@Value("${tikkit.cors.allowed-origins}") String[]`로 외부화했다.
`@ConfigurationProperties`를 안 고른 이유는 값이 하나뿐인데 클래스 + 빈 등록이 늘어나고,
**스프링이 `String[]`이면 콤마를 자동으로 끊어주므로 다중 origin이 공짜로 따라온다**는 것이다.
선언은 `application.yml`에 한 번만 뒀다(기본값이 `localhost:3000`이라 dev/test 동작 유지,
`application-test.yml`도 수정 불필요).

### 5.4 compose — actuator 없는 헬스체크

actuator가 Task 025 범위라 `/actuator/health`가 없다. 대안으로 **CI e2e 잡이 기동 확인에
쓰는 엔드포인트를 그대로 썼다** — `GET /api/v1/performances?size=1`은 비로그인 permitAll이고,
200이 나오려면 Hikari 커넥션과 Flyway 마이그레이션까지 끝나야 한다.

```yaml
healthcheck:
  test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://localhost:8080/api/v1/performances?size=1"]
  start_period: 40s   # Flyway 8개 + 시드 3개(좌석 백필 포함)가 도는 동안 실패를 세지 않는다
```

`CMD-SHELL`이 아니라 **exec 배열 형식**이어야 한다. 셸을 거치면 URL의 `?size=1`이 글로빙
대상이 된다. 404에 대해 exit 1로 떨어지는 것도 확인했다 — 헬스체크가 정직하다.

FE는 slim 이미지에 curl도 wget도 없어서 Node 22의 전역 `fetch`로 확인한다.

**prod 전용 볼륨 `pgdata-prod`로 분리했다.** `db/seed`의 버전(`V1_1`, `V4_1`, `V5_1`)이
`db/migration` 사이에 끼어 있어서, 시드를 적용한 dev 볼륨에 시드 없는 prod를 붙이면 Flyway가
기동을 깬다. 컨테이너명에 `-prod`를 붙이고 DB/Redis 호스트 포트를 열지 않아 **dev 스택과
동시에 띄울 수 있다**.

### 5.5 playwright — 조건부 webServer

`webServer`가 무조건 `npm run start`를 띄우려 해서 컨테이너 FE를 대상으로 e2e를 돌릴 수
없었다. `E2E_BASE_URL`이 주어지면 `webServer: undefined`로 끈다.

`reuseExistingServer`를 손보는 쪽은 안 된다 — CI에서 그 값이 `false`가 되어 3000번 포트를 두고
충돌하고, 로컬에서도 Playwright가 종료 시점에 남의 서버(컨테이너)를 죽이려 든다.

기존 두 경로는 그대로다. 로컬 `npm run test:e2e`와 CI e2e 잡 모두 `E2E_BASE_URL`을 설정하지
않으므로 `webServer`가 살아 있다 — **`ci.yml`의 e2e 잡은 수정하지 않았다.**

### 5.6 기각한 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| `gradle:8.14.4-jdk21` 빌드 이미지 | Gradle 버전 선언이 두 곳으로 늘어난다 |
| `--launcher`로 추출 | JarLauncher를 거치는 구 레이아웃이 된다. thin jar 쪽이 명령도 단순하다 |
| `@ConfigurationProperties`로 CORS | 값 1개에 클래스와 빈 등록이 늘어난다 |
| `reuseExistingServer` 재활용 | CI에서 포트 충돌 (5.5절) |
| `node:22-alpine` | tailwindcss v4·lightningcss 네이티브 바이너리의 musl 변수 |
| 루트 `package-lock.json` 삭제 | 레이아웃이 밖의 파일 유무에 종속되는 구조 자체를 끊는 쪽을 골랐다 (4.2절) |
| `start` 스크립트를 standalone 실행으로 교체 | `npm start` 동작이 바뀌고 복사 단계를 잊으면 스타일 없는 화면이 나온다 (8절) |

## 6. 관문 — prod 스택에서 e2e 3개

```
$ docker compose -f docker-compose.prod.yml ps
tikkit-backend-prod    Up (healthy)
tikkit-frontend-prod   Up (healthy)
tikkit-postgres-prod   Up (healthy)
tikkit-redis-prod      Up (healthy)

$ cd tikkit-front && E2E_BASE_URL=http://localhost:3000 npm run test:e2e
  ok 1 [e2e] 로그인 없이 마이페이지에 들어가면 로그인 화면으로 보내고 원래 경로를 기억한다 (206ms)
  ok 2 [e2e] 로그인 없이 결제 화면에 들어가도 로그인 화면으로 보낸다 (138ms)
  ok 3 [e2e] 가입 → 로그인 → 좌석 선택 → 선점 → 결제 → 취소 흐름 (4.3s)
  3 passed (5.5s)
```

기존 로컬 워크플로우(`E2E_BASE_URL` 없음, dev 프로필 BE)도 **3 passed (6.0s)** 로 회귀 없다.

정적 자산 체인도 응답 코드로 직접 쟀다(4.4절의 실패 모드를 e2e가 못 잡으므로).

| 요청 | 결과 |
|---|---|
| `/_next/static/chunks/0q0ca.ks.6r.~.css` | 200, 46,647 B |
| `/_next/static/chunks/03~yq9q893hmn.js` | 200, 112,594 B |
| `/_next/static/media/PretendardVariable....woff2` | 200, 2,057,688 B |
| `/_next/static/media/favicon....ico` | 200, 25,931 B |

CSS 안의 `@font-face`가 `url(../media/PretendardVariable....woff2)`로 상대 경로를 쓰고 그게
200으로 서브된다 — 체인이 끊기지 않았다.

시드 토글은 **양방향으로** 쟀다. 켜는 것만 확인하면 "운영 기본값이 안전한가"를 모른다.

| `FLYWAY_LOCATIONS` | 적용된 마이그레이션 | 데이터 |
|---|---|---|
| 비움 (운영 기본값) | `1 2 3 3.1 3.2 4 5 6` — 8개, 시드 없음 | `performances=0`, `members=0` |
| `...,classpath:db/seed` | `1 1.1 2 3 3.1 3.2 4 4.1 5 5.1 6` — 11개 | ON_SALE 3 / UPCOMING 3 / CLOSED 2 |

여기서 알게 된 것: **BE 헬스체크가 빈 DB에서도 healthy다.** 목록 API가 빈 배열로 200을 주기
때문이다. 상세 조회나 통계 엔드포인트를 골랐으면 시드 없는 운영 DB에서 영원히 unhealthy였다.

## 7. 수치

| 항목 | 값 |
|---|---|
| BE 이미지 | **137 MB** (`docker save` 기준) / 컨테이너 내부 파일시스템 276 MB |
| FE 이미지 | **89 MB** (`docker save` 기준) / 컨테이너 내부 파일시스템 272 MB |
| BE 빌드 | 콜드 **70초** → 소스 변경 시 **21초** (`dependencies` 레이어 CACHED) |
| FE 빌드 | **44초** |
| `compose up` → 4개 전부 healthy | **24초** |
| BE 컨테이너 기동 | **4.414초** (004_1의 로컬 4.42초와 동일 수준) |
| e2e | 컨테이너 스택 **5.5초** / 로컬 6.0초 |
| 테스트 | **123개 통과** (실패 0 / 에러 0 / 건너뜀 0) |

### 레이어 분리가 버는 것

| 레이어 | 크기 |
|---|---|
| `dependencies` | **77.6 MB** (거의 안 변함) |
| `application` | **184 kB** (매 빌드 변함) |
| `spring-boot-loader` | 4.1 kB (빈 디렉터리) |
| `snapshot-dependencies` | 4.1 kB (빈 디렉터리) |

코드만 고치면 78MB가 아니라 **184kB만** 새 레이어가 된다.

### .dockerignore가 버는 것

| 모듈 | 전체 | 제외 후 | 제외하는 것 |
|---|---|---|---|
| `tikkit-back` | 82 MB | **642 KB** | `build/` 77M, `.gradle/` 3.7M |
| `tikkit-front` | 1.7 GB | **1.1 MB** | `node_modules/` 693M, `.next/` 977M |

효과는 이미지 크기가 아니라 빌드 시작 비용이다. 제외한 1.7GB는 어차피 최종 이미지에 안 들어간다.
줄어드는 건 컨텍스트 전송 시간과, **그 파일들이 바뀔 때마다 캐시가 깨지는 빈도**다 —
`node_modules`를 안 빼면 `npm install` 한 번으로 모든 레이어 캐시가 무효화된다.

> 참고: 빌드 로그의 `transferring context: 5.31kB`는 **증분**이라 전체 컨텍스트 크기가 아니다.
> 로컬 컨텍스트 캐시가 따뜻하면 바뀐 파일만 전송하므로 `--no-cache`를 줘도 전체가 안 나온다.
> 위 표는 `du --exclude`로 직접 쟀다.

## 8. 새로 생긴 경고

| 경고 | 평가 |
|---|---|
| `"next start" does not work with "output: standalone" configuration. Use "node .next/standalone/server.js" instead.` | **신규.** 실측하니 `next start`는 CSS 200 / 폰트 200으로 정상 서브한다. `output: standalone`이 기존 `.next/` 산출물을 **대체하지 않고** `.next/standalone/`을 추가로 만들기 때문이다 — `next start`는 평소 쓰던 쪽을 읽고, standalone 서버는 자기 디렉터리 안의 `.next/static`만 찾는다. 로컬 e2e와 CI e2e 잡이 둘 다 통과하므로 지금은 그대로 두고, 프레임워크가 실제로 끊으면 옮긴다 |
| `Next.js inferred your workspace root...` | **해소됨** — `outputFileTracingRoot`로 고정 (4.2절) |
| `SpringDoc /swagger-ui.html endpoint is enabled by default` | **해소됨** — 004_1 8절이 이 Task로 넘긴 항목. prod에서 끄면서 사라졌다 |
| `spring.jpa.open-in-view is enabled by default` | prod 프로필에서 `open-in-view: false`로 꺼서 prod 기동에는 안 뜬다. dev는 그대로 |

## 9. 한계

- **푸시도 배포도 하지 않았다.** 레지스트리 인증·도메인·TLS는 Task 034다. CI는 빌드만 한다
- **CI 잡의 캐시는 검증되지 않았다.** 로컬에서 확인한 것은 Dockerfile이 CI와 같은 컨텍스트
  지정으로 빌드된다는 것까지다. `cache-from/to: type=gha`가 실제로 적중하는지, 매트릭스 두 잡이
  scope 분리로 안 덮어쓰는지, 20분 타임아웃이 충분한지는 **PR을 두 번 푸시해봐야 안다**
- **시드를 켠 이미지로 검증했다.** 시드 없는 진짜 운영 경로는 기동과 헬스체크까지만 확인했고
  e2e로는 확인되지 않았다(시드가 없으면 예매할 공연이 없다)
- **시드의 "판매중" 회차가 적용 시점 기준 상대 날짜다**(`now() + interval '15~27 days'`).
  볼륨을 만든 지 15일이 지나면 일부가 닫히고 **27일이 지나면 ON_SALE이 0개가 되어 e2e가 깨진다.**
  그 시점에 `ReservationExpiryScheduler`가 status를 `CLOSED`로 바꾼다. CI e2e 잡은 매번 새
  서비스 컨테이너라 무사하고 **로컬 prod 볼륨만 썩는다.** 근본 수정 대신 "e2e 전에 `down -v`"를
  `.env.example`과 재현 방법에 못 박았다
- **세션 쿠키 `secure`가 false다.** 로컬 compose가 평문 HTTP라 켜면 쿠키가 저장되지 않아 e2e
  3개가 전부 깨진다. 지금 쓰지 않는 토글을 심지 않기로 하고 yml을 비워뒀다. Task 034에서 리버스
  프록시와 함께 켠다
- **세션이 서버 메모리라 BE는 1개뿐이다.** 2개로 늘리는 것이 Task 026이고, 그때
  `@EnableScheduling`(`@Profile("!test")`)이 양쪽에서 돌아 **같은 배치가 두 번 실행된다.**
  조건부 UPDATE라 데이터가 깨지지는 않지만(영향 행 수만 갈림) ShedLock 같은 수단이 필요해진다
- **헬스체크가 도메인 엔드포인트다.** `GET /api/v1/performances?size=1`의 permitAll이나 경로가
  바뀌면 compose가 조용히 unhealthy가 된다. 5초 주기 헬스 트래픽이 로그와 향후 메트릭에도 섞인다
- **이미지 크기 수치가 도구마다 다르다.** `docker save`와 `docker image inspect`는 BE 137MB /
  FE 89MB로 일치하는데 `docker images`는 434MB / 381MB를 보고한다. `--provenance=false
  --sbom=false`로 다시 빌드해도 434MB라 attestation은 원인이 아니다. **원인을 가리지 못했다** —
  이 문서의 수치는 `docker save` 기준이다
- **`tikkit.redis.enabled`는 false다.** `RedissonClient`를 주입받는 프로덕션 코드가 0건이라
  켜도 하는 일이 없고, 켜면 BE 기동이 Redis 상태에 묶인다. compose에는 서비스를 띄워 두고
  `depends_on`도 걸어뒀으니 Task 029/031에서 `.env` 한 줄로 켜면 된다
- **루트 `package-lock.json`은 남아 있다.** `outputFileTracingRoot`로 영향을 끊었을 뿐이다
- **FE 빌드가 devDependencies를 전부 설치한다.** 최종 이미지에는 안 남지만 빌드 시간은 든다

## 10. 다음 단계

- [Task 025: 모니터링](../ROADMAP.md) — actuator를 넣고 헬스체크를 `/actuator/health`로 옮긴다.
  이 compose에 Prometheus·Grafana를 추가한다. 5초 주기 헬스 트래픽이 지표에 섞이는 것을 감안해야 한다
- [Task 026: 세션 불일치](../ROADMAP.md) — 이 compose에 BE 2개 + nginx를 얹는다. 그때
  `proxy.ts`의 `new URL("/login", request.url)`이 `X-Forwarded-Host`를 안 보고 컨테이너 내부
  host로 리다이렉트하는 문제가 터질 수 있다. 배치 중복 실행도 같이 드러난다
- [Task 034: 클라우드 배포](../ROADMAP.md) — `docker build` 잡을 GHCR 푸시로 확장하고 이
  compose를 VM에 적용한다. 세션 쿠키 `secure`와 TLS가 여기서 켜진다

## 재현 방법

```bash
# 1. prod 스택 기동 (dev 스택을 끄지 않아도 된다 — 포트·컨테이너명·볼륨이 겹치지 않는다)
cd TIKKIT
cp .env.example .env
docker compose -f docker-compose.prod.yml down -v    # 시드 날짜 때문에 항상 볼륨을 비운다
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
#   -> 4개 서비스 전부 Up (healthy), 약 24초

# 2. layered jar 추출 결과 — JarLauncher가 아닌 것이 핵심
docker run --rm --entrypoint sh tikkit-backend:local -c "ls -1 /application && ls /application/lib | wc -l"
#   -> application.jar / lib / 121
docker run --rm --entrypoint sh tikkit-backend:local \
  -c "unzip -p /application/application.jar META-INF/MANIFEST.MF | grep -E 'Main-Class|Class-Path'"
#   -> Main-Class: com.tikkit.api.TikkitApplication
#   -> Class-Path: lib/....jar
docker run --rm --entrypoint id tikkit-backend:local
#   -> uid=100(spring)  비루트

# 3. 환경변수 누락 시 컨테이너가 정직하게 죽는지 (bootRun은 exit 0으로 가린다)
docker run --name tmp-noenv -e SPRING_PROFILES_ACTIVE=prod tikkit-backend:local
docker inspect tmp-noenv --format '{{.State.ExitCode}}' && docker rm -f tmp-noenv
#   -> 1

# 4. springdoc 비활성화와 404 (500이 아니어야 한다)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/v3/api-docs
#   -> 404
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/swagger-ui.html
#   -> 404
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/v1/performances?size=1"
#   -> 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/nonexistent-path
#   -> 401  (시큐리티가 먼저 끊는다)

# 5. 정적 자산 체인 (e2e가 못 잡는 실패 모드)
CSS=$(curl -s http://localhost:3000/performances | grep -oE 'href="/_next/[^"]+\.css"' \
      | head -n 1 | sed 's/href="//;s/"$//')
echo "$CSS"
#   -> 비어 있으면 아래 측정이 무의미하다 (빈 문자열을 붙이면 루트 요청이 되어 200이 찍힌다)
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" "http://localhost:3000$CSS"
#   -> 200 46647
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:3000/_next/static/media/PretendardVariable.0a.~5ku~863u1.woff2"
#   -> 200

# 6. Flyway 시드 토글 양방향
docker exec tikkit-postgres-prod psql -U tikkit_user -d tikkit_db -c \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
#   -> 1 1.1 2 3 3.1 3.2 4 4.1 5 5.1 6 (11개, 전부 t)
docker exec tikkit-postgres-prod psql -U tikkit_user -d tikkit_db -c \
  "SELECT status, count(*) FROM performances GROUP BY status;"
#   -> ON_SALE 3 / UPCOMING 3 / CLOSED 2   (ON_SALE이 0이면 7단계가 깨진다)
#
# 시드 없는 운영 경로:
#   docker compose -f docker-compose.prod.yml down -v
#   FLYWAY_LOCATIONS= docker compose -f docker-compose.prod.yml up -d
#   -> 마이그레이션 8개만, performances=0, 그래도 backend는 healthy

# 7. 완료 기준 — prod 스택 대상 e2e
cd tikkit-front && E2E_BASE_URL=http://localhost:3000 npm run test:e2e
#   -> 3 passed, webServer 미기동

# 8. 회귀 — 기존 로컬 워크플로우가 그대로인지
cd .. && docker compose -f docker-compose.prod.yml stop
cd tikkit-back && docker compose up -d && ./gradlew bootRun &      # dev 프로필
cd ../tikkit-front && npm run build && npm run test:e2e
#   -> 3 passed, 이번에는 webServer가 npm run start를 띄운다
```
