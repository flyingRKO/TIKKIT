# TIKKIT 개발 로드맵

단순한 예매 MVP에서 출발해 동시성·지정석·대기열·성능·운영을 단계적으로 개선하며, 매 단계를 재현 → 해결 → 수치로 증명하는 티켓 예매 서비스를 만든다.

## 개요

TIKKIT은 공연 탐색, 등급·수량 기반 예매(10분 선점), 모의 결제, 예매 관리 기능을 갖춘 MVP를 먼저 완성한 뒤, 포트폴리오 관점에서 의미 있는 고도화 트랙(동시성 제어, 지정석 전환, 운영/배포, 성능 최적화, 대기열)을 순서대로 진행한다. 각 고도화 Task는 "문제 재현 → 원인 분석 → 해결 → before/after 수치"를 `docs/improvements/`에 기록해 개선 과정 자체를 증거로 남긴다.

## 기술 스택

| 영역 | 기술 | 도입 시점 |
|---|---|---|
| 백엔드 | Spring Boot 3.4.5, Java 21, JPA, QueryDSL 5.1.0, MyBatis 3.0.4 | 기존 |
| 스키마 관리 | Flyway (flyway-core, flyway-database-postgresql) | Task 002 |
| 테스트 DB | Testcontainers PostgreSQL (H2 대체) | Task 002 |
| API 문서 | springdoc-openapi | Task 006 |
| 인증 | Spring Security + JJWT | Task 008 |
| 캐시/락/대기열 | Redis + Redisson | Task 020, 028, 030 |
| 모니터링 | Actuator + Micrometer + Prometheus + Grafana | Task 025 |
| 부하 테스트 | k6 | Task 026 |
| 프론트엔드 | Next.js 16.2.3, React 19.2.4, TypeScript 5, Tailwind v4 | 기존 |
| UI | shadcn/ui, next-themes | Task 003 |
| E2E | Playwright | Task 017 |

## 개발 워크플로우

1. `/git:branch feature/task-NNN-slug`로 브랜치 생성
2. 구현 및 테스트 작성
3. `/git:commit` → `/git:pr`
4. `/docs:update-roadmap`으로 완료 표시
5. 고도화 Task는 `docs/improvements/NNN-*.md` 작성 (문제 → 재현 방법 → 원인 분석 → 해결안 비교 → before/after 수치 → 한계·다음 단계)
6. 마일스톤 완료 시 git tag 추가: `v0.1.0-mvp` → `v0.2.0-concurrency` → `v0.3.0-seatmap` → `v0.4.0-ops` → `v0.5.0-performance` → `v1.0.0`

## 개발 단계

### Phase 0: 기반 정비

- **Task 001: [공통] 프로젝트 문서·설정 정합성 정리** ✅ - 완료
  - ✅ `tikkit-back/CLAUDE.md`의 DB명(`tikkit_dev` → `tikkit_db`)과 Spring Security 설명("Task 008에서 도입 예정")을 실제 상태에 맞게 수정
  - ✅ 루트 `CLAUDE.md`의 `/update-roadmap` 표기를 `/docs:update-roadmap`으로 수정, 문서 목록에 `docs/ERD.md`, `docs/improvements/` 추가
  - ✅ `.claude/agents/development-planner.md`의 기술 스택 표를 Java 17 → 21로 수정
  - ✅ `tikkit-back/.env.example` 추가 (`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`), `docker-compose.yml`·`application-dev.yml`과 값 일치 확인
  - ✅ 루트 README 골격 작성 (개요, 실행 방법, 문서 링크)
- **Task 002: [BE] 백엔드 공통 기반 구축** ✅ - 완료
  - ✅ Flyway 도입, `ddl-auto: validate`로 전환, `db/migration`(스키마)과 `db/seed`(dev 시드) 위치 분리
  - ✅ 테스트 프로필을 H2에서 Testcontainers PostgreSQL로 전환 (공통 베이스 테스트 클래스 `AbstractContainerTest`)
  - ✅ `ApiResponse<T>`, `PageResponse<T>`(Boot 3.3+의 `PageImpl` 직렬화 경고 회피), `ErrorCode` enum, `BusinessException`, `GlobalExceptionHandler` 추가
  - ✅ `BaseTimeEntity`(JPA Auditing), `JPAQueryFactory` 빈, CORS(`localhost:3000` 허용) 설정
  - ✅ `spring-restdocs-mockmvc` 의존성 제거 (springdoc으로 문서화 방식 통일)
