# 013. 쿼리 카운트 게이트 — 안 걸려 있던 게 아니라 안 보였다 (Task 042_1)

| 항목 | 내용 |
|---|---|
| 관련 Task | Task 042_1 (Phase 11: 성능 잔여 과제) — Task 042에서 떼어내 캐시 작업(036·044·045) 앞으로 옮겼다. 근거는 [007](./007-query-optimization.md) 남은 과제 · [008](./008-caching.md) 다음 단계 |
| 대상 코드 | `tikkit-back/build.gradle`, `support/QueryCountReport`(신규), `*QueryCountTest` 3개, `.github/workflows/ci.yml` |
| 측정 | **없다.** 이 Task는 장치이고 before/after 수치가 생기는 종류가 아니다. 대신 **게이트가 실제로 잡는 것을 재현**해서 증거로 남겼다 (3절) |
| 결론 | 로드맵이 적은 문제("CI에 안 걸려 있다")가 **틀렸다.** 테스트는 이미 CI에서 돌고 있었고, 007·008이 남긴 실제 공백은 **"문장 수가 늘었다를 PR에서 눈에 띄게 만드는 장치가 없다"** 하나였다. `@Tag("query-count")` + 독립 CI 잡 + Step Summary 표로 닫았다 |
| 알게 된 것 | **테스트가 돈다는 것과 결과가 읽힌다는 것은 다르다.** 통과한 실행에는 숫자가 한 톨도 남지 않아서, 21개 경로의 문장 수 계약이 테스트 소스 안에만 있었다 |
| 기각 | **기준값을 외부 베이스라인 파일로 빼는 것**(4.2절). **메서드 단위 태그**(4.3절). **기계가 읽을 JSON 산출물**(4.2절) |
| 작성 시점 | 2026-10-31 |

---

## 1. 진단 정정이 먼저다

### 1.1 로드맵이 적은 근거 세 줄이 틀렸다

Phase 11은 Task 035에서 데인 뒤로 "착수할 때 근거 문서의 해당 절을 먼저 읽고, 어긋나면 로드맵을
고치는 것부터 한다"를 규칙으로 달아 뒀다. 그대로 읽었더니 세 줄이 어긋나 있었다.

| 로드맵 | 실제 |
|---|---|
| "쿼리 카운트 테스트가 로컬 실행에만 의존한다" | `ci.yml`의 `./gradlew test`에 테스트 필터가 없고 `@Tag`가 레포 전체에 **0건**이었다. 테스트 파일 37개가 전부 CI에서 돌고 있었다 |
| 테스트 2개 클래스 | **3개다.** `ReservationQueryCountTest`(선점 6문장, 좌석 수 무관, 내 목록 1/2, 상세 3)가 빠져 있었다 |
| "간헐 실패를 게이트가 있었으면 들어올 때 걸렸다" | 그 세 테스트는 **그때도 CI에서 돌면서 운으로 통과**하고 있었다. 잡을 나누는 것은 간헐성을 바꾸지 않는다 |

**출처 쪽은 정확했다.** 007 523줄과 008 633줄의 실제 문장은 둘 다 이렇다 —

> 테스트는 있지만 `./gradlew test`에 섞여 돌 뿐이고, "문장 수가 늘어났다"를 PR에서 눈에 띄게
> 만드는 장치는 없다.

두 줄짜리 항목인데 **앞줄만 요약되어 로드맵으로 넘어가면서 뜻이 바뀌었다.** "섞여 돌 뿐이다"가
"안 돈다"가 됐고, 그 상태로 Task 번호까지 받았다. 011 2.1절이 밟은 것과 같은 자리이고
`README.md` 교훈 6이 말하는 전달 손실이다. 다른 점은 하나다 — **011은 기각이 전달되지 않은
경우이고, 여기는 요약이 뜻을 바꾼 경우다.** 둘 다 원문을 열어 보면 바로 보인다.

### 1.2 그래서 할 일이 바뀌었다

"CI에 걸기"였다면 `ci.yml`에 스텝 하나를 넣고 끝났을 것이다. 실제 공백이 가시성이라서 두 가지가
필요해졌다.

1. **실패가 구분되어야 한다** — 37개 파일이 한 잡에서 돌면 "문장 수가 늘었다"가 다른 실패와
   같은 빨간 X 하나로 보인다
2. **통과해도 숫자가 남아야 한다** — 리뷰어가 "선점은 6문장이어야 한다"를 테스트 소스를 열지
   않고 알아야, 숫자를 고치는 PR이 설명을 달고 올라온다

---

## 2. 만든 것

### 2.1 태그로 갈라 독립 잡을 만들었다

세 클래스에 `@Tag("query-count")`를 붙이고 `build.gradle`에서 `test`를 좁혔다.

```groovy
tasks.named('test') {
    useJUnitPlatform { excludeTags 'query-count' }
}

tasks.register('queryCountTest', Test) {
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    useJUnitPlatform { includeTags 'query-count' }
    outputs.upToDateWhen { false }
}

tasks.named('check') { dependsOn 'queryCountTest' }
```

`outputs.upToDateWhen { false }`가 필요한 이유는 2.2절에 있다 — UP-TO-DATE로 건너뛰면 요약 표가
만들어지지 않아 Step Summary가 빈다.

CI에는 `backend`와 **병렬로** 도는 `query-count` 잡을 뒀다. 벽시계 시간은 늘지 않고, Postgres 1개 +
Redis 1개만 띄우므로 `backend`(Redis 3개)보다 가볍다.

**`./gradlew test`의 뜻이 바뀌었다.** 전체는 `./gradlew check`다. 재현 커맨드로 `./gradlew test`를
"전부"로 적고 있던 문서 5곳(004·004_1·004_3·005·008)을 같이 고쳤다.

### 2.2 통과한 실행에도 표를 남긴다

`QueryCountReport`가 경로별 실측값을 모아 `build/query-counts.md`에 쓰고, CI가 그 파일을
`$GITHUB_STEP_SUMMARY`에 붙인다. `queryCountTest`가 만든 표 전문은 이렇다.

| 경로 | 문장 수 |
|---|---:|
| `GET /performances (size 20)` | 1 |
| `GET /performances (size 1)` | 2 |
| `GET /performances (캐시 miss)` | 1 |
| `GET /performances (캐시 hit)` | 0 |
| `GET /performances?keyword (캐시 안 함, 1회차)` | 1 |
| `GET /performances?keyword (캐시 안 함, 2회차)` | 1 |
| `GET /performances/{id}` | 2 |
| `GET /performances/{id} (캐시 miss)` | 2 |
| `GET /performances/{id} (캐시 hit)` | 0 |
| `GET /schedules/{id}/ticket-grades` | 2 |
| `GET /schedules/{id}/ticket-grades (캐시 miss)` | 2 |
| `GET /schedules/{id}/ticket-grades (캐시 hit)` | 1 |
| `GET /schedules/{id}/seats` | 2 |
| `GET /schedules/{id}/seats (캐시 켬, 1회차)` | 2 |
| `GET /schedules/{id}/seats (캐시 켬, 2회차)` | 2 |
| `GET /reservations (size 20)` | 1 |
| `GET /reservations (size 1)` | 2 |
| `GET /reservations/{id}` | 3 |
| `POST /reservations (1석)` | 6 |
| `POST /reservations (2석)` | 6 |
| `POST /reservations (4석)` | 6 |

007 5.1절 표와 전부 일치한다 — **이 Task는 숫자를 하나도 바꾸지 않았다.**

**기록할 때마다 파일을 통째로 다시 쓴다.** 엔트리가 21개라 비용이 없고, 셧다운 훅에 의존하지
않으므로 테스트가 중간에 깨져도 거기까지의 표가 남는다. 3절이 그게 필요한 이유다.

라벨 정렬이 그대로 표 순서다(`ConcurrentSkipListMap`). 공백(`0x20`)이 `/`(`0x2F`)보다 앞이라
`GET /performances (size 20)` → `GET /performances/{id}` 순으로 같은 경로의 변형들이 붙어 보인다.
정렬 순서를 따로 선언하지 않아도 되는 이유이고, 라벨을 007 5.1절 표기로 고정한 이유다.

---

## 3. 게이트가 실제로 잡는지 재현했다

장치를 만들어 놓고 "걸릴 것이다"로 두면 008 6.6절이 당한 것과 같다 — 기본 비활성 토글에 테스트를
안 써서 **히트 경로가 깨진 걸 부하 측정에서야 알았다.** 그래서 일부러 깨 봤다.

`TicketGradeRepositoryImpl.findByIdWithSchedule`의 `.fetchJoin()`을 한 줄 떼면 `Schedule` 프록시가
`schedule.isBookingOpen()`에서 초기화되면서 SELECT가 하나 더 나간다. Task 028이 7 → 6으로 줄인
바로 그 왕복이다.

```
ReservationQueryCountTest > 예매 선점은 좌석 수와 무관하게 6문장을 보낸다 FAILED
    org.opentest4j.AssertionFailedError:
    expected: 6L
     but was: 7L
```