- **Task 003: [FE] 프론트 초기화 및 브랜드 디자인 시스템** ✅ - 완료
  - ✅ 보일러플레이트 정리(기본 page.tsx/메타데이터/미사용 SVG 제거), `shadcn init` 실행 (base-nova 스타일)
  - ✅ `globals.css`에 퍼플/핑크 브랜드 토큰 적용 (아래 "브랜드 디자인 토큰" 참조), next-themes로 class 기반 다크모드
  - ✅ 폰트를 Geist에서 Pretendard로 교체, `components/{ui,layout}`, `lib/api`, `types` 폴더 생성
  - ✅ `.env.local.example` 추가 (`API_BASE_URL`)
- **Task 004: [공통] GitHub Actions CI 구성** ✅ - 완료
  - ✅ `.github/workflows/ci.yml`: BE 잡(`./gradlew test`, ubuntu-latest 내장 Docker로 Testcontainers 실행), FE 잡(`npm ci` → `lint` → `tsc --noEmit` → `build`)
  - ✅ `pull_request`(main 대상) + `push`(main) 트리거, `concurrency`로 중복 실행 취소
  - ✅ `tikkit-back/gradlew` 실행 권한 수정 (Linux 러너 `Permission denied` 방지)
  - ✅ README에 CI 배지 추가

### Phase 1: 도메인 골격 및 API 계약

- **Task 005: [BE] MVP 스키마(V1) 및 JPA 엔티티** ✅ - 완료
  - ✅ `V1__init_schema.sql` 작성 (venues~payments 7개 테이블, ERD의 제약·인덱스·한국어 COMMENT 전부 반영)
  - ✅ 5개 도메인 패키지(venue/member/performance/reservation/payment)에 엔티티·리포지토리 작성, 연관관계는 단방향 `@ManyToOne(LAZY)`만 사용
  - ✅ `V1_1__seed_dev_data.sql`: venue 4곳 → 공연 8개 × 회차 2~4개 × VIP/R/S 등급(60건), 테스트 계정 2개, 판매중/오픈예정/판매종료 3그룹으로 상태 분산, `performances`의 파생 컬럼(status/start_date/end_date)을 schedules 기준으로 재계산
  - ✅ Testcontainers 매핑 테스트: 이메일 대소문자 유니크, 예약~결제 그래프 왕복, 총액 CHECK 제약, 복합 FK 가드(ticket_grade_id·schedule_id)
- **Task 006: [공통] API 계약 정의 및 springdoc 설정** ✅ - 완료
  - ✅ `springdoc-openapi-starter-webmvc-ui:2.8.6` 추가 (Boot 3.4용 2.x 계열 마지막 버전), `/swagger-ui.html`에서 11개 엔드포인트 확인
  - ✅ 부록 B 전체 엔드포인트에 컨트롤러 스텁 + 요청/응답 DTO(record) 작성. 실제 로직은 각각 Task 008/009/012~013에서 연결 예정(주석으로 명시)
  - ✅ 에러 코드 표·공통 응답 포맷은 Task 002에서 이미 구현된 것을 그대로 사용
  - ✅ FE `types/api.ts`에 계약과 동일한 타입 수기 작성
- **Task 007: [FE] 라우트 구조 및 공통 레이아웃** ✅ - 완료
  - ✅ 부록 A 9개 라우트에 placeholder 페이지 생성, `(main)`(헤더+푸터)/`(auth)`(로고만) 라우트 그룹 구성
  - ✅ 헤더(로고 그라디언트, 데스크톱 네비, 로그인 링크는 Task 011에서 실제 세션으로 교체 예정), 푸터, 모바일 내비게이션(`useState` 토글), 다크모드 토글(`useSyncExternalStore`로 하이드레이션 불일치 방지)
  - ✅ 루트 `loading.tsx`/`error.tsx`/`not-found.tsx` 작성
  - ✅ `lib/api/client.ts`: `ApiResponse`를 언랩하는 `apiFetch` + `ApiError` (Server Component/Action 전용, `NEXT_PUBLIC_` 미접두라 브라우저에서는 미사용)
- **Task 008: [BE] 회원가입·로그인 (Spring Security + JWT)**
  - Spring Security, JJWT 도입, BCrypt 비밀번호, stateless `JwtAuthenticationFilter`
  - `/auth/signup`, `/auth/login`, `/members/me` 구현
  - `ApiResponse` 포맷을 따르는 401/403 핸들러
  - 서비스 단위 테스트 및 시큐리티 통합 테스트

### Phase 2: 공연 탐색

- **Task 009: [BE] 공연·회차 조회 API**
  - QueryDSL 기반 목록 조회 (카테고리, 키워드, 상태, 페이징)
  - 회차 목록을 포함한 상세 조회 엔드포인트
  - `GET /schedules/{id}/ticket-grades`로 실시간 잔여 수량 조회
  - 리포지토리·컨트롤러 테스트
- **Task 010: [FE] 메인·공연 목록·상세 화면**
  - Server Component 기반, 메인 페이지에 그라디언트 히어로 + "오픈 예정"/"예매 중" 섹션
  - 목록 페이지: 카테고리 탭, 검색, 페이지네이션 (URL searchParams 기반)
  - 상세 페이지: 포스터·정보, 회차/등급/수량 선택은 클라이언트 컴포넌트로 분리
  - Task 009 이전에는 목업 데이터로 개발 후 실제 API 연동, 전 구간 로딩/에러 상태 처리
- **Task 011: [FE] 인증 화면 및 세션 처리**
  - 로그인·회원가입 폼 (Server Action, 검증 메시지)
  - 토큰을 httpOnly 쿠키에 저장, 서버 사이드 fetch에 `Authorization` 헤더 첨부
  - `proxy.ts`로 `/booking`, `/my` 보호, `?redirect=` 처리
  - 로그아웃, 헤더의 로그인 상태 표시

### Phase 3: 예매·결제

- **Task 012: [BE] 예매 선점 API (PENDING 홀드)**
  - `POST /reservations`: 판매 기간, 수량(1~4매), 잔여 수량 검증
  - **의도적으로 동시성을 보장하지 않는 단순 차감** 구현 (`remaining -= quantity` 후 더티체킹으로 반영). 코드에 `// 동시성 미보장 — Phase 5(Task 018~020)에서 개선` 주석 명시, README "알려진 한계"에도 기록
  - `expires_at = now + 10분`, 예약번호 `TK{yyMMdd}-{6자리}` 생성
  - 단일 스레드 단위/통합 테스트
- **Task 013: [BE] 모의 결제·취소·만료 처리**
  - 결제(PENDING → CONFIRMED, payment row 생성) 및 취소(재고 복원, REFUNDED) 구현
  - `@Scheduled(fixedDelay = 60000)` 만료 배치, 상태 전이 규칙 강제 (아래 예약 상태 머신 참조)
  - 같은 배치 주기에 `performances.status`/`start_date`/`end_date`도 함께 재계산 (파생값이므로 별도 배치를 만들지 않고 여기 얹는다)
  - 내 예매 목록/상세 조회 (소유자 검증 포함)
  - 모든 상태 전이 테스트 (만료 후 결제 시도 → 409 포함)
- **Task 014: [FE] 예매·결제 플로우 화면**
  - 상세 페이지에서 옵션 선택 → `POST` → `/booking/[id]`로 이동
  - 결제 페이지: 카운트다운 타이머, 주문 요약, 모의 결제수단 선택
  - 완료 페이지, 매진/만료/판매 전 에러에 대한 안내 처리
  - 모바일 우선 하단 고정 CTA
- **Task 015: [FE] 마이페이지 예매 내역**
  - 상태 필터와 배지가 있는 목록
  - 상세 페이지, 취소 확인 다이얼로그, 취소 후 목록 갱신

### Phase 4: MVP 안정화 및 릴리스

- **Task 016: [FE] UX 완성도 점검**
  - 360/768/1280px 반응형 점검
  - 다크모드 대비, 빈 상태·에러·스켈레톤 상태 점검
  - 기본 접근성 점검 (label, focus ring)
- **Task 017: [공통] E2E 테스트 및 v0.1.0-mvp 릴리스**
  - Playwright: 회원가입 → 탐색 → 선점 → 결제 → 취소 시나리오
  - README 완성 (스크린샷, 아키텍처 다이어그램, "알려진 한계: 동시성" 섹션)
  - `v0.1.0-mvp` 태그 생성

### Phase 5: 동시성 제어 고도화