같은 실행의 `build/query-counts.md`에 남은 값:

```
| `POST /reservations (1석)` | 7 |
| `POST /reservations (2석)` | 7 |
| `POST /reservations (4석)` | 7 |
```

**실패한 실행에서도 표가 남는 것이 설계 의도대로 동작했다.** `expected 6 but was 7` 한 줄만
보이면 "어느 경로가"를 테스트 이름으로 역추적해야 하는데, 표에는 세 변형이 다 7로 올라와 있어서
좌석 수와 무관하게 늘었다는 것(= 좌석마다 루프를 돈 게 아니라 고정비가 하나 늘었다는 것)까지
같이 보인다.

되돌린 뒤 `./gradlew queryCountTest`와 `./gradlew check`가 둘 다 통과하는 것을 확인했다.

---

## 4. 기각한 것

### 4.1 간헐 실패까지 잡으려는 것 — 이 게이트가 할 수 있는 일이 아니다

로드맵은 `CacheQueryCountTest` 3개의 간헐 실패를 "이 Task가 필요하다는 증거"로 인용했다.
실패 자체는 사실이고 `8e5f575`(Task 033)에서 `awaitCached()`로 고쳤다. 다만 **그 논거는
성립하지 않는다** — 그 테스트들은 그때도 CI에서 돌고 있었고 운으로 통과했다. 잡을 나누는 것은
실행 횟수를 늘리지 않으므로 간헐성 검출력도 늘리지 않는다.

**이 Task의 값은 "숫자가 바뀐 것을 리뷰어가 본다"는 쪽에만 있다.** 그 선을 넘어서 간헐 실패까지
잡으려면 반복 실행(`--rerun-tasks` × n)이 필요하고, 그건 CI 시간을 곱으로 늘리는 다른 결정이다.

### 4.2 기준값을 외부 베이스라인 파일로 빼는 것

`query-counts.json`을 커밋해 두고 CI가 비교하는 구조가 더 "제대로"처럼 보인다. 쓰지 않았다.