- **Task 018: [BE] 초과 판매 재현 테스트**
  - 좌석 10석에 100개 스레드로 동시 요청 (ExecutorService + CountDownLatch), 테스트가 실패하며 초과 판매를 재현
  - 결제-만료 배치 간 경쟁 상태도 재현
  - 결과 기록 (예약 건수 vs 총 재고)
  - `docs/improvements/001-overselling-reproduction.md` 작성
- **Task 019: [BE] DB 락 전략 적용 및 비교**
  - 비관적 락 (`@Lock(PESSIMISTIC_WRITE)`) 적용
  - 낙관적 락 (`V3__add_version_to_ticket_grades.sql` + 재시도 로직) 적용
  - 조건부 UPDATE(`WHERE remaining_quantity >= :qty`) 적용, 예약 상태 전이도 조건부 UPDATE(`WHERE status = 'PENDING'`)로 강화
  - 정확성·소요시간·재시도 횟수 비교 후 **조건부 UPDATE 채택**
  - `docs/improvements/002-db-lock-comparison.md` 작성
- **Task 020: [BE] Redis 분산 락 비교 실험**
  - docker-compose에 Redis 추가, Redisson `@DistributedLock` AOP 구현
  - "커밋 전 락 해제" 함정과 해결 방법 정리
  - DB 방식과 비교 후 "단일 DB에서는 불필요" 결론 도출
  - `docs/improvements/003-redis-distributed-lock.md` 작성, `v0.2.0-concurrency` 태그

### Phase 6: 지정석 전환

- **Task 021: [공통] 지정석 스키마 설계 및 확장 마이그레이션**
  - `docs/ERD.md`에 지정석 델타 반영 (seats, schedule_seats, reservation_seats — venues는 V1에 이미 있음)
  - `V4__create_seat_tables.sql` (expand 단계, 테이블·컬럼 한국어 COMMENT 포함)
  - `V5__backfill_seats.sql`, `V5_1__seed_venue_layouts.sql` (기존 예약을 좌석에 배정)
  - 마이그레이션 검증 테스트 (등급별 SOLD+HELD 건수와 기존 total-remaining 일치 확인)
- **Task 022: [BE] 좌석 조회·선점 API 전환**
  - `GET /schedules/{id}/seats` 추가, `POST /reservations` 요청 바디에 `seatIds` 추가 (`ticketGradeId`/`quantity`는 유지 — 한 예약=한 등급 정책)
  - 선택한 `seatIds`가 모두 동일한 `ticketGradeId`에 속하는지 애플리케이션 레벨 검증 (여러 테이블에 걸친 조건이라 DB CHECK로 불가)
  - 정렬된 ID 기준 다중행 조건부 UPDATE, "좌석당 한 명만 선점 성공" 동시성 테스트
  - 만료·취소 시 좌석 반환 처리 (`reservation_seats` 경유 UPDATE)
  - `V6__drop_quantity_columns.sql`: `ticket_grades`의 수량 컬럼만 제거 (contract 단계, `reservations` 스키마는 변경 없음), `docs/improvements/004-seatmap-migration.md` 작성
- **Task 023: [FE] 좌석 배치도 화면**
  - 등급별 색상이 표시되는 SVG/그리드 좌석 배치도
  - 최대 4석 선택, 요약 패널
  - 409 응답 시 배치도 재조회 및 안내, 모바일 확대/스크롤 대응
  - `v0.3.0-seatmap` 태그

### Phase 7: 운영 기반

- **Task 024: [공통] 컨테이너화 및 CD**
  - BE layered-jar Dockerfile, FE `output: 'standalone'` Dockerfile
  - `docker-compose.prod.yml`, GitHub Actions로 GHCR 푸시 후 VM에 SSH 배포
  - 환경변수·시크릿 관리. **배포 대상(EC2/Lightsail/Oracle Free 등)은 착수 시 사용자에게 확인**
- **Task 025: [BE] 모니터링 구축**
  - Actuator + micrometer-prometheus 연동, compose에 Prometheus·Grafana 추가
  - 대시보드: HTTP p95, HikariCP 풀, JVM, 예매 성공/실패 카운터
  - `v0.4.0-ops` 태그

### Phase 8: 성능 개선

- **Task 026: [공통] k6 부하 테스트 환경 및 베이스라인**
  - 대량 데이터 스크립트(`generate_series`로 공연 1,000개 × 회차당 좌석 약 2,000석, 전체 `schedule_seats` 약 600만 행 목표), dev 시드와 분리 관리
  - 시나리오: 탐색, 좌석 조회, 예매 스파이크
  - 베이스라인 리포트(p95, TPS, 에러율 + Grafana 캡처), `docs/improvements/005-performance-baseline.md` 작성