**회귀가 들어올 때 통과하는 경로가 생긴다.** 기준값이 데이터 파일에 있으면 숫자가 틀렸을 때
파일을 갱신하는 것이 자연스러운 수선처럼 보이고, 그 커밋은 리뷰에서 한 줄 diff로 지나간다.
기준값을 테스트 코드 안의 `isEqualTo(6)`으로 두면 고치려면 **왜 6이 7이 됐는지를 PR에 써야 한다.**
007이 숫자를 하드코딩한 선택을 그대로 유지한 것이고, 교훈 5("기각한 구현은 토글로도 남기지
않는다")와 같은 종류의 판단이다.

그래서 `build/query-counts.md`는 **게이트가 아니라 읽히기 위한 산출물**이고, 기계가 읽을 JSON도
만들지 않았다. 둘 다 있으면 다음 사람이 어느 쪽이 계약인지 헷갈린다.

### 4.3 메서드 단위 태그

`CacheQueryCountTest`에는 쿼리 수를 세지 않는 테스트 4개(`캐시_매니저_구성`, `적중률_지표_노출`,
`좌석_상태는_즉시_반영된다`, `없는_회차는_캐시하지_않는다`)가 섞여 있다. 깔끔하게 가르려면
메서드에 태그를 붙여야 하는데, 그러면 **Redis 컨테이너와 스프링 컨텍스트가 두 잡에서 모두 뜬다.**
분리의 이득(가벼운 잡)이 사라지므로 클래스 단위로 같이 옮겼다. 그 4개는 지금 `query-count` 잡에서
돈다 — 잡 이름과 내용이 조금 어긋나는 대가를 받아들였다.

---

## 5. 범위 밖에서 발견한 것

- **`CacheQueryCountTest`의 주석이 반증된 설명을 들고 있었다.** "Redis 비우기는
  `AbstractRedisCacheContainerTest`가 FLUSHDB로 처리한다"고 적혀 있는데, 그 베이스 클래스는
  FLUSHDB를 **폐기하고** `isolateCacheKeySpace()`(테스트마다 id 구간을 1,000씩 밀고 목록 페이지
  크기를 바꿔 키를 가른다)로 바꿨다. FLUSHDB가 같은 Redis를 쓰는 다른 컨텍스트까지 날린다는 게
  바꾼 이유다. 교훈 4("반증된 설명을 코드 주석에 남기면 다음 사람이 그걸 근거로 움직인다")의
  작은 판본이라 같이 고쳤다. 미사용 import 하나도 지웠다
- **CI에 `paths:` 필터가 없다.** README 한 줄만 고쳐도 잡이 전부 돈다. 이 Task가 잡을 하나 더
  늘렸으므로 비용이 조금 더 커졌다
- **Python stdout이 cp949로 잡히는 문제가 또 나왔다**(010 12.6절). 이번에는 em dash가 아니라
  한국어 메시지 한 줄에서 `UnicodeEncodeError`가 났다. 측정 스크립트가 아니라 일회성 편집
  스크립트였으므로 조치는 하지 않았다

---

## 6. 알려진 한계

- **간헐 실패 검출력은 늘지 않았다**(4.1절). 010 12.4절이 당한 종류의 실패는 이 게이트가 못 잡는다
- **`./gradlew test`의 뜻이 바뀌었다.** 로컬에서 전부 돌리려면 `check`를 알아야 한다. 문서 5곳을
  고쳤지만 **습관은 문서보다 느리게 바뀐다** — 누가 `test`만 돌리고 "전부 통과했다"고 할 수 있다
- **`check` 한 번에 테스트 워커 JVM이 둘이라 Testcontainers가 두 번 뜬다.** 로컬 전체 실행이
  그만큼 느려진다(이번에 `check` 59초, `queryCountTest` 단독 20초). CI에서는 잡이 병렬이라
  벽시계 손해가 없지만 총 러너 시간은 늘었다
- **`QueryCounter`는 Hibernate 밖 경로를 세지 않는다.** 픽스처를 `JdbcTemplate`으로 깔아도
  카운트에 섞이지 않는다는 장점의 뒷면이다 — 운영 코드가 `JdbcTemplate`이나 MyBatis로 쿼리를
  보내면 **게이트 밖이다.** 지금 MyBatis 매퍼는 통계·배치용이라 이 경로에 없지만, Phase 13이
  정산 배치를 MyBatis로 쓰면 그 왕복은 안 세진다
- **문장 수만 보고 그 문장이 얼마나 비싼지는 안 본다.** 007이 버퍼 접근을 88% 줄이고도 아무것도
  빨라지지 않은 것과 같은 축이다. 이 게이트가 통과한다는 것은 "왕복 수가 그대로다"까지만 보증한다
- **쿼리 수를 Testcontainers 소량 데이터에서 센다.** 문장 수는 데이터 크기와 무관하므로 값은
  유효하다는 007 7절의 판단을 그대로 유지했다

---

## 7. 다음 단계

- **[Task 042] 테스트 컨테이너 `max_connections`** — 이 Task는 컨텍스트를 새로 만들지 않았다
  (태그로 가른 것이라 수는 그대로다). 다만 036·044·045가 캐시 토글 조합을 늘리면 008 9절의
  벽에 다시 부딪힌다
- **[미배정] CI `paths:` 필터** — 문서만 바뀐 PR이 잡 5개를 돌린다 (5절)
- **[미배정] `frontend` 잡에 `timeout-minutes`가 없다** — 다른 네 잡은 전부 있다. 폭주하면
  기본값 360분까지 러너를 점유한다
- **[미배정] 문장 수 밖의 회귀 장치가 없다** — 6절 다섯째 항목. "문장 수는 그대로인데 한 문장이
  비싸졌다"는 지금 아무것도 안 잡는다. `EXPLAIN` 기반 게이트는 데이터 규모에 의존해서
  Testcontainers로는 못 만든다(007 9절)

---

## 재현 방법

```bash
# 0. Git Bash라면 경로 번역을 꺼야 한다 (007 6절). PowerShell이면 생략.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

# 1. 게이트만 — Postgres 1 + Redis 1. 약 20초
cd tikkit-back && ./gradlew queryCountTest
cat build/query-counts.md       # 2.2절 표가 그대로 나온다

# 2. test에서 빠진 것 확인 — "No tests found"가 나와야 한다
./gradlew test --tests '*QueryCountTest'

# 3. 게이트가 잡는지 (3절) — fetchJoin 한 줄을 떼고 돌린 뒤 되돌린다
#    src/main/.../TicketGradeRepositoryImpl.findByIdWithSchedule 의 .fetchJoin() 제거
./gradlew queryCountTest        # 선점 6 -> 7로 FAILED, 표에도 7이 남는다
git checkout -- src/main/java/com/tikkit/api/domain/performance/repository/TicketGradeRepositoryImpl.java

# 4. 전체
./gradlew check
```

CI 쪽은 PR에서만 확인된다 — 체크 목록에 `query-count`가 `backend`와 따로 뜨는지, 그 잡의
Summary 탭에 표가 보이는지. **Step Summary는 로컬에서 확인할 수 없는 유일한 항목이다.**