- **Task 027: [BE] 쿼리·인덱스 최적화**
  - N+1 쿼리 탐지(SQL 로그/쿼리 카운트) 및 fetch join·`@BatchSize`·DTO 프로젝션으로 해결
  - `EXPLAIN ANALYZE` 기반 인덱스 추가(`V7__add_indexes.sql`)
  - before/after 수치 기록, `docs/improvements/006-query-optimization.md` 작성
- **Task 028: [BE] 캐싱 적용**
  - Spring Cache + Redis로 공연 목록/상세 캐싱 (TTL, 변경 시 evict)
  - 좌석·잔여 수량은 캐싱 대상에서 제외하고 이유를 문서화
  - before/after 수치 기록, `docs/improvements/007-caching.md` 작성
- **Task 029: [FE] 렌더링 성능 개선**
  - Lighthouse 베이스라인 측정
  - `next/image`, `revalidate`/`cacheLife`(Next 16 문서 확인 필요), Suspense 스트리밍 적용
  - before/after 수치 기록, `docs/improvements/008-frontend-performance.md` 작성, `v0.5.0-performance` 태그

### Phase 9: 대기열 시스템

- **Task 030: [BE] Redis 대기열**
  - ZSET(score = 입장 시각) 기반 대기열, ZRANK로 순번 계산
  - 스케줄러가 초당 N명씩 활성 세트로 입장시키고 TTL 토큰 발급
  - 좌석·예매 API에 토큰 검증 인터셉터 적용, `V8` 마이그레이션의 `queue_enabled` 플래그로 회차별 on/off
  - 순서·입장·만료 테스트
- **Task 031: [FE] 대기열 화면**
  - `/queue/[scheduleId]`: 순번, 예상 대기시간, 그라디언트 진행바
  - 2초 간격 폴링, 입장 시 자동 이동, 페이지 이탈 경고
- **Task 032: [공통] 오픈런 부하 검증 및 최종 회고**
  - 대기열 유무에 따른 k6 스파이크 비교 (DB 커넥션, 에러율, p95)
  - `docs/improvements/009-waiting-queue.md`와 요약 인덱스 `docs/improvements/README.md` 작성
  - README 포트폴리오 섹션 마무리, `v1.0.0` 태그

## 브랜드 디자인 토큰 (Task 003 적용)

퍼플을 `primary`, 강한 핑크를 별도 `--highlight` 토큰으로 분리한다. shadcn의 `--accent`는 hover 배경으로 쓰이므로 강한 핑크를 넣으면 과해져, 옅은 핑크 틴트로 둔다.

| 토큰 | Light | Dark |
|---|---|---|
| primary (보라) | oklch(0.52 0.24 295) | oklch(0.68 0.20 295) |
| accent (옅은 핑크 틴트) | oklch(0.95 0.03 350) | oklch(0.30 0.06 350) |
| highlight (강한 핑크) | oklch(0.60 0.22 355) | oklch(0.72 0.20 355) |
| destructive | oklch(0.577 0.245 27.3) | oklch(0.704 0.191 22.2) |

- 그라디언트(`from-primary to-highlight`)는 로고, 메인 히어로, 예매/결제 CTA, 대기열 진행바에만 사용하고 본문·큰 배경에는 쓰지 않는다.
- 다크모드는 next-themes의 class 전략 + `@custom-variant dark (&:is(.dark *))`로 구현한다.
- `--radius: 0.75rem`, 좌석/티켓 등급 색상은 `--grade-vip/r/s/a`로 별도 정의한다.

## 예약 상태 머신

```
PENDING --(결제)--> CONFIRMED --(취소)--> CANCELLED
PENDING --(사용자 취소)--> CANCELLED
PENDING --(expires_at 경과, 스케줄러)--> EXPIRED
```

CANCELLED, EXPIRED 전이 시 재고(수량 또는 좌석)를 복원한다. Phase 5부터는 모든 전이가 현재 상태를 조건으로 하는 조건부 UPDATE로 이루어진다.

---

**📅 최종 업데이트**: 2026-09-24
**📊 진행 상황**: Phase 1 진행 중 (7/32 Tasks 완료)
