# TIKKIT 개발 로드맵

단순한 예매 MVP에서 출발해 동시성·지정석·대기열·성능·운영을 단계적으로 개선하며, 매 단계를 재현 → 해결 → 수치로 증명하는 티켓 예매 서비스를 만든다.

## 개요

TIKKIT은 공연 탐색, 등급·수량 기반 예매(10분 선점), 모의 결제, 예매 관리 기능을 갖춘 MVP를 먼저 완성한 뒤, 포트폴리오 관점에서 의미 있는 고도화 트랙(동시성 제어, 지정석 전환, 운영/배포, 성능 최적화, 대기열)을 순서대로 진행한다. 각 고도화 Task는 "문제 재현 → 원인 분석 → 해결 → before/after 수치"를 `docs/improvements/`에 기록해 개선 과정 자체를 증거로 남긴다.

## 기술 스택

| 영역 | 기술 | 도입 시점 |
|---|---|---|
| 백엔드 | Spring Boot 4.1.1, Java 21, JPA(Hibernate 7.4), QueryDSL 5.1.0, MyBatis 4.1.0 | Task 026_1 (3.4.5에서 올림) |
| 스키마 관리 | Flyway 12 (spring-boot-starter-flyway + flyway-database-postgresql) | Task 002, 026_1 |
| 테스트 DB | Testcontainers 2.0 PostgreSQL (H2 대체) | Task 002, 026_1 |
| API 문서 | springdoc-openapi 3.1.1 | Task 006, 026_1 |
| 인증 | Spring Security (세션 기반, in-memory HttpSession) | Task 008 |
| 캐시/대기열 | Redis 7 + Lettuce (Redisson은 Task 031에서 제거 — 분산 락 기각 후 주입처 0건) | Task 020, 029, 031 |
| 모니터링 | Actuator + Micrometer + Prometheus + Grafana | Task 025 |
| 세션 저장소 | **Spring Session Data Redis**(indexed) — JWT와 비교 후 채택 | Task 026 |
| 로드밸런서 | nginx 1.27-alpine (BE 2대 라운드로빈, `/actuator` 차단) | Task 026 |
| 스케줄러 락 | ShedLock 7.10.1 + JDBC provider (`usingDbTime()`) | Task 026 |
| 부하 테스트 | k6 | Task 027 |
| 프론트엔드 | Next.js 16.2.3, React 19.2.4, TypeScript 5, Tailwind v4 | 기존 |
| UI | shadcn/ui, next-themes | Task 003 |
| E2E | Playwright | Task 017 |

## 개발 워크플로우

1. `/git:branch feature/slug`로 브랜치 생성 (Task 번호는 넣지 않는다)
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

### Phase 1: 도메인 골격 및 API 계약 ✅

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
- **Task 008: [BE] 회원가입·로그인 (Spring Security, 세션 기반)** ✅ - 완료
  - ✅ Spring Security 도입, BCrypt 비밀번호, 기본 세션(in-memory `HttpSession`) 방식으로 인증 — 서버가 1대인 MVP 단계에서는 JWT보다 구현이 단순하다. 다중 인스턴스로 확장할 때 생기는 문제와 대안(Redis 세션/JWT)은 Task 026에서 다룬다
  - ✅ `/auth/signup`, `/auth/login`(로그인 성공 시 `JSESSIONID` 쿠키 발급), `/auth/logout`, `/members/me` 구현
  - ✅ `ApiResponse` 포맷을 따르는 401/403 핸들러
  - ✅ 서비스 단위 테스트 및 시큐리티 통합 테스트

### Phase 2: 공연 탐색

- **Task 009: [BE] 공연·회차 조회 API** ✅ - 완료
  - ✅ QueryDSL 기반 목록 조회 (카테고리, 키워드, 상태, 페이징)
  - ✅ 회차 목록을 포함한 상세 조회 엔드포인트
  - ✅ `GET /schedules/{id}/ticket-grades`로 실시간 잔여 수량 조회
  - ✅ 서비스·리포지토리·컨트롤러 테스트
- **Task 010: [FE] 메인·공연 목록·상세 화면** ✅ - 완료
  - ✅ Server Component 기반, 메인 페이지에 그라디언트 히어로 + "예매중"/"오픈 예정" 섹션
  - ✅ 목록 페이지: 카테고리 탭, 검색, 페이지네이션 (URL searchParams 기반)
  - ✅ 상세 페이지: 포스터·정보, 회차/등급/수량 선택은 클라이언트 컴포넌트(`TicketSelector`)로 분리.
    실제 예매 API는 Task 012 이후라 "예매하기"는 비활성 처리
  - ✅ Task 009가 먼저 끝나 목업 없이 바로 실제 API 연동, 로딩(`loading.tsx`)·에러(`error.tsx`)·404(`notFound()`) 처리
- **Task 011: [FE] 인증 화면 및 세션 처리** ✅ - 완료
  - ✅ 로그인·회원가입 폼 (`useActionState` 기반 Server Action, 검증 메시지). 가입과 로그인은 분리해서 가입 후엔
    로그인 화면으로 보낸다(`?redirect=` 유지)
  - ✅ 브라우저는 BE를 직접 호출하지 않으므로, Next 서버가 BE의 `Set-Cookie: JSESSIONID`를 받아 httpOnly 쿠키로 저장하고, 서버 사이드 `apiFetch`에서 그 쿠키를 BE로 그대로 중계
  - ✅ `proxy.ts`(Next 16에서 `middleware.ts`가 이 이름으로 바뀜)로 `/booking`, `/my` 보호, `?redirect=` 처리 —
    쿠키 존재 여부만 보는 낙관적 확인이고 실제 유효성은 항상 BE가 401로 판단
  - ✅ 로그아웃(`/auth/logout` 호출 + 쿠키 제거), 헤더의 로그인 상태 표시(데스크톱/모바일 모두)

### Phase 3: 예매·결제 ✅

- **Task 012: [BE] 예매 선점 API (PENDING 홀드)** ✅ - 완료
  - ✅ `POST /reservations`: 판매 기간, 수량(1~4매), 잔여 수량 검증
  - ✅ **의도적으로 동시성을 보장하지 않는 단순 차감** 구현 (`remaining -= quantity` 후 더티체킹으로 반영). 코드에 `// 동시성 미보장 — Phase 5(Task 018~020)에서 개선` 주석 명시, README "알려진 한계"에도 기록
  - ✅ `expires_at = now + 10분`, 예약번호 `TK{yyMMdd}-{6자리}` 생성 (뒤 6자리는 `reservation_no_seq` DB 시퀀스로 채번)
  - ✅ 같은 회원이 같은 등급에 이미 PENDING 선점이 있으면 중복 선점 차단 (`DUPLICATE_PENDING_RESERVATION`, 코드 리뷰에서 도출되어 PRD에도 반영)
  - ✅ 단위(Service/Entity)·통합(Controller) 테스트
- **Task 013: [BE] 모의 결제·취소·만료 처리** ✅ - 완료
  - ✅ 결제(PENDING → CONFIRMED, payment row 생성) 및 취소(재고 복원, REFUNDED) 구현
  - ✅ 결제는 `PaymentGateway` 인터페이스 뒤에 `MockPaymentGateway`(UUID transactionKey, 항상 성공)를 둔다. 이후 실제 PG로 교체할 때 서비스 로직을 바꾸지 않기 위함
  - ✅ 취소는 PENDING이면 시점 제한 없이, CONFIRMED면 공연 24시간 전까지만 가능 (마감 후 `CANCEL_DEADLINE_PASSED`)
  - ✅ `@Scheduled(fixedDelay = 60000)` 만료 배치, 상태 전이 규칙 강제 (아래 예약 상태 머신 참조). MyBatis 없이 JPA `@Modifying` native 쿼리로 처리하고, 만료 처리와 재고 복원은 Postgres data-modifying CTE 한 문장으로 묶어 원자적으로 반영한다. 테스트 프로필에서는 스케줄링을 꺼서 통합 테스트에 배치가 끼어들지 않게 한다
  - ✅ 같은 배치 주기에 `performances.status`/`start_date`/`end_date`도 함께 재계산 (파생값이므로 별도 배치를 만들지 않고 여기 얹는다). 규칙: 판매 기간 안인 회차가 1개 이상이면 `ON_SALE`, 없지만 앞으로 열릴 회차가 있으면 `UPCOMING`, 그 외 `CLOSED`. `start_date`/`end_date`는 회차 `show_at`(KST 날짜)의 min·max
  - ✅ 내 예매 목록/상세 조회 (소유자 검증 포함)
  - ✅ 모든 상태 전이 테스트 (만료 후 결제 시도 → 409 포함)
  - ✅ 결제 처리 중 만료 배치가 동시에 도는 경쟁 상태(재고 이중 복원 가능성)는 막지 않고 Task 018의 재현 대상으로 남긴다 (README "알려진 한계"에도 기록)
- **Task 014: [FE] 예매·결제 플로우 화면** ✅ - 완료
  - ✅ 상세 페이지에서 옵션 선택 → `POST` → `/booking/[id]`로 이동 (Server Action + `useActionState`, 세션 만료 시 로그인 후 상세로 복귀)
  - ✅ 결제 페이지: 카운트다운 타이머, 주문 요약, 모의 결제수단 선택. 타이머는 서버가 계산한 남은 시간을 넘겨받아 브라우저 시계와 무관하게 센다
  - ✅ 완료 페이지, 매진/만료/판매 전 에러에 대한 안내 처리. 판매 전·종료 회차는 화면에서 먼저 비활성화하고, 최종 판단은 BE가 한다
  - ✅ 모바일 우선 하단 고정 CTA (360px 확인)
  - ✅ 같은 등급 중복 선점(409) 시 기존 PENDING 예약의 결제 페이지로 이동. BE 목록 응답에 `scheduleId`/`ticketGradeId`가 없어 공연명+회차 시각+등급 문자열로 매칭한다 (알려진 한계: 같은 조건의 예약이 여럿이면 오매칭 가능)
  - ✅ 타이머 0초는 화면에서만 만료 처리하고 cancel API는 호출하지 않는다. 재고 복원은 BE 만료 배치에 맡긴다
  - ✅ 브라우저 시나리오 검증 (선점 → 결제 → 완료, 중복 선점, 만료, 판매 전·종료 회차, 404, 모바일)
- **Task 015: [FE] 마이페이지 예매 내역** ✅ - 완료
  - ✅ 상태 필터와 배지가 있는 목록 (`?status=&page=`, 한 페이지 10건, 잘못된 status는 전체로 취급, 빈 상태 문구)
  - ✅ 상세 페이지: 주문 요약, 결제 정보(결제 상태·취소 일시), PENDING이면 결제하러 가기 링크
  - ✅ 취소 확인 다이얼로그(shadcn `alert-dialog`), 취소 후 목록으로 이동해 갱신 (`revalidatePath` + `redirect`). CONFIRMED는 환불 안내, PENDING은 선점 해제 안내 문구
  - ✅ 화면 상태는 BE 상태를 그대로 쓰지 않고 계산한다 (`lib/reservation-rules.ts`): `PENDING`이어도 `expiresAt`이 지났으면 만료로 표시, CONFIRMED는 공연 24시간 이내면 취소 버튼 대신 안내
  - ✅ 브라우저 시나리오 검증 (필터·페이지네이션, PENDING/CONFIRMED 취소·환불, 24시간 이내 취소 불가, 서버 마감 거절 시 다이얼로그 내 에러, 404, 세션 만료, 360px)
  - 알려진 한계: 목록 응답에 `expiresAt`이 없어 만료 배치가 돌기 전(최대 60초)에는 만료된 선점이 "결제 대기"로 보일 수 있다. 24시간 취소 규칙은 BE 규칙의 복사본이라 BE 규칙이 바뀌면 `CANCEL_DEADLINE_MS`도 같이 고쳐야 한다

### Phase 4: MVP 안정화 및 릴리스 ✅

- **Task 016: [FE] UX 완성도 점검** ✅ - 완료
  - ✅ 360/768/1280px 반응형 점검: 긴 텍스트 줄바꿈(`min-w-0 break-words`), 카드 배지 `flex-wrap`, 터치 영역 확대(수량 버튼 32px, 상태 필터 28px)
  - ✅ 다크모드 대비, 빈 상태·에러·스켈레톤 상태 점검: `components/ui/skeleton.tsx`와 loading.tsx 추가(홈은 섹션별 `Suspense`), `(main)/error.tsx`·`not-found.tsx`·`global-error.tsx` 추가, `EmptyState`·`FormError` 공통 컴포넌트, 다이얼로그 오버레이 다크 대비
  - ✅ 기본 접근성 점검 (label, focus ring): skip link, 검색창 `aria-label`, 공통 `focusRing`, MobileNav(`aria-expanded`·Esc·경로 변경 시 닫기), `aria-current="page"`, 제목 레벨 정리, 로그인·회원가입 `<main>` 랜드마크, `eslint-plugin-jsx-a11y` recommended 적용
  - ✅ API 래퍼 에러 정규화(`lib/api/client.ts`): 네트워크 실패는 `NETWORK_ERROR`, JSON이 아닌 응답은 `INVALID_RESPONSE`로 바꿔서 폼은 인라인 에러, BE가 꺼져도 Header는 로그인 영역만 숨기고 유지
  - ✅ 브라우저 시나리오 검증 (15개 페이지×폭 조합 가로 넘침, 예매→결제→취소, 빈 상태, 404, BE 다운 시 에러 화면·로그인 인라인 에러, "다시 시도" 복구, Tab 키보드 이동, 라이트·다크)
  - 알려진 한계: 서버에서 던진 에러는 프로덕션에서 `message`·`code`가 지워져 error.tsx에서 연결 문제를 구분할 수 없어 일반 문구를 쓴다. `unstable_retry`는 unstable API다. 모바일 하단 고정 CTA는 스크롤 끝에서 푸터를 가린다. 루트 `app/not-found.tsx`·`error.tsx`에는 헤더와 `<main>`이 없다
  - 후속 수정 완료: `lib/format-date.ts`가 서버에서 `PM 10:59`, 브라우저에서 `오후 10:59`로 렌더링해 상세 페이지에서 하이드레이션 불일치가 나던 문제(Task 010부터 있던 문제)는 `formatToParts()`로 오전/오후를 직접 지정해 해결했다
- **Task 017: [공통] E2E 테스트 및 v0.1.0-mvp 릴리스** ✅ - 완료
  - ✅ Playwright 도입(`playwright.config.ts`, chromium만 사용): 같은 DB를 공유하므로 `workers: 1`로 순차 실행하고, `e2e`(회귀)와 `screenshots`(README용) 프로젝트를 분리. FE는 `webServer`가 `next start`로 띄우고 BE(dev 프로필)와 DB는 밖에서 띄운다. BE 호출이 전부 Next 서버 쪽에서 일어나 `page.route`로 목킹할 수 없어서 실제 BE를 쓴다
  - ✅ 시나리오 `e2e/booking-flow.spec.ts`: 회원가입 → 로그인 → 예매중 공연 선택 → 선점 → 결제 → 예매 내역 → 취소(환불). 매번 새 회원으로 가입해 중복 선점(409)을 피하고, 끝에 취소해서 재고를 되돌린다. 공연 id는 고정하지 않고 `?status=ON_SALE` 목록의 첫 카드를 쓴다. `e2e/auth-guard.spec.ts`: 비로그인으로 `/my`, `/booking` 접근 시 `/login?redirect=`로 이동
  - ✅ `.github/workflows/ci.yml`에 `e2e` 잡 추가: Postgres 서비스 컨테이너 + `bootJar`로 BE 기동(`/api/v1/performances`로 기동 대기) + FE 빌드 후 Playwright 실행. 실패 시 리포트와 BE 로그를 artifact로 업로드. PR #12의 CI 러너에서 첫 실행에 테스트 3개가 통과했다(잡 약 2분)
  - ✅ README 완성: 스크린샷 6장(`docs/images/`, `npm run screenshots`로 재생성), mermaid 아키텍처 다이어그램, E2E 실행 방법. "알려진 한계" 섹션 3개(동시성, 결제-만료 배치 경쟁, PG 호출 트랜잭션)는 기존 것을 유지
  - ✅ `v0.1.0-mvp` 태그 생성 (PR #12 머지 후 main의 `a13c24c`에 주석 태그로 생성)
  - 알려진 한계: 시드 공연 날짜가 "시드 적용 시점" 기준이라 같은 DB를 약 50일 넘게 쓰면 예매중 공연이 없어져 E2E가 실패한다(`docker-compose down -v`로 초기화). E2E가 가입시킨 회원은 DB에 남는다(삭제 API 없음). 시드에 포스터 URL이 없어 스크린샷의 포스터는 placeholder다

### Phase 5: 동시성 제어 고도화 ✅

- **Task 018: [BE] 초과 판매 재현 테스트** ✅ - 완료
  - ✅ 좌석 10석에 100개 스레드로 동시 요청 (ExecutorService + 출발선 CountDownLatch), 초과 판매 재현. 4회 반복 모두 **100건 전원 성공(판매 100매 vs 총재고 10)**, `SOLD_OUT` 0건. 동시 진입한 트랜잭션들이 같은 값을 읽고 전부 `값-1`을 절대값으로 쓰므로 재고가 "세대당 1"씩만 줄어 재고 부족 검증이 아예 발동하지 않는다
  - ✅ 결제-만료 배치 간 경쟁 재현. 테스트 전용 `PaymentGateway`로 **실제 PG 승인 지연(5초)을 모사**하고 홀드가 그 사이에 끝나게 두는 방식 — 래치로 순서를 강제하면 "실제로 일어나는가"에 답할 수 없기 때문이다. 3회 모두 예약 CONFIRMED + 결제 PAID + 재고 복원(10/10)으로 불변식이 깨졌다
  - ✅ 결과 기록 (예약 수량 합 vs 총 재고). 초과 판매는 **재고 음수로 나타나지 않는다** — `ck_ticket_grades_remaining_range`는 항상 통과하므로 검증 지표를 `SUM(reservations.quantity)` vs `total_quantity`로 잡았다
  - ✅ **테스트는 "현재의 잘못된 동작"을 단정해 CI를 초록으로 유지**한다(`@Disabled`·`@Tag` 미사용). Task 019에서 단정만 뒤집으면 PR diff에 개선이 그대로 드러난다. 뒤집을 지점은 테스트 코드에 주석으로 표시
  - ✅ `@Transactional` 없는 전용 베이스(`support/AbstractConcurrencyTest`) 추가: 테스트 스레드의 미커밋 데이터는 워커 스레드에서 안 보여 롤백 격리로는 경쟁 자체를 만들 수 없다. 데이터를 커밋하고 `TRUNCATE ... RESTART IDENTITY CASCADE`로 정리하며, 커넥션 풀은 `@TestPropertySource`로 이 클래스만 30으로 올려 기존 테스트 20개에 영향을 주지 않는다
  - ✅ 회원 100명을 미리 만든다 — 중복 선점 가드가 `(회원, 등급, PENDING)` 단위라 같은 회원으로 100번 쏘면 1건만 성공하고 99건이 `DUPLICATE_PENDING_RESERVATION`으로 떨어져 재현되지 않는다. signup/login API를 100번 타면 BCrypt가 200번 돌아서, 인코딩을 1회로 줄이고 세션에 `SecurityContext`를 직접 심었다
  - ✅ `.github/workflows/ci.yml`의 `backend` 잡에 `timeout-minutes: 15` 추가 (동시성 테스트가 멈출 때 러너를 360분 점유하는 것을 막는다)
  - ✅ `docs/improvements/001-overselling-reproduction.md` 작성 (해결안 비교 절은 Task 019에서 채운다)
  - 알려진 한계: 재현이 커넥션 풀 크기에 의존한다 — 풀을 1로 줄이면 완전히 직렬 실행되어 초과 판매가 나지 않는다. 현재 설정(30)에서 4회 모두 100건이 성공했고 단정 임계값은 11건이라 여유가 크지만, CI 러너에서 분포가 달라지면 임계값을 재측정해야 한다. 시나리오 B는 10분을 기다릴 수 없어 `expires_at`을 SQL로 당기고, 스케줄러가 test 프로필에서 꺼져 있어 만료 배치 본체를 직접 호출한다. 취소와 만료가 겹치는 재고 이중 복원(`increaseRemaining`에 상한 검증 없음 → CHECK 위반 가능)은 재현하지 않고 Task 019 대상으로 남긴다
- **Task 019: [BE] DB 락 전략 적용 및 비교** ✅ - 완료
  - ✅ 네 가지(미보장 기준선 / 비관적 락 / 낙관적 락 / 조건부 UPDATE)를 `SeatHoldStrategy` 인터페이스로 분리해 같은 조건(10석·100스레드·풀 30)에서 측정. 비교가 끝난 추상화는 같은 PR 마지막 커밋에서 제거했다
  - ✅ 비관적 락: `em.refresh(grade, PESSIMISTIC_WRITE)`로 적용. **`@Lock` 쿼리 메서드를 쓰면 안 되는 함정**이 있다 — 엔티티가 이미 1차 캐시에 있으면 SQL은 `FOR UPDATE`로 나가 락은 잡히지만 Hibernate가 인스턴스 필드를 DB 값으로 덮어쓰지 않아, 락을 걸고도 낡은 값으로 계산해 초과 판매가 난다
  - ✅ 낙관적 락: `V3__add_version_to_ticket_grades.sql` + `@Version` + **수동 재시도 루프**(트랜잭션 밖). `spring-retry`를 안 쓴 이유는 `@Retryable`을 `@Transactional`과 같은 메서드에 붙이면 트랜잭션 안에서 재시도되는 함정 때문이다. `@Version`은 엔티티 단위로 전역이라 기준선(미보장)까지 낙관적 락이 걸려버려서, 기준선은 조건 없는 절대값 UPDATE로 재현했다
  - ✅ 조건부 UPDATE 적용 + **예약 상태 전이도 조건부 UPDATE로 강화**(`confirmIfPending`은 `WHERE status='PENDING' AND expires_at > :now`, `cancelIfStatus`는 읽은 상태를 WHERE에). 상태 전이에 성공한 트랜잭션만 재고를 복원하므로 취소↔만료 이중 복원이 구조적으로 사라졌다
  - ✅ 측정 결과 — 정확성은 세 전략 모두 동일(판매 10매), **조건부 UPDATE 채택**: 총 소요 60~72ms(미보장 287~322 / 비관적 100~121 / 낙관적 120~140), 요청 최대 지연 57~64ms(비관적 90~114 / 낙관적 120~139), 재시도 0회(낙관적은 100요청에 **114~138회**). 결정적 근거는 성능보다 구조였다 — 낙관적 락은 "모든 쓰기가 version을 올려야 한다"는 전역 규약을 요구하고 아무것도 강제하지 않는다(만료 배치의 네이티브 CTE가 실제로 어기고 있었다)
  - ✅ 결제-만료 경쟁 해결 + **보상 환불**: 확정이 0행이면 이미 떨어진 PG 승인을 환불하고 `RESERVATION_EXPIRED`로 실패시킨다. 새 에러 코드 없이 기존 계약을 유지했다
  - ✅ Task 018 재현 테스트의 단정을 뒤집어 **회귀 테스트로 전환**(201 10건 / 409 90건 / 불변식 10). 성공·거절 건수까지 단정해 "재고가 남았는데 거절"하는 회귀도 잡는다. 엔티티에서 재고·상태를 바꾸는 메서드를 **삭제**해 더티체킹이 조건부 UPDATE를 덮어쓸 경로를 없앴다
  - ✅ `V3_1__drop_version_from_ticket_grades.sql`로 `version` 제거. `V4`가 아니라 `V3_1`을 쓴 이유는 ERD 이력에 V4~V8이 미래 계획으로 잡혀 있어 번호가 7곳 밀리기 때문이다 (Flyway는 3.1로 해석해 V3와 V4 사이에 끼운다)
  - ✅ `docs/improvements/002-db-lock-comparison.md` 작성, `001`의 "해결안 비교" 절 채움, README "알려진 한계" 2개를 해결됨으로 갱신
  - 알려진 한계: 중복 선점 가드(`existsBy...` + INSERT)는 여전히 동시 요청에 취약하다 — `UNIQUE(member_id, ticket_grade_id) WHERE status='PENDING'` 부분 유니크 인덱스가 정답이지만 마이그레이션 번호를 또 소모해 범위 밖으로 뒀다. 보상 환불은 최선 노력이라 환불 실패 시 로그만 남는다(영속적 보상은 아웃박스+정산 배치가 필요, Phase 7). `DataIntegrityViolationException`은 여전히 500이다(예약번호 시퀀스 한 바퀴, 동시 이중 결제 등 Task 019와 무관한 제약). 측정은 로컬 Docker 기준이라 절대 수치가 아니라 전략 간 상대 비교로 읽어야 한다
- **Task 020: [BE] Redis 분산 락 비교 실험** ✅ - 완료
  - ✅ docker-compose에 Redis 7 추가(볼륨 없음, 영속화 끔), `tikkit.redis.enabled` **기본 false**로 둬서 Redis 없이도 기동·테스트가 돈다. `redisson-spring-boot-starter`를 쓰지 않고 core `redisson:3.50.0` + 직접 만든 `RedissonClient` 빈을 쓴 이유는 **스타터 자동설정이 기동 시점에 즉시 연결하고 실패하면 컨텍스트를 깨뜨리기** 때문이다 (CI의 backend 잡과 평소 로컬 개발이 전부 막힌다). `redisson-spring-data-NN` 버전 매트릭스도 따라붙는데 `RLock`만 쓰므로 spring-data-redis가 필요 없다
  - ✅ `@DistributedLock` AOP 구현(SpEL 키, `tryLock(waitTime, leaseTime)`, `isHeldByCurrentThread()` 가드). **이 프로젝트의 첫 커스텀 Aspect**였고 비교 후 제거했다
  - ✅ **축 A — 재고 차감**(10석·100스레드): 정확성은 세 arm 모두 동일(판매 10매)인데 **분산 락이 6~7배 느리다**. 총 소요 80~104ms(조건부 UPDATE 단독) vs 519~673ms(락, 트랜잭션 밖) vs 438~480ms(락, 커밋 전 해제)
  - ✅ **축 A에서 "커밋 전 락 해제" 함정이 수치로 드러나지 않는다** — 락을 잘못 걸어도 판매는 정확히 10매다. 아래 깔린 조건부 UPDATE가 받쳐주기 때문이다. 즉 **분산 락의 정합성은 자기 자신이 증명하지 못하고 DB 보장이 증명해준다**. 게다가 함정 arm이 **오히려 빠르다**(438~480 vs 519~673) — 커밋 전에 락을 풀면 다음 스레드가 앞 스레드의 커밋과 겹쳐 돌 수 있어서, 정합성을 팔아 처리량을 산 셈이고 벤치마크만 보면 "더 나은 구현"으로 보인다
  - ✅ **축 B — 중복 선점 가드**(같은 회원·20스레드): Task 019가 한계로 남긴 `existsBy...` 후 INSERT 레이스를 먼저 재현했다. **20건 전원 통과**(PENDING 20건). 일부가 아니라 전부인 이유 — 재고 차감이 조건부 UPDATE로 직렬화되는데도 **가드 통과 여부는 락을 잡기 전에 이미 결정**돼서, 뒤에서 행 락이 직렬화해 줘도 앞의 판단을 되돌리지 못한다
  - ✅ 축 B에서 분산 락은 막아준다(1건). 하지만 `V3_2` 부분 유니크 인덱스(`UNIQUE(member_id, ticket_grade_id) WHERE status='PENDING'`)를 넣은 뒤로는 **락 없이도 1건**이고, **일부러 락을 잘못 건 arm(커밋 전 해제 + 2ms 지연)도 1건**이 된다. 축 A와 똑같은 구도다. 락이 반드시 필요했던 유일한 자리마저 제약으로 메워져 이제 락이 하는 일은 지연 2배(41~46ms → 81ms)뿐이다
  - ✅ **"단일 DB에서는 불필요" 결론**. 결정적 근거는 성능이 아니라 구조다 — 분산 락은 "모든 쓰기 경로가 같은 키로 락을 잡아야 한다"는 전역 규약을 요구하고 아무것도 강제하지 않는다(Task 019가 낙관적 락을 기각한 논리와 같다). DB 제약은 어느 경로로 들어와도 성립한다
  - ✅ **중복 선점은 부분 유니크 인덱스로 영구 채택**(`V3_2`, Task 019 알려진 한계 해소). `DataIntegrityViolationException`을 서비스에서 제약명으로 가려 409 `DUPLICATE_PENDING_RESERVATION`으로 바꿔 **새 에러 코드 없이 기존 계약을 유지**했다. `GlobalExceptionHandler`에 두지 않은 이유는 거기서는 제약을 구분할 수 없어 예약번호·이중결제 위반까지 뭉개기 때문이다. `Reservation`의 PK가 `IDENTITY`라 `save()` 시점에 INSERT가 나가서 서비스 안에서 잡을 수 있다(`SEQUENCE`면 커밋 시점에 터져 못 잡는다). `existsBy` 가드는 재고를 깎고 롤백하는 낭비를 줄이는 빠른 경로로 남겼다
  - ✅ 재현 테스트의 단정을 뒤집어 **회귀 테스트로 전환**(`중복_선점_차단`: 201 1건 / 409 19건 / PENDING 1건 / 잔여 19)
  - ✅ 구현 함정 6개 정리: **`@Order(HIGHEST_PRECEDENCE)`는 너무 높아서 깨진다**(`ExposeInvocationInterceptor`가 `HIGHEST_PRECEDENCE + 1`이고 그게 먼저 돌아야 `@annotation(x)` 바인딩이 성립 → `@Order(0)`) / `@Order`를 안 주면 트랜잭션 어드바이저와 동점이 되어 순서가 미정의 / `@Order`만으로 락을 트랜잭션 안쪽에 둘 수 없다 / 자기 호출은 Aspect를 건너뛴다 / 조건부 빈은 락을 조용히 무력화한다 / **`src/test`의 `@RestController`는 모든 테스트 컨텍스트에 스캔된다**(무관한 테스트 59개가 깨져서 패키지를 `com.tikkit.api` 밖으로 옮겼다. `@RequestMapping`만 남기는 우회는 Spring 6.2의 `isHandler()`가 `@Controller`만 보기 때문에 통하지 않는다)
  - ✅ 실험 종료 후 정리: `@DistributedLock` AOP·측정 하네스·`spring-boot-starter-aop`·`LOCK_ACQUISITION_FAILED` 제거(`17 insertions, 931 deletions`). **Redis 인프라는 남긴다**(Task 029 캐싱·031 대기열에서 재사용). 실험 코드를 `src/test`에 둔 덕에 정리 커밋이 파일 삭제로 끝났다 — Task 019는 전략을 런타임에 갈아끼워야 해서 main에 뒀고 정리 때 운영 코드 여러 곳을 건드려야 했다
  - ✅ `docs/improvements/003-redis-distributed-lock.md` 작성, `002`의 "다음 단계"에 결과 기록, README에 "중복 선점 차단(해결됨)" 섹션 추가, ERD·`db-design` 스킬의 서브버전 규칙 갱신
  - ✅ `v0.2.0-concurrency` 태그 생성 (PR #16 머지 후 main의 `c64f7a2`에 주석 태그로 생성. 커밋 8개를 머지 커밋으로 보존했다 — `003` 문서가 삭제한 측정 코드를 커밋 SHA로 참조하므로 squash하면 재현 방법이 깨진다)
  - 알려진 한계: 결론은 **단일 DB 전제**에서만 유효하다 — DB를 샤딩하거나 DB 밖 자원(외부 API 쿼터, 파일)을 보호해야 하면 분산 락 외에 선택지가 없다. Testcontainers Redis는 같은 호스트 루프백이라 **측정된 Redis 오버헤드는 하한**이다(운영은 0.5~2ms). 단일 인스턴스를 썼으므로 Redlock 논쟁은 범위 밖이다. 인덱스는 `PENDING`만 제한하므로 같은 회원의 `CONFIRMED` 중복은 여전히 허용된다(의도된 동작). `DataIntegrityViolationException` 일반 매핑은 아직 500이다(`uk_reservations_reservation_no`, `uk_payments_reservation_id`). 측정은 로컬 Docker 기준이라 **방식 간 상대 비교**로만 읽어야 한다

### Phase 6: 지정석 전환 ✅

- **Task 021: [공통] 지정석 스키마 설계 및 확장 마이그레이션** ✅ - 완료
  - ✅ `docs/ERD.md`에 지정석 델타 반영 (seats, schedule_seats, reservation_seats — venues는 V1에 이미 있음). 2절에 설계가 선반영돼 있어서 ERD가 비워둔 구체 수치와 제약만 채웠다
  - ✅ `V4__create_seat_tables.sql` (expand 단계, 테이블·컬럼 한국어 COMMENT 26개 포함). **엔티티는 만들지 않았다** — `ddl-auto: validate`는 매핑 안 된 테이블을 문제 삼지 않으므로 "애플리케이션은 좌석을 모르지만 스키마는 준비된" expand 상태가 코드로 성립한다. 엔티티 설계(상태 변경 메서드를 둘지, `reservationId`를 연관으로 둘지)는 Task 022의 선점 쿼리 모양이 정해져야 답이 나온다
  - ✅ **`fillfactor = 90`은 `schedule_seats`에만** 걸었다. ERD는 "인덱스를 안 걸어 HOT을 보존한다"만 적었는데 **인덱스가 없어도 페이지에 빈 공간이 없으면 HOT이 깨진다** — fillfactor가 그 전제 조건이고 `CREATE TABLE` 시점에만 깔끔하게 걸린다(나중에 `ALTER`하면 이미 쓰인 페이지엔 안 먹고 `VACUUM FULL`이 필요). 반복 UPDATE가 없는 `seats`(INSERT만)·`reservation_seats`(append-only)는 기본값 100 — 10%를 비우면 저장 공간만 낭비하고 읽을 페이지가 늘어난다
  - ✅ `ck_schedule_seats_status_holder` 추가(ERD에 없던 제약). `AVAILABLE ⟹ reservation_id IS NULL`, `HELD`/`SOLD` ⟹ NOT NULL을 **양방향으로** 걸었다 — 한쪽만 적으면 "선점이 풀렸는데 점유자가 남은" 반대 방향 모순이 통과한다. 단일 컬럼 CHECK(`seat_number >= 1`, `price >= 0`)는 V1 관행대로 이름 없는 인라인으로 뒀다
  - ✅ **좌석 명명은 국내 예매처 관행("구역 / 열 / 번")을 따랐다.** 열은 알파벳이 아니라 숫자(`'1'`, `'23'`)다 — 서구권은 `Row A`에 `I`를 건너뛰지만 국내는 "3열 12번"이고, 숫자를 쓰면 백필에서 27열 이상을 `AA`로 넘기는 분기도 없어진다. 구역은 등급별 **좌·중앙·우 3분할**(실제 공연장 구조, `pos_x`에 통로가 자연히 생겨 배치도가 제대로 나온다). 좌석 번호는 구역마다 1번부터 재시작
  - ✅ `V5__backfill_seats.sql` — 그리드 생성 → 등급 배정 → 기존 예약 좌석 배정. 그리드는 venue별 등급 `MAX(total_quantity)` 기준이고 열당 좌석 수는 `CASE WHEN 총좌석 <= 1000 THEN 20 ELSE 40 END`(돔에 20석/열을 쓰면 160열이 되어 실제 공연장과 동떨어진다). 블록 비율 `(좌 1, 중 2, 우 1)/4`로 두면 **열당 좌석 수가 4의 배수라 정수 나눗셈이 정확**하다 — 소수 비율은 반올림 오차로 블록 합이 어긋난다
  - ✅ 예약 배정은 **데이터 변경 CTE 한 문장**(`expirePendingReservations`와 같은 패턴). `matched` CTE를 `held`(UPDATE)와 최종 INSERT가 **둘 다** 참조해서 Postgres 12+가 자동 materialize하고, 그래서 두 구문이 같은 매칭 결과를 본다. 한 번만 참조하면 인라인되어 각자 재계산할 수 있고 배정 좌석이 어긋날 수 있다
  - ✅ **세 statement 모두 멱등**(`ON CONFLICT DO NOTHING` / `NOT EXISTS`)하고 `DO` 블록을 쓰지 않는다. 운영 재실행 안전성 때문만이 아니라 **검증 테스트가 이 파일을 그대로 재실행**하는 구조라 필수가 됐다(`ScriptUtils`의 세미콜론 분리가 달러 인용 본문에서 깨진다). 그래서 백필 안에서 예외를 던지는 흔한 패턴을 못 쓰고 검증을 테스트로 밀어냈다 — "어떻게 테스트할지"가 "프로덕션 SQL을 어떻게 쓸지"를 역방향으로 제약한 사례
  - ✅ **범위 추가: `V4_1__adjust_venue_quantities.sql`(db/seed, dev 전용).** V1_1이 `CROSS JOIN`으로 모든 회차에 VIP 30 / R 80 / S 120을 똑같이 넣어서 1만 5천석 돔과 1,700석 소극장이 전부 230석이었다. 백필 공식은 이미 venue별인데 **입력이 균일해서 결과가 똑같아진다**. V1_1을 직접 고치지 않은 이유는 이미 적용된 마이그레이션이라 Flyway 체크섬이 깨지기 때문이고, 4.1은 V4 뒤·V5 앞에 돌아서 백필이 조정된 수량을 그대로 읽는다. 실제 규모의 약 1/5(고척 3200 / KSPO 3000 / 예술의전당 468 / 블루스퀘어 350)로 넣고 돔 2곳에만 A 등급을 추가했다 — `grade` CHECK에 A가 있는데 한 번도 쓰이지 않아 `--grade-a` 색상 토큰을 검수할 수 없었고, 등급 수가 공연장마다 다른 경우까지 함께 커버한다
  - ✅ `V5_1__seed_venue_layouts.sql`(db/seed, dev 전용)은 **구역명만 다듬는 역할로 축소**했다(`VIP-중` → `VIP석 중앙`, `section`이 `varchar(10)`이라 최대 `VIP석 좌측` 7자). 원래 핵심이던 HELD/SOLD 상태 샘플은 dev DB에 예매 테스트로 쌓인 예약이 있어 V5가 알아서 배정했으므로 생략했다. 좌표 보정도 통로가 이미 들어가 있어 불필요했다. **순서에 양방향 제약이 있다** — `section`은 V5 [2/3]이 `split_part(section, '-', 1)`로 등급을 되찾는 기준이라 먼저 돌면 `schedule_seats`가 하나도 안 생기고, 거꾸로 이 UPDATE 뒤에는 구분자가 사라져 V5 수동 재실행이 매칭되지 않는다
  - ✅ 마이그레이션 검증 테스트 2종, 새 `com.tikkit.api.migration` 패키지(레이어가 아니라 테스트 종류라 `integration/`과 나란히 둔다. 엔티티가 없어 미러링할 main 패키지도 없다). 엔티티 대신 `JdbcTemplate` — 검증 대상이 원시 SQL과 스키마 자체라 Hibernate 변환이 끼면 안 된다
  - ✅ `SeatBackfillMigrationTest` 10개: 등급별 `SOLD+HELD == total - remaining`(로드맵 요구) 외에 `COUNT(schedule_seats) == total_quantity`(그리드 올림 여분이 새어들지 않음), SOLD/HELD 분리(점유 건수만 보면 두 상태가 뒤바뀌어도 통과한다), 예약별 좌석 수 == 매수, 가격 합 == 결제액, 취소·만료 예약 좌석 0건, 한 좌석 활성 예약 1건, **좌석 공연장 == 회차 공연장**(3홉이라 복합 FK로 표현 불가 — DB가 보장하지 못하는 유일한 불변식), 한 예약 한 등급, 멱등성
  - ✅ 테스트 프로필은 `db/seed`를 로드하지 않아 **기동 시 V5 실행에는 검증할 데이터가 없다**. 그래서 픽스처를 넣고 `ScriptUtils`로 V5를 재실행한다. `DataSourceUtils.getConnection()`을 쓰는 게 핵심 — `dataSource.getConnection()`을 직접 부르면 테스트 트랜잭션 밖 커넥션을 받아 픽스처가 안 보이고 스크립트가 쓴 데이터가 다음 테스트로 샌다. **`EncodedResource`로 UTF-8을 명시**해야 한다(V5의 블록 이름 `'좌'`/`'중'`/`'우'`가 한글 문자열 리터럴이라 플랫폼 기본 인코딩으로 읽으면 `section` 값이 깨진다). 기각한 대안: test 전용 Flyway location에 픽스처를 넣는 방식은 싱글턴 컨테이너를 모든 컨텍스트가 공유해서 낮은 버전을 뒤늦게 들고 들어가면 Flyway가 기동을 깬다
  - ✅ `SeatSchemaConstraintTest` 8개(`db-design/repository.md`가 요구하는 복합 FK 어긋남 테스트 포함). 제약 위반은 **메서드당 한 건**씩만 — Postgres는 위반 후 트랜잭션을 abort해 `current transaction is aborted`로 후속 쿼리를 전부 거부한다. 계획에 없던 `점유자_있는_AVAILABLE_좌석은_거부된다`를 추가해 CHECK의 반대 방향도 고정했고, `취소된_예약도_같은_좌석을_이력으로_가질_수_있다`로 `schedule_seat_id` 단독 UNIQUE를 두지 않은 설계 의도를 확인했다
  - ✅ **`status IN (...)` CHECK는 알 수 없는 상태를 막는 역할에서 중복**이라는 걸 테스트가 드러냈다. `'RESERVED'`를 넣으면 `schedule_seats_status_check`가 아니라 `ck_schedule_seats_status_holder`가 보고된다 — 세 값 중 어느 것도 아니니 holder의 두 분기를 모두 못 만족한다. 둘 다 위반이고 어느 쪽을 보고할지는 Postgres가 정하므로 테스트에서 제약명을 고정하지 않았다. IN 목록은 허용 값을 스키마에 드러내는 문서 역할로 남긴다
  - ✅ dev 적용 결과: `seats` 7,120 / `schedule_seats` 26,894 / `reservation_seats` 41. 공연장별 그리드가 실제로 갈라졌다(고척 80열 / 블루스퀘어 19열). 전체 테스트 125개 통과
  - 알려진 한계: **`V5`가 PENDING 예약을 HELD로 만들지만 Task 013의 만료 배치는 좌석을 모른다.** 만료된 예약의 좌석이 HELD로 고착되며, 과거 PENDING을 건너뛰면 핵심 불변식이 깨지므로 건너뛸 수도 없다 → Task 022의 좌석 반환이 **필수 후속**이다(아직 운영 중이 아니라 실제 피해는 없다). `schedule_seats.reservation_id`를 FK로 둬서 선점 UPDATE마다 부모 `reservations` 행에 `FOR KEY SHARE` 락이 잡힌다 — 한 예약당 한 사용자라 경쟁은 낮다고 봤지만 Task 022 동시성 측정에서 multixact 오버헤드가 보일 수 있다. 열당 좌석 수(20/40)와 "VIP가 무대에 가깝다"는 배치는 PRD·ERD에 근거가 없는 자체 판단이라 Task 023 화면을 보고 조정할 수 있다. 수량 정의가 `V1_1`과 `V4_1` 두 파일에 나뉘어 있다. V6 이후에는 좌석 수의 원천이 `seats`+`schedule_seats`로 뒤집히는데 PRD "MVP 제외 범위"에 관리자 기능이 빠져 있어 **새 공연장 좌석을 만드는 경로가 시드/수동 SQL뿐**이다
- **Task 022: [BE] 좌석 조회·선점 API 전환** ✅ - 완료
  - ✅ 엔티티 4개 생성 — `Seat`(`domain/venue`), `ScheduleSeat`·`SeatStatus`(`domain/performance`), `ReservationSeat`(`domain/reservation`). 소유 애그리거트를 따라 쪼개 `domain/seat/` 허브를 만들지 않았다. **`TicketGrade`와 같이 상태 변경 메서드를 두지 않았다** — 더티체킹 UPDATE가 조건부 UPDATE를 덮어쓰는 경로를 아예 만들지 않는 Task 019의 결정이 그대로 옮겨왔다
  - ✅ `GET /api/v1/schedules/{id}/seats` 추가 (평면 배열, `ORDER BY pos_y, pos_x`). 로그인 없이 조회 가능 — 등급·잔여 수량이 이미 공개인데 배치도만 막으면 "자리 보고 가입"하는 흐름이 깨진다
  - ✅ `POST /reservations`에 `seatIds` 추가 (`ticketGradeId`/`quantity` 유지 — 한 예약=한 등급 정책). `seatIds`는 `schedule_seats.id`이고 물리 좌석이 아니다
  - ✅ `seatIds`가 모두 같은 `(회차, 등급)`에 속하는지 애플리케이션 검증 (세 테이블 교차라 DB CHECK로 불가) → 404. 중복 좌석·매수 불일치는 400으로 끊는다 — **중복을 조용히 제거하면** 선점 좌석 수가 매수보다 적어져 `SOLD_OUT`(409)으로 떨어지고 에러가 실제 원인을 가린다
  - ✅ 정렬된 ID 기준 다중행 조건부 UPDATE + `reservation_seats` INSERT를 데이터 변경 CTE 한 문장으로. 반환값(INSERT 행 수) ≠ 요청 좌석 수면 `SOLD_OUT`으로 롤백 — **부분 선점을 DB 제약이 아니라 이 검사가 막는다**
  - ✅ 동시성 테스트 전환·추가 — 초과 판매 차단(좌석 10석에 100명, `i % 10`번 좌석 → 정확히 10건 성공), 좌석당 한 명만 선점(1석에 100명 → 1건), **겹치는 좌석 집합 동시 선점**(`A{0,1,2} B{2,3,4} C{1,4,5}` → 1건, 데드락 0건, 부분 선점 0건), 중복 선점 차단, 결제-만료 경쟁
  - ✅ `AS MATERIALIZED`는 **붙이지 않았다.** 선점·만료 배치 모두 CTE를 한 번만 참조하므로 인라인돼도 결과가 달라지지 않는다. 판단 근거를 메서드 Javadoc에 적어 "참조가 늘면 명시하라"는 신호를 남겼다
  - ✅ 만료·취소 시 좌석 반환 (`reservation_seats` 경유 UPDATE) — **Task 021의 필수 후속 해소.** 만료 배치의 `restored` CTE(등급별 `SUM` 합산)가 사라졌다. 좌석은 1:1 매칭이라 합산이 필요 없다. 반환값 의미가 "복원된 등급 수" → "반환된 좌석 수"로 바뀌어 로그 문구도 고쳤다
  - ✅ `V6__drop_quantity_columns.sql` (contract) — `ticket_grades`의 수량 컬럼만 제거, `reservations` 스키마 무변경. `ck_ticket_grades_remaining_range`는 `DROP COLUMN`에 딸려 자동 삭제되고(롤백 트랜잭션에서 확인) 복합 FK가 의존하는 `uk_ticket_grades_id_schedule`은 생존
  - ✅ `remainingQuantity`를 좌석 AVAILABLE 건수로 파생 — **API 응답 형식이 그대로라 FE 컴포넌트·e2e 스펙을 한 줄도 고치지 않았다.** 이중 쓰기를 피한 이유: 두 재고를 같이 갱신하면 `ticket_grades` 한 행 경쟁이 남아 좌석으로 쪼갠 효과가 상쇄된다
  - ✅ FE는 `types/api.ts`·`lib/api/performances.ts`·`lib/actions/reservation.ts` 세 파일만. **Server Action이 좌석을 골라 `seatIds`로 변환**하므로 컴포넌트 수정이 없다 (`pickAvailableSeatIds`는 한시적 코드, Task 023에서 제거)
  - ✅ `docs/improvements/004-seatmap-migration.md` 작성. 실측: **`schedule_seats` HOT 업데이트 100%**(Task 021 미측정 항목 해소), 잔여석 COUNT 0.82 ms, 좌석맵 조회 10.70 ms / 408,705 bytes(3,200석), 데드락 0건
  - ✅ 검증: 백엔드 테스트 129개 통과, e2e 3개 통과, 깨끗한 DB에서 V1~V6 전체 체인 재검증(좌석 7,120 / 회차좌석 26,894 / 구역 12종)
  - **원문 정정**: "좌석 중복 선점이 `uk_schedule_seats_schedule_seat`를 때리면 409로 가린다"는 **해당 없다.** 선점은 UPDATE라 그 UNIQUE를 때릴 수 없고, `reservation_seats`의 UNIQUE는 `distinct()` 정규화가 애초에 막는다. 영원히 안 타는 `catch`를 넣지 않았다. 자동 생성 CHECK 이름에 의존하지 않는다는 원칙은 유효하며, `status`를 enum으로 넘겨 위반 자체가 불가능하게 했다
  - 알려진 한계: **`pickAvailableSeatIds`가 한시적 코드다** — Task 023에서 제거해야 하고, 남으면 "사용자가 고른 좌석"과 "서버가 고른 좌석" 두 경로가 공존한다. 선점 직전 좌석 400 KB를 한 번 더 조회하는 왕복도 그때 없어진다. **HOT 측정 표본이 6건**이라 경향만 본 것이고 Task 027에서 다시 재야 한다. **좌석맵 조회가 10.7 ms / 400 KB**로 잔여석 COUNT보다 13배 비싸다 — 원인은 `seats` 전체 Seq Scan과 정렬이라 V7에서 볼 것은 `idx(schedule_id, status)`가 아니다. `schedule_seats.reservation_id` FK의 `FOR KEY SHARE` multixact 오버헤드는 이번 규모에서 드러나지 않았다. `SeatBackfillMigrationTest`를 삭제했다 — V5가 `total_quantity`를 읽어서 V6 이후 재실행이 불가능하다. 지속 가치가 있는 불변식 5개는 `ReservationApiIntegrationTest.좌석_불변식`으로 옮겼다. 열당 좌석 수(20/40)와 "VIP가 무대에 가깝다"는 여전히 PRD·ERD 근거가 없는 자체 판단이다
- **Task 023: [FE] 좌석 배치도 화면** ✅ - 완료
  - ✅ **별도 라우트 `/performances/[id]/schedules/[scheduleId]/seats`**(PRD 부록 A에 계획돼 있던 경로). 상세의 `md:w-80` 사이드 패널에는 배치도가 안 들어가고, 무엇보다 `apiFetch`가 서버 전용(`API_BASE_URL`에 `NEXT_PUBLIC_` 없음)이라 **Server Component가 좌석을 받는 구조여야 409 재조회를 위한 클라이언트 경로를 새로 만들지 않아도 된다**. 등급·매수는 `?gradeId=&quantity=`로 넘겨 새로고침·뒤로가기·로그인 복귀에도 살아남는다
  - ✅ **매수를 먼저 고르고 배치도에서 그만큼 선택**하는 흐름. BE가 `seatIds.size() != quantity`를 400으로 끊으므로 둘 중 하나를 진실로 정해야 했고, 기존 `TicketSelector`의 매수 UI를 살리는 쪽이 변경 범위가 작다
  - ✅ 404와 리다이렉트를 갈랐다 — 회차가 그 공연 것이 아니면 `notFound()`(없는 리소스), 등급·매수가 어긋나면 상세로 `redirect()`(회차는 존재하고 다시 고르면 되는 상황). 404로 뭉개면 "공연이 사라졌나" 싶게 된다
  - ✅ **SVG `<rect>`로 렌더.** `posX`에 구역 사이 통로만큼 값이 비어 있어서(V5 백필) 좌표를 그대로 그리면 통로가 공짜로 생긴다. CSS grid는 좌석마다 `grid-column`을 지정해야 하고 노드도 더 무겁다. 등급 색은 Task 003에서 미리 심어둔 `--grade-vip/r/s/a` 토큰을 Tailwind v4 `@theme` 매핑으로 `fill-grade-*`로 쓴다
  - ✅ **등급이 바뀌는 줄 앞에만 가로 통로를 넣었다**(`rowY` 맵). `posY`가 등급 구분 없이 1씩 이어져서 단순 곱셈으로 y를 계산하면 구역 이름이 윗 등급 마지막 줄 좌석과 겹친다. 실제 공연장도 등급 사이에 통로가 있어 모양까지 자연스러워졌다
  - ✅ **고를 수 없는 좌석 레이어를 `memo`로 끊었다.** React Compiler가 꺼져 있어(`next.config.ts` 비어 있음) 자동 메모이제이션이 없고, 안 끊으면 좌석 하나 누를 때마다 3,000개 rect를 다시 만든다. 실측 **클릭 → DOM 반영 5~8ms**(3,000석)
  - ✅ **roving tabindex 추가**(계획에 없던 항목). 좌석마다 `tabIndex={0}`을 주면 키보드 사용자가 요약 패널까지 가는 데 150번(A석이면 1,200번) Tab을 눌러야 한다. 탭 스톱은 하나만 두고 ←→로 좌석 간, ↑↓로 같은 `posX`에 가장 가까운 윗/아랫줄로 옮긴다. `focusRing`(`ring-2`)은 box-shadow라 SVG에 안 먹어서 `outline`을 직접 지정했다
  - ✅ 최대 4석(BE `@Size(max = 4)`와 같은 값). **매수가 찬 뒤 다른 좌석을 누르면 무시하고 안내**한다 — 먼저 고른 좌석을 밀어내면 누른 적 없는 자리가 조용히 풀려서 예매에서는 위험하다
  - ✅ 확대 3단계(전체/보통/확대)와 `overflow-auto` 스크롤. 제스처 라이브러리는 도입하지 않았다. 360px에서 **가로 넘침 0px**
  - ✅ **409 재조회**: `SOLD_OUT`/`NOT_FOUND`는 둘 다 "화면의 좌석 정보가 낡았다"는 같은 상황이라 함께 묶어 `revalidatePath(좌석 경로)` + 선택 비우기로 처리한다. 선택 초기화 신호는 `resetSeatsAt: number`(타임스탬프) — boolean이면 같은 실패가 연속될 때 값이 안 바뀌어 두 번째를 감지할 수 없다. 초기화는 `useEffect`가 아니라 **렌더 중 상태 조정**으로 한다(effect로 하면 낡은 선택이 한 프레임 보이고, `react-hooks/set-state-in-effect` lint에도 걸린다). **`router.refresh()`는 필요 없었다** — `revalidatePath`만으로 배치도가 다시 내려오는 것을 2개 브라우저 컨텍스트로 실측 확인
  - ✅ **`pickAvailableSeatIds` 제거 — Task 022의 필수 후속 해소.** 선점 직전 좌석 400 KB 재조회 왕복도 함께 사라졌다
  - ✅ **범위 추가: `GET /reservations/{id}` 상세 응답에 좌석 목록.** 자리를 골랐는데 결제·예매상세에 안 보이는 불일치를 막는다. 조회는 `reservation_seats` → `schedule_seats` → `seats` 3홉 조인 — `schedule_seats.reservation_id`로 바로 가는 1홉 경로는 HOT 보존을 위해 인덱스를 안 걸었고(Task 021), **취소하면 null이 되어 "내가 어느 자리였는지"가 사라진다**. 목록 응답은 건드리지 않았다(N건마다 좌석 조인 → N+1)
  - ✅ `OrderSummary` 한 곳만 고쳐 결제·완료·예매상세 **세 화면**이 함께 해결됐다. 좌석 DTO에 `price`를 넣지 않은 이유: 한 예약=한 등급이라 `unitPrice`와 같은 값이 반복되고 "좌석별 차등 가격이 있다"는 오해를 준다
  - ✅ 하단 고정 CTA가 세 번째 복붙이 될 자리라 `components/layout/sticky-cta.tsx`로 추출, `getSaleState`도 `lib/schedule-rules.ts`로 분리(좌석 페이지와 `TicketSelector`가 공유)
  - ✅ 검증: BE 테스트 123개, e2e 3개(좌석 선택 step 추가), 360px·다크모드·키보드 이동, 프로덕션 빌드. **스크린샷 8장으로 갱신**(배치도 데스크톱·모바일 추가)
  - ✅ `v0.3.0-seatmap` 태그
  - 알려진 한계: **Server Component가 좌석 전체(3,000석 약 400 KB)를 한 번에 내려준다** — Task 027/030에서 측정해 구역 단위 분할이나 BE 필터 파라미터를 검토한다. 배치도 **확대는 버튼 3단계**이고 핀치 줌은 없다. `findInAdjacentRow`는 선택 가능한 좌석만 훑으므로 **다른 등급 줄을 건너뛴다**(의도된 동작이지만 시각적 위치와 어긋날 수 있다). 좌석 `aria-label`에 상태(선점/판매완료)가 없다 — 고를 수 없는 좌석은 접근성 트리에서 아예 빼는 쪽을 택했다. **열당 좌석 수(20/40)와 "VIP가 무대에 가깝다"는 여전히 PRD·ERD 근거가 없는 자체 판단**이고, 배치도를 그려 본 결과 모양은 자연스러웠다

### Phase 7: 운영 기반 (로컬)

Phase 7~9는 전부 로컬 Docker로 재현·측정한다. 클라우드 VM이 필요한 배포·CD는 Task 034로 분리했고, 그 Task는 보류 상태다(문서 말미 "보류 Task" 참조).

- **Task 026_1: [BE] Spring Boot 4 업그레이드** ✅ - 완료
  - ✅ Spring Boot 3.4.5 → **4.1.1** (Framework 7.0.8 / Hibernate 7.4.5 / Jackson 3.1.5). 3.4 라인의 OSS 보안 패치가 2025-12-31에 끊겼고 3.5도 2026-06-30에 끝나서, 패치를 받는 라인은 4.0/4.1뿐이다. Java 21 유지(Boot 4는 17+ 요구)
  - ✅ **Phase 7 맨 앞에 둔 이유**: 뒤따르는 Task 024(Dockerfile base image)·025(Actuator 엔드포인트와 Micrometer 지표명)·026(세션 설정)이 전부 Boot 버전에 묶여 있다. 3.4에서 먼저 만들면 같은 자리를 두 번 손대고 측정치도 다시 뽑아야 한다
  - ✅ **Hibernate 7 관문 통과** — 조건부 UPDATE 7개의 영향 행 수 반환이 Boot 3.4와 동일하다. `@Modifying(flushAutomatically = true, clearAutomatically = true)`의 flush 시점도 유지된다. 깨지지 않은 이유는 **조건부 UPDATE를 전부 원시 SQL/JPQL로 직접 쓰고 ORM의 상태 관리를 안 믿도록 설계**했기 때문이다 — Task 019가 엔티티에서 상태 변경 메서드를 삭제한 결정이 Hibernate 변경에 대한 노출 면적을 줄여놨다. Hibernate 7이 실제로 바꾼 것(native 쿼리 날짜 타입 `java.sql`→`java.time`, `@Immutable` 벌크 UPDATE 예외)은 우리가 쓰지 않는 기능이다
  - ✅ **파괴적 변경 3건이 컴파일을 통과한다** — 이 Task의 핵심 발견. (1) springdoc 3.1.1이 swagger-core 2.2.55를 통해 **Jackson 2를 클래스패스에 남겨서** 구 `ObjectMapper` import가 컴파일된다(기동 시점에 "빈이 없다"로 터진다). (2) `testcontainers-postgresql-2.0.5.jar`가 `org/testcontainers/containers/PostgreSQLContainer.class`를 **호환용으로 남겨둔다**. (3) `asText()`는 `asString()`에 위임하는 final 메서드로 남아 deprecated 경고만 낸다. 그래서 **`-Xlint:deprecation`을 명시적으로 켜는 게 실질적 안전망**이었다(init 스크립트는 `allprojects {}`로 감싸야 한다 — `tasks.withType`만 쓰면 빌드가 실패하는데 그걸 "경고 없음"으로 오독할 수 있다)
  - ✅ **함정: `spring-boot-starter-flyway`는 DB 모듈을 안 가져온다.** 기동이 `FlywayException: Unsupported Database: PostgreSQL 15.18`로 깨졌는데 **실제 원인은 의존성 누락**이고 메시지가 그걸 가린다. 스타터는 `flyway-core`까지만 가져오므로 `flyway-database-postgresql`을 따로 명시해야 한다
  - ✅ **Jackson 3가 필드 순서를 바꾼다** — Jackson 2는 선언 순서, Jackson 3는 creator 파라미터 먼저 + 나머지 알파벳순. `ApiResponse`는 `{data, success}`로, `PageResponse`는 생성자 파라미터명이 `page`라서 `page`가 맨 앞으로 갔다. **응답 DTO 17개는 전부 record라 안 바뀌었고** 순서를 라이브러리 기본값에 맡기던 클래스가 딱 2개(공통 래퍼)였다. 전역으로 `SORT_PROPERTIES_ALPHABETICALLY`를 끄는 대신 **두 클래스에 `@JsonPropertyOrder`를 명시**했다 — 전역 설정은 "선언 순서에 암묵적으로 의존"하는 상태로 되돌아가는 것이고 그게 방금 우리를 문 그 의존이다
  - ✅ 빌드 전환: Gradle 8.13 → **8.14.4**(Boot 4 플러그인 최소 요구. 최신 9.8.1 대신 8.x 마지막을 골라 "Gradle 9인가 Boot 4인가"를 분리할 필요를 없앴다), `starter-web` → **`starter-webmvc`**, 테스트는 **`starter-webmvc-test` + `starter-data-jpa-test`**(기술별로 쪼개짐), Testcontainers 좌표 **`testcontainers-postgresql`**(2.0 전면 개명), Redisson **4.8.0**(3.50.0은 netty 4.1, Boot 4는 4.2 관리), MyBatis starter **4.1.0**, springdoc **3.1.1**, `spring-security-test` 제거(실사용 0건), AssertJ·Mockito 버전 고정 해제
  - ✅ 패키지 이동: `@AutoConfigureMockMvc`가 `boot.test.autoconfigure.web.servlet` → **`boot.webmvc.test.autoconfigure`**(5파일, **유일하게 컴파일러가 잡아준 변경**), `findValuesAsText()` → `findValuesAsString()`, `asText()` → `asString()`
  - ✅ **Testcontainers 변경이 1파일에 갇혔다** — 테스트 11개가 `AbstractContainerTest`를 import하고 Testcontainers를 직접 import하는 건 그 베이스 클래스뿐이다. Task 002의 "공통 베이스 테스트 클래스" 결정이 라이브러리 메이저 업그레이드 비용을 1파일로 묶어줬다
  - ✅ **조사에서 해당 없음으로 판정된 Boot 4 파괴적 변경**: `@MockBean`/`@SpyBean` 제거(사용 0건), `@SpringBootTest`의 MockMvc 자동 구성 중단(5곳 전부 이미 `@AutoConfigureMockMvc` 명시), `@EntityScan` 패키지 이동·`spring.jackson.*` 키 이동(미사용), `starter-aop`→`-aspectj`(Task 020에서 이미 제거). **스프링 테스트 지원 기능을 적게 쓴 쪽이 업그레이드 비용으로 돌아왔다**
  - ✅ 로드맵이 Task 020 시점에 적은 영향 범위는 과소 추정이었다 — Task 022가 코드를 늘려서 `nativeQuery`는 2파일이 아니라 **3파일 5개**, Jackson 직접 사용은 6파일이 아니라 **7파일**이다
  - ✅ 검증: 테스트 **123개 통과**(실패 0 / 에러 0 / 건너뜀 0), e2e **3개 통과**(FE 무수정), 동시성 수치 전부 유지(초과 판매 201 10건/409 90건, 중복 선점 1/19, 겹치는 좌석 집합 데드락 0·부분 선점 0, 좌석당 1명, 결제-만료 경쟁+보상 환불), **좌석맵 응답 408,705 bytes 바이트 단위 동일**, 좌석맵 쿼리 플랜 동일, `schedule_seats` HOT **100%** 유지, 기동 4.42초
  - ✅ 새 경고: `SpringDoc /swagger-ui.html endpoint is enabled by default`(springdoc 3.x 신규). 운영 비활성화는 **Task 024에서 처리**
  - ✅ `docs/improvements/004_1-spring-boot-4-migration.md` 작성. `005`~`010`에 이름이 예약된 Task들을 밀지 않으려고 서브번호 관행(`V3_1`, `V4_1`, `Task 026_1`)을 따랐다
  - 알려진 한계: **Jackson 2와 3이 한 JVM에 공존한다**(swagger-core가 Jackson 2를 쓰는 동안 그대로. 현재 증상은 없다). **HOT 측정 표본이 3건**이라 경향만 본 것이고 Task 027에서 다시 재야 한다. 좌석맵 DB 쿼리 3.15~3.55 ms는 **004 문서의 플랜 설명을 보고 재구성한 동등 쿼리** 측정이라 before 10.70 ms와 직접 비교할 수 없다 — 유효한 결론은 "플랜 모양이 같다"까지다. **한 번에 올렸으므로 "무엇이 무엇을 깨뜨렸나"를 diff로 가를 수 없다**(3.5.x 경유를 안 골랐다). Gradle은 8.14.4에 머물러 있고 9.x 전환은 별도 건이다
  - 번호를 `027`이 아니라 `026_1`로 붙인 이유: Task 027~033이 ERD 마이그레이션 이력과 기술 스택 표에서 참조되고 있어 번호를 밀면 여러 곳을 같이 고쳐야 한다 (마이그레이션에서 `V4` 대신 `V3_1`을 쓴 것과 같은 이유)
- **Task 024: [공통] 컨테이너화 및 로컬 운영 스택** ✅ - 완료
  - ✅ BE layered-jar Dockerfile(`temurin:21-jdk` 빌드 → `21-jre-alpine` 런타임), FE `output: 'standalone'` Dockerfile(`node:22-bookworm-slim` 3스테이지). 이미지 **BE 137 MB / FE 89 MB**(`docker save` 기준), 둘 다 비루트 실행
  - ✅ **Boot 4는 `jarmode=layertools`가 아니라 `jarmode=tools extract`다**(3.3에서 교체). 더 중요한 건 `--launcher`를 주지 않으면 `Main-Class`가 `Start-Class`로 치환된 thin jar가 만들어져 **`JarLauncher`를 거치지 않고 `java -jar`로 바로 뜬다**는 것 — 인터넷에 흔한 `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]`는 이 구성에서 틀린 명령이다. 공식 4.1.1 문서와 v4.1.1 태그의 `ExtractCommand.java`로 확인
  - ✅ 레이어 분리 실측: `dependencies` **77.6 MB**(거의 안 변함) / `application` **184 kB**(매 빌드). 코드만 고치면 78MB가 아니라 184kB만 새 레이어가 된다. 빌드 콜드 70초 → 소스 변경 시 **21초**
  - ✅ `docker-compose.prod.yml`(루트, 서비스 4개) + 통합 `.env.example`. **dev 스택과 동시에 띄울 수 있다** — 컨테이너명 `-prod` 접미사, DB/Redis 호스트 포트 미공개, 볼륨 `pgdata-prod` 분리(시드 버전이 migration 사이에 끼어 있어 볼륨 혼용은 Flyway 기동을 깬다)
  - ✅ **완료 기준 달성** — prod 이미지로 띄운 스택에서 e2e **3개 통과**(5.5초), `compose up` → 4개 전부 healthy **24초**, BE 컨테이너 기동 **4.414초**(004_1의 로컬 4.42초와 동일 수준), 테스트 123개 통과
  - ✅ **ROADMAP이 예고한 함정 3개가 전부 실재했고, 공통점은 "전부터 경고로만 있던 것"이었다.** (1) **Flyway 시드** — prod 프로필은 `db/seed`를 안 읽어서 `${FLYWAY_LOCATIONS}` 토글을 만들었다(기본값은 운영 기준). **양방향으로 측정**했다: 기본값은 마이그레이션 8개·데이터 0건, 켜면 11개·ON_SALE 3건. (2) **CORS origin** — `WebConfig`의 하드코딩을 `@Value("...") String[]`로 외부화(스프링이 콤마를 자동 split해서 다중 origin이 공짜로 따라온다). (3) **정적 파일 복사 누락** — `public/`이 아예 없어서 공식 Next 템플릿의 `COPY /app/public`은 빌드가 깨지고, 파비콘·Pretendard가 둘 다 `.next/static/media`에만 있어 그 한 줄을 빼면 **페이지는 200인데 CSS·폰트만 404**가 된다. **e2e 3개는 이 상태에서도 통과하므로**(DOM만 보니까) 자산 응답 코드를 따로 쟀다
  - ✅ **springdoc을 끄니 그 자리가 404가 아니라 500을 뱉었다** — `GlobalExceptionHandler`의 `@ExceptionHandler(Exception.class)`가 `NoResourceFoundException`("404를 내라"는 프레임워크 신호)을 먹어버린 것. **그동안 안 보인 이유는 `anyRequest().authenticated()`가 모르는 경로를 먼저 401로 끊어서**, 이 구멍에 빠지는 건 "permitAll인데 핸들러가 없는 경로"뿐이고 springdoc을 끄기 전까지 그런 경로가 없었다. 보안 설정이 예외 처리의 결함을 덮고 있었다. 핸들러 5줄 추가로 404로 교정(계획 밖 변경, `ErrorCode.NOT_FOUND` 재사용). ROADMAP:268이 이 Task로 넘긴 항목 해소
  - ✅ **루트의 빈 `package-lock.json`(의존성 0개, 초기 커밋 이후 불변)이 standalone 레이아웃을 바꾸고 있었다** — Next가 그걸 보고 모노레포 루트를 워크스페이스 루트로 추정해 산출물이 `.next/standalone/tikkit-front/` 아래로 중첩된다. Dockerfile을 그대로 썼으면 `CMD ["node", "server.js"]`가 파일을 못 찾는다. `outputFileTracingRoot: __dirname`으로 고정(파일을 지우는 대신 레이아웃이 밖의 파일에 종속되는 구조를 끊는 쪽)
  - ✅ **devtools가 기동 실패를 가리고 있었다** — prod datasource에 기본값을 안 줬는데 `bootRun`은 컨텍스트가 깨져도 `BUILD SUCCESSFUL`(exit 0)로 끝난다(`developmentOnly`라 bootJar엔 없음). 컨테이너에서는 정직하게 **exit 1**. 로컬 결과만 믿었으면 "기본값을 안 주는 설계"가 무력하다고 잘못 판단했을 것이다
  - ✅ **actuator가 없어서(Task 025 범위) 헬스체크를 도메인 엔드포인트로 대체했다** — CI e2e 잡이 쓰는 `GET /api/v1/performances?size=1`(permitAll, 200이면 Hikari+Flyway까지 끝난 것). `curl`이 jre-alpine에 없어 busybox `wget`을 쓰고, `CMD-SHELL`이면 `?size=1`이 셸 글로빙 대상이라 **exec 배열 형식**으로 줬다. **빈 DB에서도 healthy**인 것을 확인 — 목록 API가 빈 배열로 200을 주기 때문이고, 상세·통계 엔드포인트를 골랐으면 시드 없는 운영 DB에서 영원히 unhealthy였다
  - ✅ `playwright.config.ts`에 조건부 `webServer` — `E2E_BASE_URL`이 있으면 `undefined`로 끈다. `reuseExistingServer`로는 안 되는데 CI에서 그 값이 false가 되어 포트 충돌하고 로컬에서도 Playwright가 남의 서버를 죽이려 든다. **기존 두 경로는 무변경**(로컬·CI 둘 다 그 변수를 안 쓴다) → `ci.yml`의 e2e 잡 수정 없음, 회귀 3개 통과
  - ✅ `ci.yml`에 `docker-build` 잡 **추가만**(34줄, 삭제 0줄). 매트릭스로 BE/FE, `push: false`·`load: false`, `cache-from/to: type=gha,scope=${{ matrix.name }}`(scope를 안 나누면 두 이미지가 같은 키를 덮어써 매번 캐시 미스가 된다), `needs` 없이 기존 3개 잡과 병렬
  - ✅ `.dockerignore` 2개 — 컨텍스트가 `tikkit-back` 82 MB → **642 KB**, `tikkit-front` 1.7 GB → **1.1 MB**. 효과는 이미지 크기가 아니라 빌드 시작 비용과 캐시 수명이다(`node_modules`를 안 빼면 `npm install` 한 번에 모든 레이어 캐시가 무효화된다)
  - ✅ `docs/improvements/004_2-containerization.md` 작성. `005`~`010`이 뒤 Task에 이름까지 예약돼 있어 `004_1`과 같은 서브번호 관행을 따랐다
  - 알려진 한계: **푸시·배포는 안 했다**(Task 034). **CI 캐시는 검증되지 않았다** — `type=gha` 적중과 scope 분리는 PR을 두 번 푸시해봐야 안다. **시드를 켠 이미지로 검증했다**(시드 없는 운영 경로는 기동·헬스체크까지만). **시드의 판매중 회차가 적용 시점 기준 상대 날짜**라 볼륨을 만든 지 27일이 지나면 ON_SALE이 0개가 되어 e2e가 깨진다 — 근본 수정 대신 "e2e 전에 `down -v`"를 못 박았다. **세션 쿠키 `secure`는 false**(평문 HTTP라 켜면 e2e가 전부 깨진다, Task 034). **BE는 1개뿐**이고 2개로 늘리면 `@EnableScheduling` 배치가 두 번 돈다(Task 026에서 ShedLock 류 필요). **헬스체크가 도메인 엔드포인트**라 그 경로·권한이 바뀌면 조용히 unhealthy가 되고 5초 주기 트래픽이 Task 025 지표에 섞인다. **이미지 크기 수치가 도구마다 다르다** — `docker save`/`inspect`는 137MB/89MB인데 `docker images`는 434MB/381MB이고 `--provenance=false --sbom=false`로도 안 바뀐다(원인 미해결, 문서 수치는 `docker save` 기준). **루트 `package-lock.json`은 남아 있다**(영향만 끊었다). `next start`가 `output: standalone`과 함께 "동작하지 않는다"는 경고를 내지만 실측하니 자산까지 정상 서브되고 로컬·CI e2e 둘 다 통과해서 그대로 뒀다
- **Task 025: [BE] 모니터링 구축** ✅ - 완료
  - ✅ `spring-boot-starter-actuator` + `micrometer-registry-prometheus`(runtimeOnly) **두 줄로 끝났다** — starter-actuator가 Boot 4 신설 `spring-boot-starter-micrometer-metrics`까지 끌고 온다. BOM에 아직 남아 있는 `-simpleclient` 변종을 고르면 자동설정이 신 패키지(`io.micrometer.prometheusmetrics.*`)를 못 찾아 엔드포인트가 아예 안 뜬다
  - ✅ **004_1이 넘긴 "Boot 4에서 바뀐 것" 확인 완료** — 지표명(Hikari 16종·JVM)은 Boot 3과 같았다. 바뀐 건 프로퍼티: `management.endpoint.<id>.enabled` **제거** → `.access`(none/read-only/unrestricted), `management.health.probes.enabled` → `management.endpoint.health.probes.enabled`. 덤으로 **probes가 기본 활성**이었다(K8s 감지 조건부가 아니다 — 설정 없이 `livenessState`·`readinessState`가 응답에 들어 있다). "확인했다"로 끝내지 않고 통합 테스트가 지표명을 단정하게 만들었다
  - ✅ 노출은 `health`·`prometheus` 둘뿐. `management.endpoints.access.default: none`이라 나머지는 **컨텍스트에서 아예 제거**되고(나중에 `include: "*"`로 바꿔도 env·heapdump가 안 열린다), `SecurityConfig`가 그 둘만 permitAll. `/actuator/**` 와일드카드는 쓰지 않았다. 노출 안 된 `/actuator/env`가 **401**인 것을 테스트로 고정
  - ✅ **p95가 값이 아니라 버킷 경계를 읽고 있었다** — 카디널리티를 줄이려 하한을 5ms로 잡았더니 목록 p95 **4.756ms**, 좌석맵(응답 59KB) p95 **4.878ms**로 거의 같았다. 로컬 응답이 대부분 5ms 미만이라 전부 최하위 버킷에 들어갔고 `histogram_quantile`이 `[0, 5ms]`를 선형 보간한 결과였다. **그래프도 숫자도 단위도 멀쩡해 보이는 상태로 틀린다.** 1ms로 내리니 목록 p50 **0.63ms**/p95 **2.11ms**, 좌석맵 p95 **3.07ms**/p99 5.48ms로 갈렸다
  - ✅ `percentiles`(인스턴스가 미리 계산한 `_quantile`)가 아니라 `percentiles-histogram`을 썼다 — **분위수는 수학적으로 합산이 불가능해서** BE가 2대가 되는 Task 026에서 못 쓴다. 대가는 카디널리티: URI 3종 기준 버킷 OFF 179 시리즈/33KB → ON(1ms~10s) **439 시리즈/72KB**(+145%/+117%). prod에서 URI 13종이 되자 **1,182 시리즈/190KB**. **URI 1종당 54 시리즈**라 경로변수를 템플릿으로 안 묶는 엔드포인트를 추가하면 여기서 터진다
  - ✅ **헬스체크를 `/actuator/health`로 이전**(004_2:386이 넘긴 항목). `DataSourceHealthIndicator`를 포함해서 UP이면 Hikari+Flyway까지 끝났다는 뜻 — 기존 보증과 같거나 더 세다. `/actuator/health/readiness`는 **쓰지 않았다**: 기본 readiness 그룹은 `readinessState` 하나뿐이라 DB를 확인하지 않아 보증이 오히려 약해진다(probes가 필요해지는 건 오케스트레이터가 들어오는 Task 034)
  - ✅ **헬스 트래픽이 지표에 섞인다는 004_2의 지적이 사실이었다** — `http_server_requests_seconds_count{uri="/actuator/health"}`가 실제로 올라간다(같은 `ServerHttpObservationFilter`를 통과). `interval: 5s → 30s`로 원천에서 1/6로 줄이고 PromQL에서 `uri!~"/actuator.*"`로 한 번 더 걸렀다. `MeterFilter`로 아예 버리는 방법은 actuator 자체 지연을 영영 못 보게 되므로 기각
  - ✅ **`start_interval: 2s`가 "기동 감지"와 "평상시 주기"를 분리했다** — 기존엔 `interval: 5s` 하나가 둘을 겸하고 있었다. 평상시 트래픽을 6배 줄이면서 `compose up` → 4개 healthy는 **24초 → 20.6초로 오히려 빨라졌다**(앱이 빨라진 게 아니다)
  - ✅ 커스텀 지표 `tikkit.reservation.attempts` — 태그 `result`(success/failure) + `reason`(`ErrorCode` 이름). **카운터를 둘로 쪼개지 않은 이유**: 쪼개면 `by (result)`로 묶거나 실패율을 한 쿼리로 못 낸다. **성공에도 `reason="none"`을 붙인 이유**: Micrometer가 같은 이름에 태그 **키 집합**이 다른 미터를 거부한다(테스트로 고정). reason이 enum이라 카디널리티가 유계다
  - ✅ **한 번도 증가하지 않은 Counter는 "0"이 아니라 시리즈가 아예 없다** — Grafana가 "No data"를 띄우고, 그러면 **실패 0건과 계측 누락을 구분할 수 없다.** 오픈런 중에 실패 패널이 비었을 때 안심할지 코드를 의심할지 모르는 상태가 된다. `@PostConstruct`로 7개 시리즈를 0으로 미리 등록
  - ✅ `@Counted` AOP 기각 — 예외 클래스명만 태그로 붙이는데 이 흐름의 실패는 전부 `BusinessException` 한 종류라 **`SOLD_OUT`과 `DUPLICATE_PENDING_RESERVATION`을 구분할 수 없다.** 그 구분이 이 패널을 만드는 이유 전부다. Boot 4에선 aspectjweaver 의존성도 추가로 필요하고, Task 020이 일부러 제거한 `starter-aop`를 되살리는 결정이 된다
  - ✅ compose에 Prometheus(`v3.15.0`)·Grafana(`13.2.3`)를 **`profiles: ["monitoring"]`** 으로 추가. 별도 compose 파일(`-f` 2개)은 `down`에서 `-f`를 빼먹으면 orphan이 남는데, `compose down`은 프로필과 무관하게 프로젝트 전체를 지운다. 두 이미지 모두 `/bin/wget`이 있어 Task 024의 "전부 healthcheck" 관행을 유지했다. **Grafana는 호스트 3001**(기본 3000은 FE가 쓴다), **datasource uid를 `prometheus`로 고정**(자동 생성 uid면 대시보드 JSON 참조가 끊겨 전 패널 "No data"), `depends_on`은 안 걸었다(스크레이프가 재시도하므로 BE 재기동에 묶이면 안 된다)
  - ✅ 대시보드 9패널을 JSON으로 커밋(`allowUiUpdates: false` — 파일이 진실, UI 편집본은 재시작 시 덮임). **패널을 단위별로 쪼갰다** — 바이트(힙)·개수(스레드)·비율(CPU)을 한 패널에 섞으면 y축이 둘 이상 필요해지고 둘 다 못 읽게 된다. 성공/실패는 색을 고정해 시리즈 수가 바뀌어도 초록·빨강이 안 뒤바뀌게 했다
  - ✅ **구조화 로깅은 안 켜기로 했다**(`application-prod.yml:41` 주석이 넘긴 항목). Boot 내장 `logging.structured.format.console`로 의존성 없이 켤 수 있는 건 맞지만 **그 JSON을 소비하는 수집기가 하나도 없고** `compose logs`를 사람이 직접 읽는 구조라 가독성만 떨어진다. `.env` 토글(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`)로 열어두고 `spring.application.name: tikkit-api`만 추가. 파일 출력은 Docker json-file과 이중 저장 + 비루트(uid 100)라 볼륨이 필요해서 제외(Task 034). `micrometer-tracing` traceId도 **소비자가 없고 인스턴스가 1대**라 기각
  - ✅ **완료 기준 달성** — 6개 서비스 전부 healthy, Prometheus 타깃 up, 9패널 전부 값 있음, 테스트 **129개**(123 + actuator 5 + 카운터 1), prod 이미지 e2e **3개 통과(5.2초)**. 예매 실패 4종(`DUPLICATE_PENDING_RESERVATION`/`SOLD_OUT`/`VALIDATION_ERROR`/`NOT_FOUND`)을 일부러 유발해 reason 분해가 실제로 갈리는 것을 스크린샷으로 남겼다
  - ✅ **측정 불가 → 측정 가능**(부하 98초, 병렬 20, 81.9 RPS): 좌석맵 p95 3.07ms, heap 최대 142.8MB, GC 평균 3~5ms, CPU 최대 3.2%. **가장 쓸모 있는 결과는 Hikari** — `active` 최대 **1**, `pending` 최대 **0**, `idle` 최소 9로 **풀 10이 이 워크로드에는 과하다**는 게 처음 수치로 보였다. 이걸 모른 채 "오픈런이 걱정되니 풀을 늘리자"고 했으면 아무 효과 없는 튜닝을 할 뻔했다
  - ✅ 계측 비용: 앱 기동 4.414초 → **4.92~5.19초**(메트릭 바인더 등록, +0.5~0.8초), BE 이미지 137MB → **141MB**(+4MB), 메모리 439MB(신규 측정)
  - ✅ `docs/improvements/004_3-monitoring.md` 작성 + 대시보드 스크린샷 2장. `005`~`010`이 뒤 Task에 예약돼 있어 `004_1`·`004_2`와 같은 서브번호 관행
  - 알려진 한계: **`increase()`가 정수를 주지 않는다**(실패 4건이 `4.115`) — Prometheus가 구간 경계를 외삽하므로 "몇 건인가"가 아니라 "어느 사유가 지배적인가"로 읽어야 한다. **`scrape_interval: 10s`라 짧은 스파이크를 놓친다** — 18초 부하에서 Hikari `active` 최대가 0으로 나온 적이 있다(샘플 2개가 마침 유휴였다). Counter는 누적이라 괜찮지만 **Gauge는 스크레이프 순간의 스냅샷**이다. **p95는 버킷 경계 보간 추정값**이고 범위를 잘못 잡으면 값이 아니라 경계가 나온다(위 함정). **성공 카운트가 트랜잭션 안에서 증가**해 커밋 롤백 시 과대 집계되고, 컨트롤러 앞단에서 400/401로 끊긴 요청은 안 잡힌다. **`/actuator/prometheus`가 호스트에 열려 있다**(8080 퍼블리시) — 진짜 해법은 리버스 프록시가 `/actuator`를 프록시하지 않는 것으로 Task 026 nginx에서 닫는다. **부하 발생이 `curl`+`xargs`**라 램프업·think time·응답 검증이 없다(베이스라인은 Task 027). Grafana가 없는 `provisioning/plugins`·`alerting` 디렉토리를 error로 찍지만 무해해서 빈 디렉토리를 커밋하지 않았다. **범위 밖 발견**: 깨진 UTF-8이 섞인 JSON 본문이 `HttpMessageNotReadableException` → `@ExceptionHandler(Exception.class)`에 먹혀 **500**으로 떨어진다(400이 맞다). Task 024의 `NoResourceFoundException` 사건과 같은 부류라 손대지 않고 기록만 했다
- **Task 026: [BE] 다중 인스턴스 세션 불일치 재현 및 개선** ✅ - 완료
  - ✅ `docker-compose.prod.yml`의 `backend` 하나를 **`backend1`/`backend2` + nginx(80)** 로 쪼갰다. `container_name`을 빼고(고정명은 애초에 복제가 불가능해서 Task 024가 "BE는 1개뿐"이라는 한계를 남겼다) **`--scale`이 아니라 서비스 2개를 명시 선언**했다 — `--scale`은 같은 서비스명에 IP가 여러 개 붙어서 Prometheus `static_configs`의 `instance` 라벨이 갈리지 않고, 그러면 "어느 인스턴스가 세션을 들고 있나"를 대시보드에서 구분할 수 없다(그게 이 Task의 측정 전부다). 환경변수·헬스체크는 **YAML 앵커로 공유** — 한쪽만 다르면 세션이 아니라 설정 차이를 측정하게 된다
  - ✅ **재현**: 같은 `JSESSIONID`로 `/members/me` 40회 → 1차 200 20/401 20, 2차 200 26/401 14. 정확히 반씩이 아닌 이유는 frontend 헬스체크(10초 주기)가 같은 upstream의 라운드로빈 순번을 같이 소비해서다. 결정적 증거는 nginx 로그에 `$cookie_JSESSIONID`를 넣은 것 — **같은 `sess=4B66...` 값이 `upstream=172.21.0.6`에서 200, `172.21.0.5`에서 401로 갈린다**(상관관계 1:1). 부하 60초에서는 **성공률 46.3%**, backend2는 401만 1,574건 냈다. **실제 앱에서도 깨진다** — prod 스택 대상 e2e에서 로그인 직후 로그아웃 버튼이 안 보여 2개가 실패했다
  - ✅ **sticky session 2종을 먼저 재고 둘 다 기각했다**(코드 0줄, nginx.conf 주석만 변경). `ip_hash`는 불일치가 0이 되지만 **backend2가 받은 요청이 0건**이었다 — 처음 세운 가설("클라이언트가 Next 서버 하나뿐")은 **틀렸고**, 실제 원인은 nginx가 IPv4의 **앞 3옥텟만** 해시 키로 쓴다는 것이다(공식 문서). Docker `/24` 전체가 한 버킷이라 클라이언트 수와 무관하게 한 대로 쏠린다. 그 한 대를 `stop`하면 세션 전멸(10/10 401)이고 재시작해도 401이다. `hash $cookie_JSESSIONID`는 **라운드로빈보다 나빴다 — 401이 20/20**이다. **세션을 만드는 요청(로그인)에는 아직 그 쿠키가 없어서**(`sess=-`) 빈 값이 해시돼 backend1로 가고, 이후 요청은 발급된 쿠키로 해시돼 backend2로 간다. 순서 문제라서 설정으로 못 고친다
  - ✅ **Spring Session은 의존성만 넣으면 끌 수 없다** — Boot 4에는 저장소 선택 프로퍼티도(`spring.session.store-type`이 없다) 비활성화 스위치도 없고, "스타터를 넣는 것 자체가 선택"이라는 설계다. 실제 활성 조건이 `@ConditionalOnBean(RedisConnectionFactory)`인데 `DataRedisAutoConfiguration`이 프로퍼티 없이도 localhost 기본값으로 그 빈을 만든다. **그 자동설정을 항상 제외하고 `session-store=redis`일 때만 `SessionRedisConfig`가 팩토리를 직접 만드는** 구조로 풀었다(Redisson이 스타터를 피한 것과 같은 패턴). **함정**: 제외하니 전혀 다른 곳이 깨졌다 — `DataRedisRepositoriesAutoConfiguration`의 `redisReferenceResolver`가 `redisTemplate`을 **이름으로** 찾는데 그 빈을 만들던 게 방금 제외한 자동설정이다. 증상(`No bean named 'redisTemplate'`)이 세션과 아무 관계없어 보인다
  - ✅ **쿠키 이름을 통합 테스트로 고정할 수 없었다.** Spring Session 기본 쿠키명은 `SESSION`이고 FE가 `/JSESSIONID=([^;]+)/`로 파싱하므로 바뀌면 **로그인이 조용히 실패한다**(200은 그대로 내려오고 쿠키만 저장되지 않는다). `server.servlet.session.cookie.name`을 명시했는데 MockMvc에서는 여전히 `SESSION`이 나왔다 — Boot의 `SessionAutoConfiguration`이 쿠키 프로퍼티 매핑을 **`EmbeddedWebServerConfiguration`**(`@ConditionalOnNotWarDeployment`)에만 두고, `@SpringBootTest`의 MOCK 환경은 임베디드 서버가 없어 **WAR 분기를 타서** `MockServletContext`의 빈 설정이 쓰인다. 실제 컨테이너는 정상(`JSESSIONID=...; HttpOnly; SameSite=Lax`)이라 쿠키명 회귀는 e2e에 맡겼다
  - ✅ **004_2가 예고한 `proxy.ts` X-Forwarded-Host 문제는 실재하지 않았다.** Next가 리다이렉트 Location을 **상대 경로**(`/login?redirect=...`)로 내려서, 호스트를 무엇으로 추정했든 브라우저가 자기 origin 기준으로 해석한다. `Host` 헤더를 바꿔 보내도, 컨테이너에 직접 쳐도 상대 경로다. **넘겨받은 경고를 측정 없이 "고쳐두는" 커밋을 하지 않은 것이 수확이다**
  - ✅ **`$host`를 `$http_host`로 바꿨다.** `$host`는 포트를 버린다. Next Server Action이 `origin`과 `host`를 비교해 다르면 `Invalid Server Actions request.`로 **500**을 내고 로그인·로그아웃이 전부 Server Action이다. 지금은 80이라 두 값이 같지만 포트를 옮기는 순간 로그인이 깨지고 메시지로는 원인에 도달하기 어렵다
  - ✅ **"커넥션 재사용 때문에 재현이 약해진다"는 내 가정이 틀렸다** — nginx는 HTTP/1.1 프록시에서 **요청 단위로** 업스트림을 고르므로 Next의 fetch가 커넥션을 유지해도 라운드로빈은 요청마다 돈다. 실제 영향은 반대쪽이었다: `keepalive`가 없으면 nginx→BE가 매 요청 새 TCP라 **핸드셰이크 비용이 지연 측정에 섞인다**(전 전략에 동일하게 섞여 상대 비교는 유효하다)
  - ✅ **대시보드가 BE 2대에서 조용히 틀린 값을 낸다** — Task 025의 "멀쩡해 보이면서 틀린다"가 재발했다. 프로세스 CPU `sum()`은 **최대 2.0**(축은 max:1), Hikari `max` `sum()`은 **20**(풀은 10)이 됐다. `by (instance)`로 쪼개고 `max()`로 바꿨다. **HTTP p95와 예매 카운터는 정상** — 버킷·Counter 합산은 수학적으로 맞다. Task 025가 `percentiles` 대신 `percentiles-histogram`을 고르며 "Task 026에서 못 쓴다"고 적어둔 판단이 실제로 값을 했다. 신규 패널 `sum by (instance, status) (increase(...{uri="/api/v1/members/me"}))`가 이 Task의 측정 계기다
  - ✅ **배치 중복: 두 번 돌지만 데이터는 깨지지 않는다.** `10:33:06.662 backend2 좌석 수: 2` / `10:33:06.694 backend1 좌석 수: 0` — 32ms 차이로 양쪽이 돌고 **두 번째는 0건**이다. 만료 쿼리 둘이 모두 멱등(`WHERE status='PENDING' AND expires_at <= :now`, `IS DISTINCT FROM`)이기 때문이고, **Task 019가 조건부 UPDATE로 설계한 것이 의도 없이 받은 보험**이었다. 그래도 락을 넣은 이유는 (a) DB 왕복 2배, (b) `좌석 수: 0`을 보고 "만료가 안 됐다"로 오독, (c) **다음에 추가될 배치가 멱등일 보장이 없다**(알림 발송은 두 번 돌면 사고)
  - ✅ ShedLock **7.10.1**(README 호환 매트릭스에서 Boot 4.x를 테스트한 라인이 7.x뿐이다) + **JDBC provider**. Redis provider가 아닌 이유 둘: JWT를 채택하면 Redis가 사라지는데 그때 배치가 같이 깨진다(DB는 어느 전략에서도 있다), **JDBC만 `usingDbTime()`을 제공한다**(락 판정에 DB 서버 시계를 써서 "노드 시계 차이가 `lockAtLeastFor`보다 크면 중복 실행된다"는 알려진 함정을 원천 제거). **`lockAtLeastFor = 55s`가 핵심** — 작업이 수십 ms라 락을 즉시 놓으면 같은 주기 안에서 다른 인스턴스가 다시 집어 **중복 실행이 "해결된 척" 하다가 그대로 남는다**. after: backend1 2회 / backend2 **0회**, 페일오버도 확인(backend1 `stop` → backend2가 인수, `locked_by` `870e41bd9be3` → `2571e27c59cf`)
  - ✅ 마이그레이션 번호를 V7이 아니라 **`V6_1`** 로 붙였다 — V7(인덱스, Task 028)과 V8(`queue_enabled`, Task 031)이 ERD 이력 표와 ROADMAP에 예약돼 있어 번호를 밀면 두 문서를 같이 고쳐야 한다(`V3_1`·`V3_2`와 같은 관행)
  - ✅ **관문: 두 인스턴스가 같은 DB에 Flyway를 동시에 돌린다.** `flyway_schema_history`를 집계하니 12개 버전이 **각각 정확히 1회** 적용됐다. Flyway의 스키마 히스토리 잠금이 작동해서 `backend2.depends_on: backend1`로 직렬화할 필요가 없었다
  - ✅ **측정**(부하 60초/병렬 20, `/members/me`): memory **200 비율 46.3%** p95 1.40ms(200만 1.58ms) / redis **99.8%** p95 **3.76ms** / jwt **99.8%** p95 **1.72ms**. 자격증명 크기 32B / 48B / **179B**, 힙 최대 62.9MB / 67.1MB / **52.7MB**. 인스턴스 분포는 redis 27:13, jwt 25:15로 **둘 다 로드밸런싱이 유지된다**(ip_hash는 100:0이었다)
  - ✅ **Redis 세션 채택.** JWT가 p95 2.2배 빠르고 힙도 14MB 적고 인프라도 0인데 기각한 이유: (1) **로그아웃을 서버에서 강제할 수 없다** — 로그아웃 후 같은 토큰으로 200이 나온다(테스트로 고정). 폐기 목록을 두면 stateless가 아니게 되고 Redis를 쓰면서 JWT 단점만 남는다. (2) 세션은 슬라이딩 만료인데 JWT `exp`는 **절대 시각**이라 좌석을 고르는 중에 끊긴다. (3) +2.0ms는 좌석맵 p95가 3.07ms인 서비스에서 받아들일 수 있고 Hikari `active` 최대가 1이라 병목이 다른 데 있다. (4) **Redis는 Task 029·031에서 어차피 들어온다** — 003에서 남겨둔 compose의 `redis`를 드디어 쓴다. (5) 세션 API를 그대로 쓰므로 `MEMORY`·`REDIS`가 **같은 구현을 공유**한다
  - ✅ **정정: 처음 쓴 채택 근거가 과장이었다**(005 문서 8.1절). "권한 회수가 불가능하다"고 적었는데 리뷰에서 "그 테스트는 404가 나왔으니 차단된 거 아니냐"는 지적을 받고 코드를 다시 봤고, **지적이 맞았다.** 삭제 반영은 엔드포인트가 DB를 보는지에 달려 있다 — `/members/me`는 `findById().orElseThrow()`로 **404(막힌다)**, `GET /reservations`는 `WHERE member_id = ?`뿐이라 **200 + 빈 목록**이다. 더 큰 문제는 **`hasRole`·`@PreAuthorize` 검색 결과가 0건**이라는 것 — `MemberRole`은 있지만 인가에 쓰이지 않아 **회수할 권한을 아무도 검사하지 않는다.** 그 요구를 코드 확인 없이 "예매 서비스니까 당연히"로 가정했다. 또 실무 JWT는 짧은 access + refresh 조합인데 이번 구현은 **refresh 없는 30분 단일 토큰**(PRD가 범위 밖으로 명시)이라 **회수 축에서는 실무보다 약한 형태와 비교했다.** 다만 결론은 유지된다 — JWT를 제대로 하려면 refresh 저장소와 폐기 목록이 필요하고 **그 저장소가 결국 Redis**다. **측정하기 쉬운 축(지연)은 재고 판단의 중심축(회수 요구)은 가정으로 둔 것**이 교훈이다
  - ✅ `repository-type: indexed`를 고른 것도 로그아웃 강제 때문이다 — 기본값 `default`에는 세션 조회 색인이 없어 `findByPrincipalName()`을 쓸 수 없다. **대가는 기동이 Redis에 묶이는 것**(indexed는 기동 시점에 `CONFIG SET`으로 keyspace notification을 켠다). Redisson 스타터를 피한 이유와 같은 모양의 비용을 의도적으로 지불했다. 다만 위 정정을 반영하면 이 선택의 근거도 약해진다 — **이 색인을 쓰는 운영 코드는 0건**이고 통합 테스트 하나가 쓴다. 강제 로그아웃 API가 실제로 필요해질 때까지 `default`로 두고 기동 의존성을 피하는 선택도 정당하다(Task 034에서 재검토)
  - ✅ **정리 커밋 `43 insertions, 502 deletions`** — JWT 4파일 + 테스트 1파일 + 의존성·프로퍼티·compose env 삭제. 건드린 운영 코드는 `AuthStrategyConfig`의 case 두 줄과 `SecurityConfig`의 if 블록 하나뿐이었다. **전략 선택을 `@ConditionalOnProperty`로 흩지 않고 switch 한 곳에 모은 결정**이 Task 020의 교훈을 지켜줬고, `AuthController`는 JWT를 붙이고 떼는 동안 한 줄도 바뀌지 않았다(`LoginCredentialWriter`로 뽑아둔 덕이다)
  - ✅ **완료 기준 달성** — 테스트 **132개** 통과(실패·에러·건너뜀 0), redis 모드에서 e2e **3개 통과**, `/actuator`가 nginx 밖에서 **404**, Prometheus 타깃 2개 up, 배치가 한 인스턴스에서만 실행. 비용: BE 기동 4.92~5.19초 → **6.76~6.88초**(+1.8초, Spring Session·Lettuce·ShedLock 빈 등록), 이미지 141MB → **147MB**(+6MB), 컨테이너 4개 → 5개
  - ✅ `docs/improvements/005-session-scaling.md` 작성. `004_1`~`004_3`과 달리 **서브번호를 쓰지 않았다** — `005`는 원래 이 Task에 예약된 번호다
  - 알려진 한계: **`memory` 토글이 남아 있다**(실험 잔재가 아니라 "Redis 없이도 테스트가 돌아야 한다"는 003의 결정을 지키는 개발·테스트용이다). 운영에서 실수로 `memory`로 띄우면 불일치가 그대로 재현되고, compose 기본값을 `redis`로 바꾼 것 말고 기동 시 경고 같은 방어는 없다. **단일 Redis라 Redis가 죽으면 전 사용자가 로그아웃된다** — 장애 반경이 "한 인스턴스"에서 "전체"로 바뀌었고 이건 교환이지 개선이 아니다(복제·Sentinel 미검토). **각 전략을 60초 1회씩만 쟀다**(p95 차이의 방향은 분명하지만 절대값으로 읽으면 안 된다). **`keepalive` 없는 상태의 수치**라 핸드셰이크 비용이 섞여 있다. **1ms 미만은 측정 불가**(버킷 하한이 1ms라 `memory`의 p50 0.88ms는 값이 아니라 `[0, 1ms]` 보간이다 — Task 025 4.1절의 함정). **쿠키명을 통합 테스트로 고정하지 못했다**. **ShedLock의 `lockAtMostFor` 경로는 미검증**(정상 종료 후 `lock_until`이 55초 뒤라 그 경로로만 확인했다). **sticky 2종은 p95를 재지 않았다**(구조적 기각이라 지연 비교가 의미 없었다). **전략을 바꿀 때 nginx 재시작을 사람이 기억해야 한다**(빼먹으면 502가 나고 전략 문제로 오진한다). 쿠키 `secure`는 여전히 false(Task 034). **범위 밖 발견**: `tikkit-front/lib/session.ts`의 `SESSION_MAX_AGE_SECONDS`가 로그인 시점 1회 고정이라 BE의 슬라이딩 만료와 어긋난다 — "BE는 살아있는데 Next 쿠키가 먼저 죽는" 상황이 가능하다

### Phase 8: 성능 개선 ✅

- ✅ **Task 027: [공통] k6 부하 테스트 환경 및 베이스라인** (`feature/k6-load-baseline`)
  - ✅ **툴 비교 후 k6 확정**: JMeter는 `.jmx`가 XML이라 "think time 300ms → 500ms"가 PR diff에 안 보여 수치의 재현성이 깨지고, Locust는 Prometheus 연동이 서드파티다. 결정적 근거는 성능이 아니라 **기존 Prometheus/Grafana(Task 025)에 remote write로 바로 꽂힌다**는 것. Gatling은 Prometheus 연동이 Enterprise라 기각
  - ✅ **대량 데이터**: `load-test/sql/` 4종(Flyway 밖). 공연장 1곳 × 좌석 2,000 → 공연 1,000 → 회차 3,000 → `schedule_seats` **6,026,894행(994MB)**. 걱정했던 FK·인덱스 때문의 지연은 없었다 — **10만 행당 1.1~1.25초로 일정, 전체 70초**라 인덱스를 떼지 않고 운영과 같은 테이블 모양(`fillfactor 90`)을 유지했다
  - ✅ **베이스라인**(keepalive ON, 3회 중앙값): 탐색 p95 **2.36ms**(35.5 req/s) / 좌석맵 **6.72ms**(응답 237.9KB·2,000석) / 예매 **7.43ms**(**82.2 req/s**). 3회 산포가 ±0.2ms 이내라 005의 "1회씩만 쟀다" 한계가 닫혔다
  - ✅ **keepalive 분리**(005 4.6절이 남긴 숙제): 주석만 풀면 안 되고 **`proxy_set_header Connection "";`이 전제조건**이었다(없으면 클라이언트의 `Connection: close`가 업스트림까지 전달돼 무효). ON이 p95 **-6.9~-11.9%**(0.2~0.7ms). 절대값은 작지만 Task 028/029의 개선폭과 같은 자릿수라 켠 상태를 기준선으로 고정
  - ✅ **가장 중요한 발견 — `http_req_failed`만으로는 이 서비스를 측정할 수 없다**: 좌석 경쟁에서 밀린 요청이 409라서, 서버가 초과 판매를 완벽히 막아낸 측정이 "실패율 **12.12%**"로 나온다. (상태코드 → `ErrorCode`) 화이트리스트로 "정상적 거절"과 "진짜 에러"를 가르는 커스텀 `Rate`를 만들어 같은 실행에서 `tikkit_real_errors` **0.00%**를 확인했다. 서버 카운터도 일치(`SOLD_OUT` 2,451 / 성공 1,215 / **500·401 0건**)
  - ✅ **Hikari는 600만 행에서도 안 뒤집혔다**: `active` 최대 **1**(경쟁 시 4), `pending` **0**, `idle` 최소 9. 004_3의 "풀 10이 과하다"가 데이터 규모를 60배 늘려도 유지된다. heap은 142.8MB → **188.8MB**
  - ✅ `docs/improvements/006-performance-baseline.md` 작성, Grafana 캡처 `docs/images/`
  - 알려진 한계: **이 수치는 한계 용량이 아니라 저부하 구간의 지연이다** — BE CPU 최대 4.2%, Hikari `pending` 0으로 서버가 거의 놀고 있고 VU/도착률은 전부 설정값이다. **포화점을 찾지 않았다**(breakpoint 테스트는 Task 033). **기본 설정으로는 좌석 경쟁이 재현되지 않는다** — 도착률 30/s에 iteration이 44ms라 동시 VU가 1개뿐이고, 2,000석 풀에서 무작위로 1~4석을 집으면 겹칠 확률이 0에 가깝다. `SEAT_STRATEGY=head`로만 경쟁이 나오고 그 측정은 1회뿐이다. **좌석 소진과 경쟁이 섞인다**(빈자리가 없으면 선점 요청 없이 iteration이 조기 종료하는데 이를 세는 Counter가 없다). **부하 생성기와 대상이 같은 호스트**(16코어)에 있다. **데이터가 균일하다**(1,000개 공연이 전부 같은 공연장·좌석 수·`ON_SALE`) — Task 028의 `EXPLAIN ANALYZE` 해석에서 선택도를 왜곡할 수 있다. **`k6_*` 패널을 대시보드 JSON에 넣지 않았다**(Explore에서 수동 비교). BE 인스턴스별 분포를 분해하지 않았다. 예약 행이 측정마다 쌓인다(리셋이 삭제가 아니라 `CANCELLED`). **범위 밖 발견**: ① **nginx 헬스체크가 Task 026부터 계속 실패하고 있었다** — 컨테이너 `/etc/hosts`의 `::1 localhost` 때문에 busybox wget이 IPv6를 먼저 쓰는데 `listen 80;`은 IPv4만 바인딩한다. nginx가 healthy해지기를 기다리는 서비스가 없어서 아무도 몰랐고 k6의 `depends_on`이 처음 걸렸다(`127.0.0.1`로 고쳤다). ② **예약 DELETE가 `schedule_seats` 600만 행 풀스캔을 유발한다** — `reservation_id`에 인덱스가 없는 게 HOT 업데이트를 위한 V4의 의도적 설계라서, 12분 넘게 안 끝나 `pg_terminate_backend`로 끊었다. 운영에서 예약을 물리 삭제할 일이 생기면 같은 벽이다(Task 028에서 같이 판단). ③ k6는 **iteration마다 VU 쿠키 자르를 비운다** — 모르면 "VU당 1회 로그인"이 전부 401이 되고, Task 026의 세션 불일치와 증상이 똑같다. ④ k6 기본 설정은 요청 URL을 `name`/`url` 라벨에 넣어 **시리즈가 3,255개**까지 늘었다(`systemTags`에서 `url`을 빼고 `name`을 템플릿으로 고정해 **18개**). ⑤ **Task 026의 세션 불일치 패널이 영구히 켜져 있다** — frontend 헬스체크가 10초마다 쿠키 없이 `/members/me`를 호출해서 정상 상태에서도 401이 분당 6건 찍힌다. 진짜 불일치가 나도 배경 트래픽과 구분되지 않는다(k6 요청의 401은 0건이라 이번 합격 기준에는 영향 없다)
- ✅ **Task 028: [BE] 쿼리·인덱스 최적화** (`feature/query-optimization`)
  - ✅ **인덱스를 하나도 추가하지 않았다 — `V7__add_indexes.sql`을 만들지 않았다.** 후보 7종을 600만 행에서 실측했고 전부 기각됐다. Task 022가 dev 규모(26,894행)에서 같은 결론을 냈는데, 데이터를 **224배** 늘려도 유지된다. ERD 이력 표의 V7은 번호를 비워 두고 사유를 적었다
  - ✅ **가장 중요한 발견 — `Buffers: shared hit`은 I/O의 대리지표가 아니다.** 커버링 인덱스(`(schedule_id) INCLUDE (seat_id, status, ticket_grade_id)`)가 좌석맵의 버퍼 접근을 **2,130 → 354(-83%)** 로 줄이는데 실행 시간은 **2.423 → 2.469 ms로 변화가 없었다.** shared buffers를 비운 cold 측정에서 **실제 페이지 읽기가 196 vs 198로 같았기** 때문이다 — 한 회차의 2,000행이 물리적으로 42페이지에 뭉쳐 있어서, 힙을 안 보면 그만큼 인덱스 페이지를 대신 읽는다. 첫 측정에서는 4.604 → 3.216 ms로 "-30%"가 나왔는데 그 before에 cold 상태와 planning 2.056 ms가 섞여 있었다 — **VACUUM 후 조건을 맞추지 않았으면 338 MB 인덱스를 넣고 HOT을 깨뜨린 채 "개선했다"고 적었을 것이다**(006의 "측정 도구가 틀리면 개선도 틀린다"가 그대로 재발했다)
  - ✅ **ERD의 예측이 절반만 맞았다.** Task 022가 좌석맵 10.70 ms의 원인으로 지목한 둘 중 `seats` Seq Scan은 맞지만(0.58 ms) **정렬은 틀렸다** — 2,000행 quicksort가 **0.05 ms**다. 실제 비용은 `schedule_seats` Index Scan이 인덱스에 `id`·`status`·`ticket_grade_id`가 없어 행마다 힙을 다시 보는 데 있었다
  - ✅ **HOT 비율 100%를 600만 행에서 확인했다**(3회 모두, 선점·확정·취소 약 15,500건 UPDATE). Task 022가 남긴 "HOT 측정 표본이 6건이다"가 닫혔다. 비교군 `reservations`는 예측대로 0%다(`idx_reservations_pending_expires_at`가 갱신 대상 `status`를 덮는다)
  - ✅ **`reservation_id` 인덱스 기각**(006이 넘긴 숙제). 예약 100건 물리 삭제가 **26,906 ms → 2.71 ms(약 9,900배)** 로 빨라지는데 기각했다 — **그 경로가 서비스에 존재하지 않는다**(상태 머신에 물리 삭제가 없고, 리셋은 `reservation_seats` 경유로 0.73초다). 대가는 HOT **100% → 34%** 와 예매 p95 **+9.5%**(7.77 → 8.50 ms)이고 이건 모든 선점·취소 요청이 매번 문다. 34%가 0이 아닌 이유는 좌석 UPDATE 셋 중 `markSold`만 `reservation_id`를 안 건드려 HOT으로 남기 때문이다. ERD에 "운영에서 예약을 물리 삭제하지 않는다"를 명시했다
  - ✅ **N+1은 구조적으로 없었다** — 전 엔티티에 `@OneToMany`·`@BatchSize`·`@EntityGraph`가 0건이고 목록은 전부 DTO 프로젝션이다. 그래서 ROADMAP에 적어둔 "`@BatchSize`로 해결"은 **적용할 지점이 없었다**(쓰려면 먼저 `@OneToMany`를 만들어야 한다). 파트를 "없는 상태를 고정하기"로 바꿨다
  - ✅ 유일하게 찾은 지연 로딩: 선점 경로에서 `ticketGrade.getSchedule().isBookingOpen()`이 `bookingOpenAt`을 읽는 순간 프록시가 초기화된다(`getId()`까지는 안 터진다). `findByIdWithSchedule` fetch join으로 **왕복 7 → 6**
  - ✅ **쿼리 카운트 하네스를 처음부터 만들었다**(`src/test`에 `Statistics`·p6spy가 0건이었다). `generate_statistics`를 test 프로필에만 켜고 `QueryCounter`로 센다. **트랜잭션 밖에서 세야** 해서 `AbstractConcurrencyTest`(비트랜잭션 + truncate)를 베이스로 썼다 — 동시성과 무관하지만 필요 조건이 일치했다. 테스트 10개로 경로별 문장 수를 고정했다
  - ✅ **목록은 2문장이 아니라 1문장이었다** — `PageableExecutionUtils.getPage`가 offset 0이고 content가 페이지 크기보다 작으면 count를 아예 안 쏜다. 예상이 틀렸고 테스트를 둘로 갈라 양쪽을 고정했다
  - ✅ SQL을 손으로 재구성하지 않았다 — test 프로필 `show-sql`로 Hibernate가 실제 생성한 SQL을 받아 그 문장을 600만 행 DB에 던졌다 (004가 "동등 쿼리라 before와 직접 비교할 수 없다"고 남긴 한계를 피했다)
  - ✅ after 측정(006 7.1과 같은 조건, 3회 중앙값): 탐색 **2.48 ms** / 좌석맵 **6.77 ms** / 예매 **7.77 ms**, `tikkit_real_errors` 0.00%, checks 100%
  - ✅ `docs/improvements/007-query-optimization.md` 작성, ERD 2절·4절 갱신(V7 담당 Task를 027 → 028로 정정)
  - 알려진 한계: **선점의 쿼리 1문장 감소를 p95로 확인하지 못했다** — 006 4.3절이 "쿼리를 2ms 개선해도 안 보인다"고 예고한 그대로이고, 줄인 건 0.1~0.3ms라 3회 산포(0.32ms)보다 작다. 검증은 쿼리 카운트 테스트뿐이다. **before/after 조건이 어긋나 있다** — 006 측정 이후 같은 볼륨을 계속 써서 `reservations`가 13,798 → 약 43,000행으로 쌓였고, 커버링 인덱스 실험으로 VACUUM 2회와 postgres 재시작 2회가 섞였다. 그래서 after 표는 "개선 폭"이 아니라 **"회귀가 없다"까지만** 말한다. **`shared_buffers`가 기본값 128MB인데 DB는 1,039MB다** — "인덱스가 줄이는 게 I/O가 아니다"는 결론이 작업 집합이 이 128MB에 들어온다는 전제에 기대고 있고, 이 값을 올리는 쪽이 인덱스보다 효과가 클 수 있는데 **측정하지 않았다**(Task 033 breakpoint에서 전제가 깨질 수 있다). **`EXPLAIN ANALYZE`를 접속 하나에서 직렬로 쟀다**(82 req/s가 동시에 들어오는 중의 플랜이 아니다). **데이터 균일성이 쿼리마다 다르게 작용한다** — `[LOAD]` 공연 1,000개의 `start_date`가 전부 같아서 목록 쪽 Incremental Sort 관찰은 운영으로 외삽할 수 없고, 좌석맵·HOT 쪽은 선택도가 스키마에서 나오므로 영향이 없다. **범위 밖 발견**: ① **`uk_members_email`이 구조적으로 쓰일 수 없다** — 인덱스는 `lower(email)` 함수 인덱스인데 리포지토리는 `where email = ?`로 조회해서 로그인이 **항상 Seq Scan**이다(503행, 0.088ms). 차이가 0.039ms이고 로그인 p95는 BCrypt로 54ms라 고치지 않았다. ② **저장소의 k6 재현 커맨드가 Git Bash에서 안 된다** — MSYS2가 `/scripts/...`를 `C:/Program Files/Git/scripts/...`로 번역해서 k6가 스크립트를 못 찾는다(`MSYS_NO_PATHCONV=1` 필요). 006 재현 방법에 그 언급이 없어 첫 줄에서 막힌다. ③ **`ReservationServiceTest`가 리포지토리 메서드 이름에 묶여 있다** — 조회를 `findById`에서 `findByIdWithSchedule`로 바꾸자 목 스텁 13개가 한꺼번에 깨졌다(fetch 전략을 바꿀 때마다 같이 깨진다). ④ `VACUUM`이 컨테이너 `/dev/shm` 부족으로 죽어서 `PARALLEL 0`이 필요했다
- ✅ **Task 029: [BE] 캐싱 적용** (`feature/caching`)
  - ✅ **좌석맵은 캐싱 자체를 기각했다 — 적중률 99.97%인데 캐시 없는 원본보다 느렸다**(6.63 → 7.22ms). 좌표·등급은 불변이라 캐시할 수 있는 모양이어서 "불변 부분만 캐시 + 상태는 실시간" 구조를 만들어 실측했는데, 캐시 항목이 2,000석에 **337,885바이트**라서 히트 경로가 하는 일이 "DB 안 가기"가 아니라 **"330KB를 받아 2,000개 record로 역직렬화하기"**였고 그게 Postgres에서 2,000행 읽는 것보다 비쌌다. 쿼리 분리 자체도 캐시 없이는 순손실(6.63 → 8.62ms)이라 단일 쿼리로 되돌렸다
  - ✅ **기각 근거가 "지금 느리다"가 아니라 "방향이 반대다"다.** 전송량과 역직렬화 비용이 둘 다 좌석 수에 선형이라 공연장이 커지면 격차가 벌어진다. 회차 3,000개면 Redis 기준 1.15GB다. 좌석맵의 확장성 해법은 캐싱이 아니라 응답 크기를 줄이는 것이고 그건 Task 030이 맡는다
  - ✅ **채택: 공연 목록·상세 + 등급 가격(Redis).** 항목이 690B~수 KB라 규모가 커져도 캐시 비용이 거의 그대로인데, 카탈로그가 커지면 원래 쿼리가 나빠진다(007 4.7의 offset 980 플랜 역전, 4.8의 `pg_trgm` 3자 미만 한계). **현 규모에서는 p95를 개선하지 못한다**(2.20 → 2.27ms, 산포 범위) — 측정 구간이 BE CPU 4.2%의 저부하라 DB 작업을 줄인 효과가 원래 지연으로 드러나지 않는다. 채택 근거가 지연이 아니라 용량이라는 걸 명시하고 검증은 Task 033으로 넘겼다
  - ✅ **Caffeine(로컬) 비교 후 기각.** 좌석맵에서는 가장 빨랐지만(4.98ms — 힙에 객체 그래프를 두니 직렬화가 0이다) 그 대상이 사라졌다. 남은 캐시는 전부 작아서 직렬화를 피해 얻는 이득이 없고, 공연 파생값 evict는 만료 배치가 ShedLock으로 **한 인스턴스에서만** 돌아 로컬 캐시면 반쪽만 적용된다
  - ✅ **응답 바이트 캐시 실험 후 기각** (007이 "이득은 237.9KB 직렬화에서 나올 것"이라 넘긴 가설). 서비스 레이어 `@Cacheable`은 직렬화를 **구조적으로** 못 줄인다 — 캐시가 돌려주는 건 객체고 컨트롤러가 반환하면 Jackson이 다시 쓴다. Timer로 갈라 보니 좌석맵 응답의 **1.4~2.2ms가 캐시 밖**(직렬화+서블릿·nginx)이다. 바이트를 캐시해 직렬화를 건너뛰어도 p95 차이가 0.01ms였다 — 적중률 26.7%에서 미스 때 238KB를 Redis에 쓰는 비용이 상쇄한다. **TTL보다 자주 들어오는 키에서만 의미가 있다**(예매 시나리오는 82 req/s라 48.4%)
  - ✅ **캐시가 조용히 틀리는 지점 다섯 개를 테스트로 고정했다.** ① `withInitialCacheConfigurations` 뒤에 `initialCacheNames`를 부르면 캐시별 TTL이 `cacheDefaults`로 덮인다 — `PTTL`이 전부 `-1`이었고 설정 코드만 읽으면 멀쩡해 보인다(`CacheTtlTest`가 Redis 키의 남은 수명을 직접 읽는다) ② 캐시 키에 SpEL의 `null` 문자열화를 기대면 안 된다(Elvis로 `'-'` 명시) ③ `PolymorphicTypeValidator` 허용 목록에 `java.math`·`java.time`이 필요하다 — 타입 id는 final이 아닌 모든 값에 붙어서 `BigDecimal`에서 바로 터진다 ④ 키가 경로 변수면 사용자가 키 공간을 고를 수 있다(`unless = "#result.isEmpty()"`로 빈 결과 제외) ⑤ **기본 비활성 토글에도 테스트가 필요하다** — 응답 바이트 캐시의 히트 경로가 터져 있었고(`byte[]`를 JSON 직렬화 캐시에 넣었다) 미스는 멀쩡해서 부하 측정에서야 진짜 에러 27%로 드러났다
  - ✅ **캐시도 워밍업 대상이다.** 20초 워밍업으로는 탐색 p95가 4.99 → 3.60 → 2.69ms로 계속 내려갔다 — 측정 사이에 DB는 리셋하지만 캐시는 TTL까지 남아서 회차를 거듭할수록 적중률이 올라간다. 워밍업을 본 측정과 같은 길이로 늘리고 조건을 **"연속 3회가 평탄해질 때까지"**로 바꿔 2.36/2.27/2.19로 수렴시켰다. 캐시 없는 A군이 20초로 평탄했던 건 JVM만 데우면 됐기 때문이다
  - ✅ **적중률 계측**(006이 요구한 교차 확인 수단): `cache_gets_total{result}` + Grafana 패널 2개(캐시별 적중률, 좌석맵 생성 시간 vs 전체 p95). 균일 분포에서 목록 92.7% / 등급 64.2% / 상세 40.4%고, 편중 분포(`browse-skewed.js` 신규)에서 상세가 **77.6%** 로 뛴다 — 균일 분포 수치는 캐시 효과의 **하한**이다
  - ✅ 등급 조회가 3문장 → **2문장**(캐시와 무관). 등급을 먼저 조회하고 결과가 비었을 때만 `existsById`로 404를 가르도록 순서를 뒤집었다
  - ✅ `docs/improvements/008-caching.md` 작성, Grafana 패널 2개 추가
  - 알려진 한계: **이 Task는 채택을 수치로 입증하지 못했다** — 저부하 구간에서는 캐시의 이득이 원래 p95에 안 보이고(BE CPU 최대 4.2%, Hikari `pending` 0), 근거가 용량이라서 검증이 Task 033에 달려 있다. **좌석맵 기각의 경계를 모른다** — 690B는 이득이고 337,885B는 손해인데 그 사이 어디서 뒤집히는지 재지 않았다. **포화 구간에서는 기각이 뒤집힐 수 있다**(DB가 병목이면 역직렬화 비용을 내고도 이득일 수 있어서 Task 033의 재측정 후보로 남겼다). **로컬 캐시의 2대 불일치를 실측하지 않았다**(코드 구조에서 나온 판단이고 Task 026처럼 compose 2대로 재현하지는 않았다). **`Cache.clear()`가 왜 늦게 반영되는지 규명하지 못했다** — 테스트는 키 공간 분리와 폴링으로 돌아가게 했고 운영에서는 evict 지연이 ms라 캐시 미스 한 번으로 끝난다. **편중 분포 수치는 기준선과 비교할 수 없다**(006·007이 같은 분포로 잰 값이 없고 쏠림 파라미터도 가정이다). **적중률을 Prometheus 시간 창으로 잘라 읽었다**(창 경계가 워밍업과 겹치면 흔들린다). **before/after 조건이 완전히 같지 않다** — `reservations`가 006 당시 13,798 → 007 약 43,000 → 이번 55,429행이고 예매 측정마다 더 쌓였다. **범위 밖 발견**: ① **`@Cacheable`은 캐시를 꺼도 비용이 있다** — `NoOpCacheManager`여도 인터셉터가 매 호출마다 돌고 프록시 홉이 붙어서 탐색 p95가 2.20 → 2.63ms였다 ② **테스트 컨텍스트가 늘면 Postgres 커넥션이 고갈된다** — 캐시 토글 조합마다 컨텍스트가 생기는데 `AbstractConcurrencyTest`가 Hikari 풀을 30으로 올려 둬서 기본 `max_connections`(100)를 넘겼고, 증상이 캐시와 무관한 동시성 테스트에서 `too many clients already`로 나타났다(캐시 테스트 풀을 5로 줄여 피했을 뿐 근본 해법은 컨테이너 `max_connections`다)
- ✅ **Task 030: [FE] 렌더링 성능 측정** (`feature/frontend-performance`) — 측정 단계. 개선은 030_1·030_2
  - ✅ **전송량의 88%가 폰트 하나였다.** 좌석맵 1회 로드 전송 2,277KB 중 `PretendardVariable.woff2`가 **2,009.5KB**다. 좌석맵 문서(전송 32.7KB)의 **61배**, 006·007·008이 이 Task로 넘긴 BE 좌석맵 응답(240KB)의 **8.4배**다. 출처는 `app/globals.css:4`의 `@import "pretendard/dist/web/variable/pretendardvariable.css"` 한 줄 — 같은 패키지에 `unicode-range`로 쪼갠 `-dynamic-subset.css`가 들어 있다. woff2는 내부 압축이 이미 걸려 있어 gzip으로는 못 줄이고 **서브셋이 유일한 레버**다
  - ✅ **ROADMAP에 적혀 있던 항목 3개 중 2개가 무효였다.** `next/image`는 **적용 대상 0개**(`<img>` 0건, `public/` 없음, `poster_url` 전부 NULL, 포스터 자리는 Lucide 아이콘), `revalidate`/`cacheLife`는 `apiFetchRaw`가 `cookies()`를 **무조건** 호출해 전 세그먼트가 동적 렌더링으로 떨어져서 선행 리팩터가 필요하다. 근거가 살아남은 건 **Suspense 스트리밍**뿐이다
  - ✅ **범위를 "측정"과 "개선"으로 쪼갰다. FE 애플리케이션 코드는 한 줄도 고치지 않았다** — 무엇을 고칠지 수치로 정하는 것이 이 단계의 목적이다
  - ✅ **좌석 배열이 두 번 실리는 걸 숫자로 확정했다.** 좌석맵 문서 659.24KB = SSR 마크업 **352.58KB**(`rect` 2,006개) + 인라인 RSC **304.97KB**(`seats` 직렬화). `SeatSelector`가 Client Component라서다. **BE 응답만 줄여도 브라우저 전송량은 안 줄어든다** — 006·007·008이 넘긴 "237.9KB를 줄여라"는 ② 층만 건드리는 일이다
  - ✅ **압축이 구간마다 정반대다.** BE→Next는 `Accept-Encoding`을 줘도 안 줘도 **240.12KB 그대로**(비압축 확정), Next→브라우저는 659.24 → **32.7KB**(20:1). nginx에 `gzip` 지시자가 0건이라 "비압축일 것"으로 봤는데 **절반만 맞았다** — Next.js가 자체 압축(`compress` 기본값 true)한다. "gzip이 가장 싼 레버"는 **② 층에만** 남아 있다
  - ✅ **600만 행은 FE 지표에 보이지 않는다.** 축소 시드(`schedule_seats` 2,000)와 600만 행에서 좌석맵 문서가 **675,063바이트로 바이트 단위 동일**하고 FCP·LCP·클릭 응답성도 산포 안에서 같다. Redis 캐시 콜드 회차로 "캐시 히트 착시"도 배제했다. **이후 FE 측정에 1분 58초짜리 시딩이 불필요하다**(축소 시드는 0.7초) — 이를 위해 `01b-small-catalog.sql`을 만들었다(`01`과 코드 2줄만 다르고 **좌석 2,000석은 유지**)
  - ✅ **좌석맵의 FCP 251ms는 "빨라서"가 아니다.** 홈·상세는 1,890/1,893ms인데 좌석맵만 250ms다 — 문서는 좌석맵이 20배 큰데도. 좌석맵의 첫 콘텐츠가 **글자가 아니라 `<rect>` 2,006개**라서 같은 폰트 비용을 내고도 지표에만 안 잡힌다. 좌석맵만 보고 "FE는 괜찮다"고 판단하면 안 된다
  - ✅ **클릭 → DOM 반영의 측정 계약을 못 박았다**(004의 "5~8ms"는 방법이 문서에 없었다). t0 디스패치 → t1 `aria-checked` 커밋(`MutationObserver`) → t2 다음 프레임. commit 중앙값 **6.95ms** / frame 16.7ms. **`page.click()` + `expect()`로 쟀으면 52ms로 7.5배 과대 측정**했을 것이라 전부 페이지 내부 시계로 잰다
  - ✅ **개선 후보 하나가 근거를 잃었다.** 선택 가능 레이어에 `memo`가 없고 좌석마다 `includes()`로 O(n)을 돌지만, 깊이별 클릭 비용이 **6.6~7.2ms로 평탄**하다. 선택 상한이 4라서(`@Size(max = 4)`) 배열 길이가 최대 4이고 2,000석 재렌더가 그걸 완전히 가린다. `memo` + `Set` 전환은 이 규모에서 이득이 없다
  - ✅ **`99-teardown.sql`이 600만 행에서 완주할 수 없는 상태였다 — 이게 맥북에서 측정을 포기한 이유이자 이번 3시간을 먹은 지점이다.** Postgres는 FK 자식 컬럼에 인덱스를 자동 생성하지 않아서 부모 1행 삭제마다 자식을 풀스캔한다. **복합 유니크가 있어도 선두 컬럼이 아니면 못 탄다**(`reservation_seats.schedule_seat_id`, `schedule_seats.seat_id` 둘 다 두 번째 컬럼). 인덱스를 못 타는 FK가 **5개**였고 `DELETE FROM reservations`는 41분에도 미완이었다. **삭제 전용 임시 인덱스 7개를 트랜잭션 안에서 만들고 커밋 전에 지우는 방식으로 3시간+ 미완 → 58.2초**가 됐다(축소 시드 2,000행은 0.471초. 영구 추가는 V4가 HOT 업데이트 때문에 의도적으로 뺀 결정이라 건드리지 않았다). 커밋 뒤 `VACUUM (FULL, ANALYZE)`도 추가 — `schedule_seats` 1,037MB → 2,728kB
  - ✅ **`98-reset-reservations.sql`의 틀린 주석을 정정했다.** "99-teardown은 `schedule_seats`를 먼저 지워서 이 문제를 피한다"고 적혀 있었는데, 트랜잭션 안의 DELETE는 테이블을 작게 만들지 않는다(지운 행도 dead tuple로 남아 풀스캔이 모든 페이지를 읽는다). **검증 없이 적힌 안심 문장 한 줄이 주석이 아예 없는 것보다 비쌌다**
  - ✅ `docs/improvements/009-frontend-performance.md` 작성. 측정 인프라 신규: `load-test/sql/01b-small-catalog.sql`, `tikkit-front/e2e/perf/{target.ts,payload-bytes.spec.ts,seat-interaction.spec.ts}`, `playwright.config.ts`의 `perf` 프로젝트, `lighthouse` 13.5.0 정확 버전 고정
  - 알려진 한계: **"폰트가 88%"와 "전송량이 지표를 지배한다"를 각각 확정했지만 둘을 인과로 잇지 못했다** — 스로틀을 끄면 홈 FCP가 1,890 → **80ms**로 23배 줄어 전송량이 전부임은 확실한데, 폰트가 FCP를 막는다는 설명에는 반례가 둘 있다(패키지 CSS에 `font-display: swap`이 이미 있고, mobile은 1,638kbps라 같은 폰트에 9.8초가 필요한데 FCP가 914ms다). **폰트만 서브셋으로 바꿔 다시 재는 것이 개선 Task의 첫 측정이어야 한다**. **좌석맵 FCP가 이중분포(250ms / 1,896ms)라 3회로는 수렴 판정이 안 돼 6회로 늘렸고, 원인을 규명하지 못해 좌석맵의 FCP·LCP·SI를 "판단 불가"로 표시했다** — 개선 Task가 이 지표로 before/after를 비교하려면 먼저 이걸 풀어야 한다. **mobile과 캐시 콜드가 n=1**이다. **Lighthouse는 항상 브라우저 캐시 콜드**라 폰트 2MB의 88%는 첫 방문 기준이고 재방문자는 안 낸다(재방문 시나리오 미측정). **저부하 단발 측정**이라 ms 값으로 확장성을 말할 수 없다. **층 ②를 gzip으로 바꿨을 때의 지연 변화를 재지 않았다**(바이트만 쟀고 압축 CPU가 상쇄할 수 있다). **범위 밖 발견**: ① **`error.log` grep이 실행 불가능한 지시였다** — 컨테이너의 `error.log`는 `/dev/stderr` 심볼릭 링크라 파일 grep이 **항상 0건**을 돌려준다. 빈 결과와 "문제 없음"이 구분되지 않는다. `docker compose logs nginx`로 고쳐 재니 `buffered to a temporary file` 0건이었다(`proxy_buffers` 256KB가 240KB를 담았고 여유는 16KB뿐) ② **`max_fails` 기본값이 1회/10초다** — `upstream`에 명시가 없어 **요청 한 번 실패로 BE가 10초간 제외**되고, `sleep` 없는 readiness 루프가 BE 2대를 동시에 내려놓아 502가 285건 찍혔다. 측정 중 산발 실패로 홈 회차 1개가 오염됐다 ③ **폰트가 지배하면 바이트 무게를 무결성 검사로 쓸 수 없다** — 데이터가 통째로 빠진 홈 회차와 정상 회차의 FCP 차이가 **1ms**, 총 바이트 차이가 **0.6%**였다. 걸러낸 건 문서 디코드 크기(44.96 vs 27.26KB)다 ④ **`package.json` 스크립트에 환경변수 접두사를 쓸 수 없다**(Windows npm이 `cmd.exe`로 돌린다) ⑤ **`02`의 시딩 시간이 호스트에 따라 1.7배 차이 난다**(006 기록 70초, 이 PC 118초). teardown 비용은 006이 아예 기록하지 않았다
- ✅ **Task 030_1: [FE] 전송량 축소** (`feature/frontend-transfer-size`) — 전송 바이트를 줄이는 레버만. 구조 작업은 030_2로 분리
  - ✅ **`@import` 한 줄이 전송량 76.8%, FCP 67.4%를 줄였다.** `globals.css:4`를 `pretendardvariable-dynamic-subset.css`로 바꾼 것 하나다. 홈 FCP **1,892 → 616ms** / LCP 2,092 → 744ms / 전송 **2,314,727 → 536,216B**, 상세 FCP 1,892 → **536ms**(-71.7%) / 전송 -80.6%, 좌석맵 전송 -79.9%. 점수는 홈 80 → 99, 상세·좌석맵 80 → **100**. 홈·상세는 before·after 양쪽 FCP 산포가 0.3% 이하로 수렴해서 판단에 쓸 수 있는 수치다
  - ✅ **009가 못 닫은 세 가지가 레버 하나로 같이 닫혔다.** ① 4.3절 "폰트를 줄이면 FCP가 내려가는가" → 내려간다. `font-display: swap`이 있어도 FCP가 폰트에 묶여 있었다 — **`swap`은 대역폭 경쟁을 막는 선언이 아니다**(폰트 2MB가 커넥션 풀을 물면 CSS·JS·문서가 뒤에 줄을 서서 대체 글꼴로 그릴 준비조차 늦어진다) ② 6.5절 좌석맵 FCP 이중분포(249/1,896ms) → **사라졌다.** 370~494ms 한 덩어리로 수축해 폭이 1,647 → **124ms**다. 249ms 회차는 좌석 `rect`가 폰트 경쟁을 피한 경우였다 ③ 4.1절 서브셋 절감량 → 홈 11조각 289,305B(**-85.9%**), 상세·좌석맵 8조각 205~211KB(-90%)
  - ✅ **메커니즘을 규명하는 것보다 원인 후보를 제거해 보는 쪽이 빨랐다.** 009는 이중분포의 메커니즘을 파다가 "미규명"으로 남겼는데, 제거에는 `@import` 한 줄과 측정 6회면 됐다
  - ✅ **걱정했던 리스크가 터지지 않은 이유를 알았다.** 조각 92개 합계가 3.1MB라 "음절이 흩어지면 2,009KB를 못 밑돈다"를 우려했지만 8~11조각만 왔다 — Pretendard의 dynamic subset은 코드포인트 순이 아니라 **글자 사용 빈도 순**이고 **번호가 클수록 흔한 글자**여서(91 = 라틴·숫자, 90 = 최빈 한글 간/개/거/게/공/과/관/구/그/나, 0~2 = 거의 안 쓰이는 글자) 한국어 페이지는 92조각 중 8~11개만 끌어온다. 조각당 약 26KB. 요청이 1 → 11개로 늘어 HTTP/1.1 커넥션 6개 제한에 걸리는데도 FCP가 1,276ms 내려갔다
  - ✅ **`favicon.ico` 25,931B → `icon.svg` 473B** (전송 26,269 → 803B). Next의 metadata 파일 규약이 `<link rel="icon">`을 자동으로 넣어 `layout.tsx`는 안 건드렸다. FCP는 618 → 617ms로 산포 안이다 — `High` 우선순위여도 첫 페인트를 막지는 않고 줄어든 건 바이트뿐이다. 측정 함정 하나: Lighthouse의 `Other`에 favicon과 **`resourceType`이 없는 307 리다이렉트**가 같이 잡혀서 "26,463B"로 뭉개졌다(수집 스크립트를 고쳤다)
  - ✅ **층 ② gzip은 기각했다.** 압축은 19:1로 잘 걸리는데(247,885 → **13,086B**) **BE 왕복에 +2.0ms(7.05 → 9.09ms, +29%)** 가 붙고 `tikkit.seatmap.build` Timer는 불변이라(4.08~4.25ms) 늘어난 전부가 압축 구간이다. **기각 근거는 "느리다"가 아니라 "지금 구성에서 아낀 바이트가 아무것도 아니다"다** — BE·nginx·Next가 한 호스트라 240KB가 compose 네트워크를 지나는 비용은 이미 7ms 안에 있다. `proxy_buffers` 여유 16KB 문제도 압축보다 버퍼를 키우는 쪽이 싸다. **`application.yml`을 되돌려 BE 변경 0으로 끝냈고** 기본 비활성 토글로도 남기지 않았다(008 6절). 재판단 조건은 Task 034의 호스트 분리다
  - ✅ **계획의 `확인 필요` 하나가 유리하게 닫혔다** — `apiFetchRaw`가 `Accept-Encoding`을 명시하지 않아 "Node fetch가 헤더를 안 보내면 효과 0"일 수 있었는데, undici가 기본으로 보내고 `content-encoding: gzip`을 받아 투명하게 해제한다. FE 수정 불필요
  - ✅ **번들 분석(측정만): 폰트를 줄이자 Script가 1순위가 됐다**(전송 비중 8.3% → **35.7%**). 홈 스크립트 191,785B 중 `react-dom` 73,132B(38.1%) + `scheduler` 40,880B(21.3%) = **React만 59.4%로 바닥**이고, 손댈 여지는 **`@base-ui/react` 34,562B(18%)** 하나다 — `components/ui/button.tsx:1`의 `@base-ui/react/button` 때문에 전 페이지가 낸다. 스타일은 이미 `cva`가 다 해서 네이티브 `<button>` 치환이 다음 Task 1순위 후보다. **`@next/bundle-analyzer`는 이 프로젝트에서 무효다** — webpack 플러그인인데 Next 16은 Turbopack으로 빌드해서 훅을 안 타고 **조용히 아무것도 안 나온다**(설치했다가 지웠다). 내장 `next experimental-analyze`가 맞는 도구인데 산출물이 비공개 바이너리라 청크 파일 마커 grep으로 귀속했다
  - ✅ **before를 다시 쟀다 — 007·008이 계속 부딪힌 "before/after 조건 불일치"를 처음 피했다.** 같은 세션에서 before → 변경 → after를 연속으로 재고 009 표는 교차 확인용으로만 썼다(다시 잰 홈 before가 1,892ms / 2,314,727B로 009의 1,890ms / 2,315,361B와 일치). B군은 009 4.6절 근거로 생략해 **`01b-small-catalog.sql`을 처음 써먹었다**(시딩 0.74초)
  - ✅ **측정 하네스 신규**: `tikkit-front/scripts/lighthouse-runs.mjs` — 회차 수집 + 중앙값·산포 계산 + **산포 20% 초과 "판단 불가" 자동 표기** + **문서 `resourceSize` 무결성 검사**(009 4.9절의 요구: 데이터가 통째로 빠진 회차와 정상 회차의 FCP 차이가 1ms, 총 바이트 차이가 0.6%였다) + 폰트 요청 개별 나열. `npx` 대신 lighthouse CLI 진입점을 node로 직접 실행(Windows `.cmd` 우회)
  - ✅ `docs/improvements/009_1-frontend-performance-improvement.md` 작성, 009 정정 4곳(2절 `CACHE_TYPE`은 prod 기본값이 `redis`가 아니라 **`none`**이다, 4.1·4.3·6.5절 콜아웃에 결과 연결)
  - 알려진 한계: **전송량 1순위가 여전히 폰트다**(after 홈 536KB 중 289KB = 53.8%). 다음 레버는 직접 서브셋(KS X 1001 단일 파일)인데 범위 확정 때 "측정하고 멈춘다"로 정해서 하지 않았다. **조각 수의 상한을 모른다** — 글자 다양성에 따라 늘어나는데(홈 11개 vs 상세 8개) 공연 1,000개 목록·검색 결과를 재지 않았다(A군 = 공연 9개). **재방문자는 이 개선을 못 느낀다**(Lighthouse는 항상 캐시 콜드, woff2는 해시 URL로 immutable 캐시 — -1.78MB는 첫 방문 기준이고, 오히려 요청이 11개로 늘어 조건부 요청이 많아지는데 재지 않았다). **폰트 → FCP 인과의 메커니즘을 Lantern 내부로 확인하지 않았고** mobile 반례(1,638kbps에서 폰트 9.8초인데 FCP 914ms)도 그대로다(mobile 미측정). **좌석맵 FCP가 아직 "판단 불가"다**(산포 25.2% — 두 모드는 사라졌지만 5회가 491~494ms에 몰리고 1회가 370ms로 빠지는 이유를 모른다). **gzip 기각을 포화 구간에서 확인하지 않았다**(008이 좌석맵 캐싱 기각에 남긴 것과 같은 유보). **압축 CPU를 cgroup 카운터로 분리하지 못했다**(노이즈가 커서 wall로 판단했고 거기엔 nginx 몫도 섞여 있다). **번들 귀속이 청크 문자열 grep이라 비중은 근사다**(한 청크에 여러 라이브러리가 섞이면 첫 마커로 전부 귀속 — "마커 없음" 20.9%). **favicon에 `.ico` 폴백을 두지 않아** Safari 16 미만에서 아이콘이 안 보인다(폴백을 두면 양쪽 요청이 다 나가 절감이 사라진다). **범위 밖 발견**: ① **frontend만 재빌드해도 `backend1`이 같이 재생성된다** — `depends_on: backend1(healthy)` 때문이고, 그러면 IP가 바뀌어 nginx가 502를 뱉는다(009 6.3절은 군 전환 시였는데 **FE만 고쳐도** 걸린다). 변경마다 `restart nginx`가 필요하다 ② **`curl -K`로 여러 URL을 줄 때 `-o`는 첫 URL에만 적용된다** — 설정 파일 안에서 URL마다 `output =`을 써야 하고, 모르면 240KB × 299개가 stdout으로 쏟아진다 ③ **좌석맵 응답 바이트가 id 자릿수에 선형으로 반응한다** — 다시 잰 문서가 677,063B로 009의 675,063B보다 **정확히 2,000B**(좌석당 1B) 크다. 009가 B군 600만 행을 적재하며 시퀀스를 1,200만까지 밀어서 `schedule_seats.id`가 7 → 8자리가 됐다. 009 4.6절의 "A·B군 바이트 동일"은 두 측정이 같은 자릿수 구간이라 성립한 것이고, **다른 세션의 바이트는 그대로 비교할 수 없다** ④ Git Bash에 `bc`가 없다
- ✅ **Task 030_2: [FE] 좌석맵 구조 개선** — `docs/improvements/009_2-seatmap-structure.md`
  - ✅ **같은 변경이 2,000석에서는 회귀이고 10,000석에서는 개선이었다.** 정적 좌석을 등급별 `<path>`로 합치면 RecalcStyle이 2,000석에서 18.71 → **31.59ms(+68.8%)** 로 나빠지고 10,000석에서 71.84 → **29.20ms(-59.4%)** 로 좋아진다. **두 번째 측정 지점(10,000석 시드)을 안 만들었으면 기각했을 변경이다** — "근거가 전송량이 아니라 확장성"을 재려면 기울기가 필요하고 기울기는 한 점에서 말할 수 없다
  - ✅ **Lantern 스로틀이 하이드레이션 비용을 가리고 있었다.** 10,000석 TBT가 스로틀에서 1ms인데 **무스로틀에서 53ms**였고 이 변경으로 **0**이 됐다. 좌석 6,000개를 DOM 노드 6,000개로 만드는 하이드레이션 몫이다. 보조 실험으로 넣은 무스로틀 군이 가장 큰 발견을 건졌다. DOM 노드 10,245 → **4,253(-58.5%)**, heap 8,233 → 7,254KB(-11.9%)
  - ✅ **Suspense 분리가 "좌석 수에 비례하던 비용"을 상수로 만들었다.** 본문 h1 도착이 첫 바이트로부터 2,000석 17.41 → **4.44ms**, 10,000석 58.2 → **6.92ms**. before는 좌석 수를 따라 커졌고 after는 평탄하다. 다만 **ROADMAP의 전제는 틀렸다** — `loading.tsx`가 이미 Suspense 경계라 셸은 원래도 첫 바이트와 같은 청크에 나갔다. `redirect`/`notFound`가 soft인 것도 이번 변경 전부터였다
  - ✅ **그런데 쪼개는 건 공짜가 아니었다.** 자리표시자(28rem)가 실제 좌석 영역(1,114px)보다 짧아 푸터가 화면 안에 있다가 +376px 밀렸고 **CLS가 0.0278 → 0.1996(점수 100 → 90)** 으로 뛰었다. 폰트 탓이 아님을 폰트만 되돌린 군으로 확인했다. "실제 높이를 맞춘다"가 아니라 **"두 상태 모두에서 푸터를 접힘선 아래에 둔다"** 로 고쳤다(`min-h-[calc(100vh-12rem)]`) → **CLS 0.0005 / 점수 100(6/6)**, before보다도 98% 낮다
  - ✅ **`@base-ui/react/button` → 네이티브 `<button>`.** 홈 Script 전송 191,777 → **183,557B(-8,220B)**, 청크 13 → 12개. **009_1의 귀속(34,562B)이 4.2배 과대평가였다** — 자신이 경고한 "청크 grep 근사"의 오차 배수가 이번에 나왔다. `alert-dialog`는 base-ui를 계속 쓰므로 의존성은 예매 취소 화면에만 남는다. base-ui가 네이티브 버튼에 넣어주던 `type="button"`을 같이 옮겨야 폼 제출 회귀가 없다
  - ❌ **단일 폰트 서브셋 기각.** 요청 11 → 1로 **홈 FCP 615 → 207ms(-66.5%)** 가 나왔는데도 기각했다 — 미리 적은 수용 기준("폰트 바이트가 205~289KB보다 작을 것")을 374,336B가 넘었고, **그 FCP 이득의 정체가 바이트가 아니라 요청 수**였기 때문이다. nginx가 `listen 80`뿐이라 HTTP/1.1이고 커넥션 6개 제한에 조각 11개가 줄을 선다. **올바른 레버는 374KB 폰트가 아니라 HTTP/2다.** 바이트는 홈 +71KB / 상세·좌석맵 +169KB 늘고, 서브셋 2,620자 밖 글자는 대체 글꼴로 떨어져 사용자 입력 텍스트에 구조적 한계가 생긴다. **재판단 조건: Task 034에서 HTTP/2를 켠 뒤**
  - ❌ **BE 좌석 응답 축소 기각.** FE 전용 변경만으로 DOM·메모리 목표를 달성했고(위), 10,000석에서 점수 100 / TBT 0 / CLS 0.0011로 깨진 지표가 없다. 남은 체감 문제는 **클릭 commit 32.5ms**인데 그건 선택 가능 레이어 몫이라 응답 축소로 안 고쳐진다. API 계약과 BE 테스트 4개를 바꿀 근거가 부족하다. 재판단 조건: 좌석 수가 10,000석을 넘거나 선택 오버레이 이후에도 인라인 RSC(10,000석 1,454KB)가 병목일 때
  - ❌ **`getMe()` 격리의 수치 효과 기각 — 구조는 남겼다.** TTFB가 세 군 전부 개선 없이 미세하게 나쁘다(2,000석 6.52 → 7.24ms). `(main)/page.tsx`가 `force-dynamic`을 선언해 뒀고 `next build` 라우트 표도 before/after 모두 전부 `ƒ`다. ROADMAP이 적은 "layout.tsx가 getMe()를 호출"도 틀렸다 — 부르는 건 `header.tsx`다. 공개/세션 진입점 분리는 남겼다(새 API가 조용히 세션을 빼먹는 사고 방지 + `cacheComponents`의 선행 조건). `revalidate`는 켜지 않았다 — 목록은 force-dynamic이 "요청마다 최신"을 선언했고 상세는 029가 이미 BE에서 TTL 60s로 캐시해 FE에 얹으면 최악 지연이 두 배가 된다
  - ✅ **측정 하네스 신규 4종**: `01c-large-venue.sql`(10,000석), `stream-markers.spec.ts`(HTML 스트림 마커 도착 시각), `dom-memory.spec.ts`(CDP로 DOM 노드·heap·누적 렌더), `parse-bench.mjs`(`JSON.parse` 격리). `payload-bytes.spec.ts`의 무결성 검사는 path 합치기에 대비해 수정했다 — rect만 세면 **검사가 개선 자체를 오염으로 오판한다**. `lighthouse-runs.mjs`에 `LH_EXTRA_FLAGS` 추가
  - ✅ **귀속을 가르려고 군을 넷으로 쟀다**(before / 5개전부 / 폰트만되돌림 / 최종). 폰트를 분리하지 않았으면 **"path 합치기가 Layout을 79% 줄였다"로 적을 뻔했다** — 실제로는 폰트 11개가 각각 리플로우를 일으키던 몫이었다
  - ✅ `docs/improvements/009_2-seatmap-structure.md` 작성, 009_1 정정 1곳(3.7절 번들 귀속), ROADMAP 전제 정정 2곳(Suspense 백지 / getMe 위치). `v0.5.0-performance` 태그 생성 (PR #29 머지 후 main의 `6b45877`에 주석 태그로 생성. 커밋 10개를 머지 커밋으로 보존했다 — `009_2` 문서가 기각한 폰트 변경을 커밋 SHA로 참조하므로 squash하면 재현 방법이 깨진다)
  - 알려진 한계: **교차점을 모른다**(RecalcStyle 부호가 2,000~10,000석 사이 어딘가에서 바뀐다). **2,000석에서는 FCP +48ms / LCP +45ms 회귀**이고 메커니즘을 규명하지 않았다. **10,000석 FCP(327ms)가 2,000석(540ms)보다 빠른 이유를 모른다** — 문서가 5배 큰데 첫 페인트가 이르다. 무스로틀 FCP 이중분포가 단일 폰트로 10,000석에서는 풀리고 2,000석에서는 안 풀렸다. **heap을 "좌석당"으로 비교하면 틀린다**(기저가 약 3,883KB, 증분은 좌석당 약 435B). 자리표시자 높이는 뷰포트 기준 근사라 실제 좌석맵이 더 짧은 공연장에서는 CLS가 다시 생길 수 있다. 모바일·재방문자 미측정

### Phase 9: 대기열 시스템

- ✅ **Task 031: [BE] Redis 대기열**
  - ✅ **ZSET 두 개가 전부다** — `tikkit:queue:{scheduleId}:waiting`(score = 진입 시각)과 `:active`(score = **입장권 만료 시각**). 중괄호는 Redis Cluster hash tag 리터럴이고, 지금은 standalone이라 무해하지만 나중에 키 포맷을 바꾸면 운영 중에 줄 서 있는 사람들의 큐가 사라진다
  - ✅ **"활성 세트 + TTL 토큰"에서 벗어났다.** 그대로 만들면 토큰 키가 만료돼도 SET에 멤버가 영구히 남아 `SCARD`가 틀리고, 유령을 치우려면 SET 전체를 순회해야 한다(O(N) 왕복). score에 만료 시각을 넣으면 검증이 `ZSCORE` 왕복 1회 / 일괄 수거가 `ZREMRANGEBYSCORE` 명령 1개 / 연장이 `ZADD GT` 명령 1개가 된다. **부수 효과로 만료 테스트가 `sleep` 없이 결정적이 됐다** — 과거 score를 직접 심으면 된다
  - ✅ **별도 토큰을 발급하지 않았다.** 입장권의 실체는 `active` ZSET의 `(memberId → 만료시각)` 엔트리이고 전송되는 비밀이 없다. 랜덤 UUID는 같은 인증 세션에 내려줄 수밖에 없어 **보안 이득이 0**이다(새 비밀을 만드는 게 아니라 같은 비밀을 두 번 적는 것). 대신 **대기열을 켠 회차는 로그인 필수**가 됐다 — Task 022의 `permitAll`을 회차 단위로 뒤집는 셈이고, `SecurityConfig`를 고치지 않고 인터셉터가 401을 던져 플래그가 꺼진 회차의 공개 조회는 보존했다
  - ✅ **Lua를 쓰지 않았다. 명령 두 개의 순서로 닫았다** — `ZADD active`(먼저) → `ZREM waiting`(나중). 사이에서 죽으면 사용자가 양쪽에 남고, 상태 조회가 `active`를 먼저 보므로 **정상 입장**한다. 중복은 다음 틱이 치운다(`ZADD`가 score만 갱신하고 `ZREM`이 대기를 지우므로 후보마다 `ZSCORE`를 돌려 걸러낼 필요가 없다 — 50명이면 왕복 50회다). 거꾸로 하면 양쪽에서 사라져 **선두 몇 명이 조용히 맨 뒤로 간다.** 멱등성 방향을 at-most-once가 아니라 **at-least-once로 고정**한 것이고, Lua는 "프로세스가 죽는 것"은 못 막고 비원자 창을 0.3ms → 0으로 줄일 뿐이다. 재판단 조건: ShedLock을 떼고 회차별 샤딩으로 갈 때, 또는 요청 경로에서 활성 인원을 늘려야 할 때(그건 ShedLock이 못 막는다)
  - ✅ 진입은 **`ZADD NX` 하나로 닫힌다** — 더블클릭·폴링 중복·새로고침이 순번을 밀지 않고 재진입으로 앞자리를 살 수도 없다. 요청 경로는 ShedLock이 없으니 이 멱등성이 Lua 없이 성립해야 했다
  - ✅ **보호는 인터셉터 + 서비스 가드 하이브리드다.** `POST /reservations`의 `scheduleId`가 **요청 본문**에 있어서 인터셉터가 읽으면 `@RequestBody` 역직렬화가 빈 스트림을 만난다 — 로드맵이 적은 "인터셉터 적용"은 경로에 회차 id가 있는 좌석 조회에만 성립한다. AOP로 숨기지 않은 이유는 "왜 403인지" 추적이 안 되고 Task 020이 지운 `@DistributedLock`을 되살리는 셈이기 때문이다. **결제·취소는 보호하지 않는다** — 이미 좌석을 잡은 사람이 돈을 낼 수도 좌석을 돌려받을 수도 없는 상태가 되면 안 된다
  - ✅ **꺼진 상태를 "빈이 없음"이 아니라 구현으로 표현했다.** `tikkit.queue.enabled=false`(기본)면 `DisabledQueueGateway`가 올라가 전부 통과시킨다 — `cache.type=none`이 `NoOpCacheManager`를 올리는 것과 같은 구조이고, 그래서 호출부를 지우지 않고도 기능이 완전히 꺼지며 **기존 테스트 176개가 Redis 없이 그대로 돈다**. 조건은 `QueueStrategyConfig`의 중첩 설정 한 곳에만 붙는다(빈마다 흩으면 "줄은 서는데 아무도 입장하지 않는" 조합이 조용히 생긴다 — 실제로 `@Repository` 하나를 남겨뒀다가 테스트 176개가 한꺼번에 빨개졌고 증상이 "대기열과 무관한 리포지토리 테스트 전부 실패"였다)
  - ✅ **`queue_enabled`를 요청마다 DB에서 읽지 않는다** — `QueueFlagRegistry`의 `volatile` 스냅샷 + 조회 시 자기 갱신(TTL 10초). Spring Cache를 못 쓴 이유는 `cache.type` 기본값이 `none`이라 캐시를 끈 환경에서 좌석 조회마다 DB로 가기 때문이고, `@Scheduled`를 못 쓴 이유는 `SchedulingConfig`가 `@Profile("!test")`라 **테스트에서 스냅샷이 영원히 비어 대기열 테스트 전체가 "플래그 off"로 조용히 통과하기** 때문이다. `QueryCounter`로 "반복 조회 100회에 쿼리 0건"을 고정했다
  - ✅ **폴링 간격을 백엔드가 지시한다** (`pollAfterSeconds`: rank<100 → 2초 / <1,000 → 5초 / 그 외 10초). 10만 명이 2초 고정으로 폴링하면 상태 API 하나에 **50,000 RPS**가 몰려서 좌석 API를 막아 번 여유를 상태 API가 전부 먹는다. ETA는 `rank / 초당 입장 인원`이고 **공칭 속도 기준이라 실제보다 낙관적이다**(`fixedDelay`라 실제 주기가 설정값보다 길다) — 관측 속도로 계산하지 않은 이유는 분모가 흔들리면 ETA의 단조 비증가가 깨져 진행바가 되감기기 때문이다
  - ✅ `V8__add_queue_enabled_to_schedules.sql`(인덱스 없음 — 읽는 쿼리가 10초에 1회이고 회차 테이블 전체 스캔이 ms 단위다), `ErrorCode` 2개(403), 지표 3종(`tikkit.queue.admitted`/`pass.checks`/`waiting.size`, 기동 시 0 등록). **Gauge는 Redis를 왕복하지 않는다** — 스케줄러가 틱마다 `AtomicLong`에 담고 Gauge는 그 값만 읽는다(게이트웨이와 스케줄러가 각자 `new QueueMetrics(...)`를 하면 Gauge가 읽는 카운터와 갱신하는 카운터가 달라져 **대기 인원이 영원히 0**이 된다)
  - ✅ **Redisson을 제거했다** — 주입처가 0개였고 필요한 연산이 전부 `opsForZSet()`에 있다. 003 8절이 "Redis를 락이 아니라 원래 잘하는 일로 쓴다"고 예고한 그대로 Lettuce + `StringRedisTemplate`으로 갔다
  - ✅ 테스트 20개 신규(순서·입장·만료·인터셉터·플래그), `AbstractRedisQueueContainerTest` 신규. **대기열만 Redis로 켠 컨텍스트**라 `RedisConnectionConfig`의 OR 조건에 추가한 세 번째 가지도 같이 증명된다
  - ✅ **범위 밖 발견**: ① **연결 팩토리가 생기면 Spring Session이 따라 켜진다.** `tikkit.auth.session-store=memory`여도 `@ConditionalOnBean(RedisConnectionFactory)`이 충족되어 세션이 Redis로 가고, 그러면 `SessionRepositoryFilter`가 `MockMvc`의 `.session(mockHttpSession)`을 **무시**해서 `SecurityContext`가 복원되지 않는다. 증상이 **403이 아니라 401**이라 "대기열 코드가 틀렸나"를 먼저 의심하게 된다. 008 8절이 "캐시만 켠다가 세션까지 켠다"로 적어둔 구조가 대기열이 세 번째 트리거가 되면서 처음 눈에 보였다 — 테스트에서는 `SessionDataRedisAutoConfiguration`을 제외해 피했고, **`session-store` 토글이 사실상 "Redis 팩토리를 만들 이유 하나"일 뿐이라는 점은 그대로 남아 있다** ② **`CacheEvictionTest`의 `DELETE FROM shedlock`은 불필요했다.** "없으면 두 번째 테스트부터 배치가 조용히 건너뛰어진다"고 적혀 있었지만 `@EnableSchedulerLock`이 `@Profile("!test")`인 설정에만 있어 test 프로필에는 어드바이저 자체가 없다. 지우고 통과를 확인했다(검증 없이 적힌 안심 문장이 테스트 코드에 남아 따라 쓰이는 걸 막았다) ③ **Spring Data는 `find`와 `By` 사이를 주제어로 보고 버린다** — `findIdsByQueueEnabledTrue()`의 "Ids"가 프로젝션으로 해석되지 않아 엔티티를 select한 뒤 `Long` 변환에서 깨진다. 컴파일은 통과하니 기동할 때까지 모른다
  - 알려진 한계: **입장권에 절대 상한이 없다**(활동하면 무한 연장 — 상한을 두려면 `admittedAt`용 ZSET이 하나 더 필요하다. 한 score에 두 시각을 담을 수 없다). **유령을 치우지 않는다**(브라우저를 닫으면 `waiting`에 남아 그 틱의 슬롯을 낭비하고 ETA가 과대 추정된다 — 사용자에게는 "생각보다 빨리 들어왔다"라 안전한 방향이다. 용량 기반으로 바꾸면 `seen` ZSET이 필수가 되고 임계값은 **90초 이상**이어야 한다. 브라우저가 백그라운드 탭 타이머를 1분에 1회로 스로틀한다). **`allkeys-lru`가 대기열 키도 축출 대상이다** — 축출되면 멤버가 아니라 **큐 하나가 통째로** 날아가고 전원이 꼬리로 재진입해 순서가 리셋된다(대기 10만 명이 약 10MB라 상한 512MB에서 일어나지 않을 계산이지만 Task 033에서 `evicted_keys`를 확인한다). **활성 인원 상한이 없어서 입장 속도가 좌석 API 처리량보다 크면 대기열이 지연을 늦추기만 하고 막지 못한다.** `admit-batch-size=50`과 폴링 백오프 숫자에 **근거가 없다**(Task 033이 정한다). **동시 진입의 상대 순서는 보장하지 않는다**(score가 epoch millis라 같은 ms면 멤버 문자열 사전순 — `"10" < "9"`). **`admit-interval`과 `@SchedulerLock(lockAtLeastFor)`가 수동으로 묶여 있다**(어노테이션에 계산식을 쓸 수 없다 — 안 맞추면 "설정은 5초인데 실제로는 1초마다 입장"이 되고 증상이 "예상 대기시간이 계속 틀리다"로만 나타난다). **부하에서 재지 않았다** — 전부 Task 033 몫이다
- ✅ **Task 032: [FE] 대기열 화면** (`feature/queue-screen`)
  - ✅ `/queue/[scheduleId]`: 순번, 예상 대기시간, 그라디언트 진행바. 나머지 값(`performanceId`/`gradeId`/`quantity`)은 쿼리로 받는다 — 목적지 URL을 통째로 넘기면 오픈 리다이렉트 표면이 새로 생기는데, 네 값이 전부 양의 정수라 검증이 완전하고 경로는 서버가 고정 템플릿에 정수만 끼워 만든다
  - ✅ **폴링 간격은 응답의 `pollAfterSeconds`를 따른다** (Task 031에서 계약 확정). 처음 "2초 고정"으로 적었는데 10만 명이면 상태 API 하나에 50,000 RPS가 몰려서, 백엔드가 순번별로 지시하는 방식(2/5/10초)으로 바꿨다 — 값은 Task 033이 측정해 고치고 프론트는 그대로 둔다. `setInterval`이 아니라 **`setTimeout` 체인**인 이유가 이것이다(간격이 매 응답마다 바뀐다). 검증 때 앞에 150명을 심어 **2초가 아니라 5초 구간을 실제로 밟았다** — 응답값을 쓴다는 유일한 실증이다
  - ✅ **진입은 서버 컴포넌트가 렌더 중에 호출한다.** 렌더 중 부작용이지만 `ZADD NX`라 멱등이고, 그 대가로 `DISABLED`/`ADMITTED`면 픽셀 하나 그리기 전에 좌석으로 redirect한다(깜빡임 없음). 클라이언트에서 부르면 대기열 아닌 회차에서도 대기 화면이 한 번 깜빡인다
  - ✅ `queue_enabled=false`인 회차도 같은 진입 API를 호출하고 `DISABLED`를 받는다 → **분기 하나로 끝난다**
  - ❌ **"좌석 선택" CTA를 `/queue`로 바꾸지 않았다 — 로드맵 문구와 갈리는 지점이다.** 대기열 API가 전부 `authenticated()`라 CTA를 돌리면 **대기열을 켜지 않은 회차에서도 로그인이 강제되고**, Task 022가 "자리 보고 가입하는 흐름이 깨진다"며 좌석 조회를 일부러 `permitAll`로 연 결정이 죽는다. 그래서 **가드가 던지는 401/403을 받을 때만 대기열로 우회한다** — 403이 에러가 아니라 "이쪽으로 가라"는 신호가 된다. 덕분에 `ticket-selector.tsx`는 **0줄 수정**이고 정상 회차는 왕복이 늘지 않는다. 대가는 대기열 회차에서 좌석 요청 한 번이 헛도는 것인데 인터셉터가 컨트롤러·DB 전에 막아서 싸다
  - ✅ **`QUEUE_PASS_REQUIRED`와 `QUEUE_PASS_EXPIRED`에 다른 행동을 줘서 리다이렉트 루프를 구조적으로 막았다.** 전자(줄 선 적 없음)는 대기열로 보내고, 후자(입장권 만료)는 **보내지 않고 좌석 영역에 막다른 안내를 그린다.** 둘을 같이 보내면 "입장 → 만료 → 대기열 → 입장"이 돌 수 있는데, 다르게 다루면 왕복 2회에서 반드시 멈춘다. 다만 **선점 Server Action에서는 둘 다 대기열로 보낸다** — 사용자가 방금 버튼을 누른 결과라 막다른 화면보다 다시 줄 세우는 게 맞다
  - ✅ **`getScheduleSeats`를 `apiFetchPublic` → `apiFetch`로 옮겼다. 이게 없으면 대기열을 켜는 순간 입장권을 받은 사람도 401이다.** Task 030_2가 정리한 "좌석은 공개 조회"라는 전제가 Task 031의 인터셉터 때문에 이미 깨져 있었고, 화면을 만들지 않으면 드러나지 않는 종류의 문제였다. 030_2의 결정은 "전부 공개로 두자"가 아니라 **"호출부가 어느 쪽인지 선언하게 하자"** 였으므로 선언을 바꾸는 게 그 장치의 정상 사용이다. 잃는 캐시도 없다(좌석은 `status`가 실시간이라 030_2가 이미 캐시 대상에서 제외했다). **파일 상단의 "네 함수는 전부 apiFetchPublic을 쓴다" 주석도 같이 고쳤다** — 안 고치면 검증 없이 신뢰되는 거짓 주석이 남는다(031이 `CacheEvictionTest`에서 같은 것을 발견해 지웠다)
  - ✅ **route handler를 `app/api/` 밑에 두지 않았다.** `nginx.conf`가 `location /api/`로 접두사 전체를 백엔드로 보내므로, `app/api/`에 두면 `next dev`에서는 돌고 **prod에서는 Spring이 404를 준다** — 증상이 "로컬에선 됐는데 배포하면 대기열만 안 움직인다"라서 nginx를 의심하기까지 오래 걸린다. 그래서 대기열 페이지의 자식 세그먼트(`/queue/[scheduleId]/status`)에 뒀다. nginx는 고치지 않았다(Task 034 소관이고, 그 파일이 접두사 단위 관리를 선언해 뒀다)
  - ✅ **폴링을 Server Action으로 하지 않았다.** 응답에 현재 라우트의 RSC 페이로드가 딸려 와 폴링마다 페이지 트리가 다시 그려지고, Next가 액션을 직렬화해 한 번에 하나만 보내며, `Cache-Control`을 직접 못 건다. 이 레포에서 Server Action은 "사용자 의도 + 상태 변경 + redirect"용이라 폴링은 셋 다 아니다. `no-store`는 **prod에 nginx가 앞에 있어서** 필수다 — 폴링 GET이 캐시되면 순번이 얼어붙고 증상은 "입장이 안 된다"로만 보인다
  - ❌ **이탈을 `sendBeacon`으로 자동 통보하지 않았다 — 로드맵 문구와 갈리는 두 번째 지점이다.** `pagehide`는 탭을 닫을 때도 **새로고침할 때도** 뜨고 둘을 구분할 방법이 없다. 거기서 `DELETE`를 보내면 순서가 "이탈 → 재진입"이 되고 재진입은 `ZADD NX`라 **꼬리로 간다** — 새로고침 한 번에 순번이 맨 뒤로 가는데 화면에는 큰 숫자만 보여서 원인을 못 찾는다. BE Javadoc도 "이탈 경고에서 **확인을 눌렀을 때** 호출한다"로 적고 있었다. 그래서 상시 문구 + "대기열에서 나가기" 버튼(`alert-dialog`) + Server Action으로 갔고, **`beforeunload` 확인창도 넣지 않았다**(Chrome이 문구를 무시하고, 새로고침에도 떠서 없는 문제를 경고한다). 탭을 그냥 닫으면 유령이 남는데 BE가 이미 "사용자에게 안전한 방향"으로 수용한 한계다
  - ✅ **effect cleanup에서 이탈을 보내지 않는다.** StrictMode가 마운트 시 effect를 한 번 접었다 펴므로, 거기 두면 **개발 환경에서 사용자가 조용히 줄에서 빠진다** — 진입은 서버 렌더에서 끝났으니 다시 줄 서지도 않고 증상은 "dev에서만 순번이 안 보임" 하나뿐이다
  - ✅ **진행바 분모를 `totalWaiting`이 아니라 처음 받은 내 순번으로 고정했다.** `totalWaiting`을 쓰면 뒤에 사람이 늘 때 바가 되감기는데, BE가 ETA의 단조 비증가를 지키려고 관측 속도 대신 공칭 속도를 쓴 판단과 어긋난다. `position`은 FIFO + `ZADD NX` 때문에 단조 비증가가 보장된 유일한 값이다. 하한 5%(막 줄 선 사람에게도 바가 보인다) / 상한 95%(대기 중 100%는 "다 찼는데 안 들어감"으로 읽힌다), 1번은 문구로 따로 말한다. 꼬리로 재진입하면 분모를 다시 잡는다
  - ✅ **ETA를 로컬에서 1초씩 깎지 않는다.** 매 폴링마다 재계산되는 추정치라 0에 닿은 뒤 양수가 오면 숫자가 올라가 보이고 **BE가 지킨 단조성을 프론트가 깨뜨린다.** `countdown-timer.tsx`의 deadline 고정 패턴은 "서버가 준 남은 ms"라는 확정 마감이 있을 때만 성립한다. 대신 분 단위로 뭉갠다("약 1시간 15분")
  - ✅ **`proxy.ts` matcher는 `/queue/:scheduleId`(한 세그먼트)다.** `:path*`로 하면 자식 세그먼트인 폴링 route handler까지 걸려서 **401 JSON 대신 로그인 HTML로 307**이 나가고, 클라이언트는 JSON 파싱에 실패하며 원인이 안 보인다
  - ✅ 검증: `tsc --noEmit`·`lint`·`build` 통과, **기존 e2e 3개 통과**(처음 실패한 건 시드 노화였다 — 시드가 `now() + interval 'N days'`로 삽입 시점 기준 날짜를 잡아서, 14일 묵은 로컬 볼륨에서는 공연이 24시간 안으로 들어와 "공연 24시간 전까지만 취소" 규칙에 정상적으로 걸렸다. CI는 매번 새로 시드하니 통과한다)
  - ✅ **브라우저 시나리오 9개를 헤드리스로 확인했다**(일회성 스크립트, 레포에 커밋하지 않았다): 좌석 URL 직접 접근 → 대기열 우회 / 대기 화면 렌더(순번 41) / **새로고침 후 순번 유지**(41 → 41 — `sendBeacon` 자동 이탈을 뺀 이유의 실측이다. 보냈다면 꼬리로 밀렸다) / 뒤에 2명 추가해도 진행바 안 줄어듦(5% → 5%) / 앞 20명 제거 시 순번 감소·진행바 증가(43 → 22, 5% → 50% — 분모가 마운트 시점 순번으로 고정돼 있다는 증거다) / **입장 후 좌석 페이지 자동 이동** / 입장 후 좌석 조회 성공 / **모바일 360px 가로 스크롤 없음** / 나가기 → 공연 상세 이동 + Redis 엔트리 삭제
  - ✅ 그 외 수동 확인: 비로그인 401 → 로그인 유도, 로그인 403 → 대기 화면, ETA 약 1시간 15분, **폴링 5초 구간**(기본 2초가 아니다 — 앞에 150명을 심어 응답값을 따른다는 것을 실증했다), `queue_enabled=false` 회차 좌석 직행, 비로그인 공개 조회 복귀, **입장권 만료 시 리다이렉트 없이 막다른 안내**(`QUEUE_PASS_EXPIRED`에서 대기열로 보내지 않는다는 설계가 루프를 막는다는 확인)
  - **Playwright 스펙은 추가하지 않았다** — 대기열을 켠 BE + Redis + 느린 입장 설정이 필요해서 CI에서 돌 수 없고, 억지로 넣으면 "켜지지 않은 환경에서 조용히 통과하는 테스트"가 된다(031이 `QueueFlagRegistry`에서 같은 함정을 겪었다). 수치 검증은 Task 033이 k6로 한다
  - 알려진 한계: **좌석 → 대기열 전환이 soft redirect다** — `SeatArea`가 Suspense 안이라 Next가 meta 태그를 심어 클라이언트가 이동시키고(스트리밍 컨텍스트의 문서화된 동작), 그래서 좌석 화면 셸이 한순간 보인 뒤 넘어간다. 미리 await하면 009_2가 측정한 "h1 도착 17.41 → 4.44ms"가 되돌아가므로 깜빡임을 받고 스트리밍을 지켰다. **SPA 내부 이동 시 유령이 남는다**(자동 통보를 없앤 대가). **입장권 만료 카운트다운을 만들지 않았다**(`passExpiresAt` 미사용) — 좌석 페이지에 이미 선점 10분 타이머가 있어 시계를 둘 보여주면 어느 쪽이 끝나는지 혼란해진다. **검증이 전부 단일 사용자다** — 동시 진입의 상대 순서, 여러 명이 같은 틱에 입장할 때의 화면 거동은 Task 033의 k6 몫이다. **폴링 10초 구간(rank ≥ 1,000)은 밟지 않았다**(2초·5초만 확인). **대기 중 네트워크 단절 시 백오프 안내**는 코드로만 있고 실제로 끊어보지 않았다
  - **범위 밖 발견**: **`npm run dev`가 PostCSS 워커를 무한 누수하고 있었다.** 모노레포 루트의 의존성 없는 빈 `package-lock.json`(init 커밋에 섞여 들어간 것) 때문에 Next가 워크스페이스 루트를 모노레포 루트로 추정하고, 거기엔 `node_modules`가 없어 **CSS 컴파일이 요청마다 실패한다.** 그런데 Next dev는 CSS 컴파일마다 node 워커를 띄우고 실패한 워커를 회수하지 않아서, 요청·HMR 틱마다 쌓인다 — 워커 **11,193개 생성 / 4,847개 생존 / 13.8GB**, `.next/dev`가 삭제된 파일 핸들로 **132GB**를 잠그고, bash `fork` 실패와 IDE 강제 종료까지 갔다. **빌드는 정상이라 증상이 dev에서만 나온다.** `outputFileTracingRoot`가 빌드 산출물 레이아웃에 대해 같은 추정을 이미 막아뒀는데 **dev 번들러 쪽(`turbopack.root`)만 빠져 있었다.** 루트 lockfile 삭제 + `.gitignore` 추가 + `turbopack.root` 고정으로 막았다(파일 삭제에 의존하지 않는 이유: 누가 루트에서 npm 명령을 한 번만 돌리면 다시 생긴다). 이 수정은 Task 032와 무관하므로 커밋을 분리했다
- ✅ **Task 033: [공통] 오픈런 부하 검증 및 최종 회고**
  - ✅ **포화점을 먼저 닫았다** — 006이 남긴 "포화점을 찾지 않았다"가 전제였다. `R_sat` = 도착률 **120/s (309 req/s, 122 MB/s, p95 64.5ms)**. 140/s에서 p95가 214.3ms로 꺾이며 SLO를 넘고, 처리량은 160 이상에서 **365~370 req/s**로 평탄하다. 진짜 벽은 CPU다(240/s에서 16코어 중 11.4개)
  - ✅ **군을 일곱으로 갈랐다** — "대기열 유무"만 가르면 ① 꺼진 회차의 회귀 ② 배치 크기 의존성 ③ 좌석 API 부하가 상태 API 부하로 변환되는 것 ④ 큐 내부 비용의 규모 의존성을 놓친다. **A ≈ B ≈ C = 309.44 / 308.8 / 308.9 req/s (±0.2%, A군 3회 범위 0.08%)** — Task 031의 "꺼진 상태는 예전과 같다"가 처음 측정됐고, 입장권 검증(`ZSCORE` + `ZADD GT`) 비용도 측정 한계 아래다
  - ✅ **`admit-batch-size`의 유효 구간 [6.7, 84]명/s를 도출했다** — 상한은 `R_sat × 안전계수 0.7`, 하한은 재고(10,000석 / 2.5석 / 600초). **이 값이 전역이 아니라 회차당**이라는 것도 드러났다: 켠 회차가 2개면 100명/s가 되어 상한을 넘는다. 기본값 50은 유지하되 근거와 전제를 `application.yml`에 적었다
  - ✅ **폴링 백오프에 4번째 구간(rank ≥ 10,000 → 60초)을 넣었다** — 3구간(2/5/10초)은 10만 명에서 10,130 RPS를 요구하는데 실측 `R_poll`이 **2,986 req/s**였다(p95 3.63ms, 서버 CPU 43%). 3구간이 지탱하는 한계는 **약 28,000명**으로 목표의 1/3이다. 60초면 요구가 2,630 RPS로 떨어진다. 30초(4,130 RPS)가 체감에 낫지만 증명되지 않은 영역이라 **증명된 쪽을 골랐다**. **FE는 한 줄도 고치지 않았다** — 032가 값을 계약(`pollAfterSeconds`)으로 내려주고 `setTimeout` 체인으로 구현한 설계가 여기서 처음 효과를 봤다
  - ✅ **거절이 DB 커넥션을 요청당 1개 먹고 있었다** — 가드가 `@Transactional` 메서드의 첫 줄이라 SQL을 한 줄도 돌리지 않는 403이 Hikari 커넥션을 **1.0114개/요청** 가져갔다. DB를 지키려고 만든 장치가 지키려던 자원을 소모한 셈이다. 가드를 컨트롤러로 올려 **0.0173개(-98.3%)** 로 내렸다. 로드맵의 "DB 커넥션" 항목에서 가장 날카로운 결과다
  - ✅ **008이 넘긴 캐시 재판정** — 포화까지 올려 보니 `browse` 한계 처리량이 **7,400 → 10,150 req/s (+37%)**, 치우친 분포는 +41%다. 008의 "채택 근거는 지연이 아니라 용량"이 증명됐다. **008이 용량을 재지 못한 구조적 이유**도 드러났다 — 하네스의 think time이 iteration 3.74초 중 99.8%였고, VU 600에서도 p95가 2ms였다(재고 있던 것이 서버가 아니라 `sleep`이다). `THINK_MIN`/`THINK_MAX`를 넣어 끌 수 있게 했다
  - ✅ **Redis 메모리·축출** — 대기 10만 명 = **9,689,219 B (9.24 MiB)**, 96.9 B/멤버, `skiplist`. 031의 "약 10MB" 추산이 맞았고 512MB에서 `evicted_keys` 0이다. 축출을 재현해 보니 **2단계**였다: 16mb에서 **세션 9,999개가 죽고 큐는 살아남고**(폴링이 큐 키를 계속 만져서 `allkeys-lru`가 핫한 큐를 지킨다 → 줄 선 사람이 다음 폴링에서 401), 6mb에서 그때 큐가 통째로 사라진다. **031의 한계 항목에는 1단계 경로가 없었다**
  - ✅ **깊이 의존성을 확인해 외삽을 정당화했다** — 대기 1,000 vs 100,000에서 상태 p95 비율이 **0.95~1.03**이다. `ZRANK`가 O(log N)이니 이론상 1.66배여야 하는데 안 보인다 → 순번 계산이 요청 비용에서 차지하는 몫이 측정 한계 아래다. 032가 미검증으로 남긴 **폴링 10초 구간(rank ≥ 1,000)도 처음 밟았다**
  - ❌ **커넥션 풀 상향** — 140/s에서 pending이 생기는 걸 보고 "풀이 벽"이라고 적었는데 **틀렸다.** 10/20/30 모두 약 365 req/s에서 똑같이 막히고, 140/s에서는 올린 쪽이 **더 느렸다**(p95 214 → 280 → 311ms). pending은 원인이 아니라 증상이었다. 반증된 뒤에 `.env.example`·compose 주석까지 같이 고쳤다
  - ❌ **BE 좌석 응답 축소**(009_2가 넘긴 재판정) — 짧은 키 + 사전으로 바이트를 **49.4% 줄였는데 처리량은 1~7%**만 올랐다. 저부하 귀속으로 보면 좌석맵 25.50ms 중 **조립(DB+DTO)이 17.46ms(68%)** 이고 쓰기가 8.05ms인데, 바이트를 반으로 줄여도 쓰기가 **9.2%만** 줄었다(쓰기의 82%가 고정비다). 배열 튜플(5.2배 축소)로 외삽해도 4~5%다. **구현을 지웠다** — 009_1의 gzip·031의 Redisson과 같은 기준이다. 그리고 이건 **008의 처방도 정정한다**: "해법은 캐싱이 아니라 응답 크기"였는데 응답 크기도 아니었다
  - **범위 밖 발견**: `CacheQueryCountTest` 3개가 "넣고 바로 꺼내는" 패턴으로 **간헐적으로 깨지고 있었다.** 베이스 클래스가 "캐시 쓰기가 간헐적으로 늦게 보인다"고 적고 `awaitCached()`를 제공하는데 그 세 테스트만 안 쓰고 있었다. 이분 탐색으로 "내 변경 탓이 아니다"를 먼저 가른 뒤 고쳤다(전체 스위트 3회 연속 통과)
  - ✅ `docs/improvements/010-waiting-queue.md`(Phase 9 전체 기록 + 031·032 설계 부록)와 인덱스 `docs/improvements/README.md` 작성, README 포트폴리오 섹션 마무리, `v1.0.0` 태그

### Phase 11: 성능 잔여 과제

`docs/improvements/` 15개 문서가 "한계·다음 단계"로 남긴 항목을 영향도 순으로 모았다. **전부 근거와 수치가 이미 있고 조치만 안 된 것들이다** — 새로 발굴한 문제가 아니다. 기존 `후속 Task 후보` 5개도 여기로 흡수했다.

이 Phase의 특징은 **"안 고친 것"보다 "못 잰 것"이 많다**는 점이다. Task 035만 코드 결함이고 나머지는 측정 범위·장비·조건의 한계다. 기각을 수치로 뒷받침해 온 대가로, 그 기각의 **유효 범위가 좁다**는 게 드러난 상태다.

**순서**: 035 → 040 → 042_1 → 036 → 037 → 039 → 041 → 042. **Task 038은 전용 부하 노드가 선행이라 순서 밖**이고, 036·037·039·040은 그 038이 안 닫힌 하네스로 측정한다는 한계를 안고 간다.

**번호순이 아니다.** Phase 7이 026_1을 맨 앞에 둔 것과 같은 이유로, **남의 전제를 바꾸는 작업과 회귀를 잡는 장치를 앞으로 당겼다.**

- **035가 1순위인 이유**: 가장 큰 레버이면서 **부하 노드 없이 Playwright/Lighthouse로 닫을 수 있는 유일한 큰 항목**이다. BE 작업과 완전히 독립이다
- **040을 036 앞으로**: 040이 올리는 `shared_buffers`가 036이 공략하는 좌석맵 조립 비용의 **DB 조회 부분을 빠르게 만든다** — 036을 먼저 하면 캐시 이득을 재서 채택한 뒤 040이 그 결론을 뒤집는다. 040은 설정 한 줄이라 가장 싸다
- **042_1을 036 앞으로**: 뒤따르는 036·044·045가 전부 캐시를 고치는데 **쿼리 카운트 회귀를 잡는 CI 게이트가 없는 상태**였다. 042에서 그 항목만 떼어냈다

035를 맨 앞에 두는 이유는 가장 큰 레버이면서 **부하 노드 없이 Playwright/Lighthouse로 닫을 수 있는 유일한 큰 항목**이기 때문이다.

- **Task 035: [FE] 좌석 선택 레이어 오버레이** (`feature/seat-selection-overlay`)
  - **10,000석에서 좌석 클릭당 32.5ms.** `components/performance/seat-map.tsx`의 `selectedSeatIds.includes(seat.id)`가 좌석마다 O(n)이고, 클릭 한 번에 선택 가능 좌석 약 4,000개가 다시 그려진다. 선택 표시를 좌석 `<rect>` 속성에서 떼어 **오버레이 ≤4개**(상한 4매)로 분리하면 O(1)이 된다
  - **009 4.8절이 `memo` + `Set` 전환을 "이 규모에서 이득 없음"으로 기각했는데 그 "이 규모"가 2,000석·상한 4매였다** — 기각의 유효 범위가 Task 030_2에서 깨졌다. 009_2가 "030_2가 남기는 가장 큰 레버"로 적은 항목이다
  - 측정은 기존 하네스 재사용: `tikkit-front/e2e/perf/seat-interaction.spec.ts` + `load-test/sql/01c-large-venue.sql`(10,000석 시드). **부하 생성기가 필요 없어 Task 038의 한계를 받지 않는 유일한 Task다**
  - 근거: `docs/improvements/009_2-seatmap-structure.md`

- **Task 040: [BE] `shared_buffers`와 인덱스 기각 재판정** (`feature/shared-buffers-retrial`)
  - **Task 036보다 앞에 둔 이유**: 036이 공략하는 좌석맵 조립 68%의 구성이 **DB 조회 + DTO 조립**이고, `shared_buffers`를 올리면 그 DB 조회 쪽이 빨라져 **036의 before 값이 바뀐다.** 036을 먼저 하면 캐시 이득을 재서 채택한 뒤에 040이 그 결론을 뒤집을 수 있다. 040은 설정 한 줄이라 가장 싸다 — Phase 7이 번호순을 버리고 026_1을 맨 앞에 둔 것과 같은 이유다("먼저 만들면 같은 자리를 두 번 손대고 측정치도 다시 뽑아야 한다")
  - **`shared_buffers`가 기본값 128MB다.** 007이 직접 **"이 값을 올리는 것이 인덱스보다 효과가 클 수 있는데 측정하지 않았다"** 고 적었다. 인덱스 후보 7종을 전부 기각하면서 정작 가장 유력한 후보를 안 쟀다
  - **인덱스 기각이 "지금 규모에서"의 조건부다.** 커버링 인덱스는 버퍼를 2,130 → 354(**-83%**)로 줄였는데 실제 페이지 읽기가 196 vs 198로 같았던 것이 기각 근거였고, **그 등가는 작업 집합이 메모리에 상주할 때만 성립한다.** 회차 수가 늘어 `shared_buffers`를 넘기는 구간을 측정하지 않았다 — 007 4.6절의 결론이 거기서 바뀔 수 있다
  - **`uk_members_email` 함수 인덱스가 쓰이지 않는다**(있을 때와 없을 때 차이 0.039ms) — 제거를 검토한다
  - 인덱스를 추가하게 되면 **`V7` 자리를 쓴다** — Task 028이 인덱스용으로 예약했다가 근거가 안 나와 비워뒀고, `V8`에 그 사실이 기록돼 있다. **`CONCURRENTLY` 없이 걸면 ACCESS EXCLUSIVE 락이 3.07초** 걸린다(007이 밟은 함정)
  - 근거: `docs/improvements/007-query-optimization.md` 남은 과제·미배정

- **Task 042_1: [BE] 쿼리 카운트 CI 게이트** (`feature/query-count-ci-gate`)
  - **Task 042에서 떼어내 캐시 작업 앞으로 옮겼다.** 뒤따르는 036·044·045가 전부 캐시를 대대적으로 고치는데, **회귀를 잡는 장치가 없는 상태로 세 번 고치는 구조**였다
  - 쿼리 카운트 테스트가 로컬 실행에만 의존한다 — 007·008이 둘 다 미배정으로 남긴 항목이다. `PerformanceQueryCountTest`(캐시 off 기준값)와 `CacheQueryCountTest`(미스/히트 경로)를 CI에서 돌려 쿼리 수 증가를 빌드 실패로 만든다
  - **이 Task가 필요하다는 증거가 이미 있다**: `CacheQueryCountTest` 3개가 "넣고 바로 꺼내는" 패턴으로 **간헐적으로 깨지고 있었는데 Task 033에서야 발견했다.** 베이스 클래스가 `awaitCached()`를 제공하는데 그 세 테스트만 안 쓰고 있었다. 게이트가 있었으면 들어올 때 걸렸다
  - 번호를 `043`이 아니라 `042_1`로 붙인 이유: Phase 12~14의 043~052가 이미 배정돼 있어 밀면 여러 곳을 같이 고쳐야 한다(026_1·030_1과 같은 이유)

- **Task 036: [BE] 좌석맵 조립 비용** (`feature/seatmap-assembly-cost`)
  - **좌석맵 요청 25.50ms 중 조립(DB+DTO)이 17.46ms = 68%** 다. 쓰기는 8.05ms이고 그중 82%가 고정비다
  - **두 길은 이미 닫혔다.** 응답 축소는 Task 033이 기각했고(바이트 **-49.4%인데 처리량 +1~7%**, 배열 튜플 5.2배 축소로 외삽해도 4~5%), Redis 캐싱은 Task 029가 기각했다(적중률 **99.97%에서도 캐시 쪽이 느렸다** — 6.63 → 7.22ms, 항목 337,885B). 008의 "해법은 캐싱이 아니라 응답 크기"라는 처방까지 033이 정정했으므로 **남은 건 조립 자체를 줄이는 것뿐이다**
  - **`010-waiting-queue.md`가 "Caffeine 로컬 캐시에는 029의 기각 근거가 적용되지 않는다"고 남겼다** — 029의 기각 근거가 직렬화와 네트워크 왕복이었고 로컬 캐시엔 둘 다 없다. 단 Task 029에서 Caffeine 의존성을 지웠으므로 되살리는 비용을 먼저 따진다
  - 10,000석 인라인 RSC **1,454KB**가 그대로 남아 있다(009_2). **구 `후속 Task 후보`의 `[BE] 좌석 응답 축소`를 여기로 흡수**했다 — 기각은 유지하고 재판단 조건(좌석 수 10,000석 초과, 또는 Task 035 이후에도 인라인 RSC가 병목)만 이관한다
  - 관련 코드: `ScheduleSeatRepositoryImpl`(단일 쿼리 + join), `CacheNames`의 좌석맵 제외 근거 Javadoc

- **Task 037: [BE] 대기열 설정값·생명주기 전역화** (`feature/queue-global-limits`)
  - **`admit-batch-size` 50이 회차당이다.** 유효 구간 [6.7, 84]명/s 안의 값이지만 `QueueAdmissionScheduler`가 켠 회차를 전부 순회하므로 **회차 2개면 상한을 넘는다.** 010이 "전역 상수로 두는 것 자체가 의심스럽다"고 남겼다
  - **활성 인원 절대 상한이 없다.** 입장권이 활동하면 `ZADD XX GT` 슬라이딩으로 무한 연장되고 유령을 치우지 않는다
  - **세션과 큐가 같은 Redis다.** 압박이 오면 **큐가 아니라 세션이 먼저 죽는다** — 폴링이 큐 키를 계속 만져서 `allkeys-lru`가 핫한 큐를 지키고, 16mb에서 세션 9,999개가 먼저 사라진다(줄 선 사람이 다음 폴링에서 401). 10만 명이 9.24 MiB라 512MB에서는 축출 0건이지만 이건 여유가 있다는 뜻이고 순서가 안전하다는 뜻이 아니다
  - **`@SchedulerLock`의 `lockAtLeastFor`가 `admit-interval`과 수동 결합돼 있다** — 한쪽만 바꾸면 조용히 틀린다
  - 근거: `docs/improvements/010-waiting-queue.md` 남은 과제 6·7 + 미배정 후속

- **Task 038: [공통] 측정 신뢰도 — 전용 부하 노드 (장비 조건부)**
  - **이 Task는 장비가 선행이라 현재 로컬 환경에서는 닫을 수 없다.** 순서에서 빼 두고, 아래 Task들이 이 한계를 안고 측정한다는 것만 명시한다
  - **부하 생성기와 측정 대상이 같은 호스트에서 돈다**(006의 첫 한계). 그래서 `R_poll`이 **2,986 req/s 하한으로만** 남았고, 그 탓에 폴링 백오프 4번째 구간을 60초로 잡았다 — 010이 "서버 CPU가 43%였으니 30초도 아마 된다, 다만 '아마'를 설정값으로 박지 않는다"고 적었다. 상한을 재면 20~40초 후보(미검증으로 표에 남아 있다)를 닫을 수 있다
  - **포화 벽의 정체가 "CPU"까지만이다.** 커넥션 풀 상향은 제대로 반증했지만(10/20/30 모두 약 365 req/s, 140/s에서는 올린 쪽이 p95 214 → 280 → 311ms로 **더 느렸다**), 포화 CPU 11.4 of 16코어를 **어느 코드가 먹는지 프로파일링하지 않았다.** Task 036의 "조립 68%"가 여기 답일 가능성이 있는데 그 연결을 확인한 적이 없다
  - **BE 2대 중 어디로 갔는지 분해하지 않았다**(006)

- **Task 039: [공통] nginx HTTP/2 + gzip 재판정** (`feature/nginx-http2`)
  - **HTTP/2** — 단일 폰트 서브셋이 홈 FCP를 66% 줄인 **정체가 바이트가 아니라 요청 수였다**(커넥션 6개 제한에 폰트 조각 11개가 2라운드). HTTP/2면 조각 11개를 그대로 두고 경쟁만 없앤다. **구 `후속 Task 후보`의 `[공통] nginx HTTP/2`를 흡수**했다
  - **판단 변경: 클라우드 배포(Task 034)를 기다리지 않는다.** 로컬에 self-signed 인증서를 깔고 nginx 443 + `http2 on;`을 세우면 Lighthouse 재측정이 성립한다. 평문 h2c로는 안 된다 — 브라우저가 cleartext HTTP/2를 지원하지 않기 때문에 HTTP/2 이득을 브라우저로 재려면 TLS가 반드시 필요하고, 그 TLS는 로컬에서도 세울 수 있다
    > [!warning] 확인 필요
    > self-signed 인증서로 Lighthouse 측정이 그대로 되는지(인증서 경고가 측정을 막지 않는지), 그리고 `scripts/lighthouse-runs.mjs`의 무결성 검사가 https 오리진에서 통과하는지는 실제로 해봐야 안다. 009_1은 이 항목을 "TLS가 없는 로컬에서는 `listen 80 http2;`가 필요하다"고 적었는데 **그 경로로는 브라우저 측정이 불가능하다**
  - **gzip 재판정** — 009_1이 층② gzip을 기각했고(19:1 압축인데 BE 왕복 +2.0ms/+29%) 재판단 조건이 "포화 구간" 또는 "호스트 분리"였다. **포화 하네스는 Task 033이 이미 만들었다** — 호스트 분리를 기다리지 않고 지금 재볼 수 있는 상태다
  - **단일 폰트 서브셋 재판단** — HTTP/2를 켠 뒤 같은 비교를 다시 한다. 현재 after 홈 536KB 중 폰트가 289KB(53.8%)로 여전히 1순위다
  - 곁딸린 nginx 미해결 2건: **`proxy_buffers`가 240KB 응답에 여유 16KB뿐**, `max_fails`/`fail_timeout` 미명시(009·009_1·010이 전부 넘겼다)

- **Task 041: [FE] 미측정 구간 닫기** (`feature/fe-unmeasured-cases`)
  - **재방문자를 한 번도 재지 않았다 — 개선이 회귀일 수 있는 유일한 구간이다.** 폰트를 dynamic subset으로 바꿔 전송량을 85.9% 줄였지만 그 대가로 **요청이 1개 → 11개**로 늘었다. 첫 방문은 확실히 이득인데(홈 전송 -76.8%, FCP 1,892 → 618ms) 재방문 시 조건부 요청 11번의 비용은 측정 밖이다. Lighthouse가 항상 캐시 콜드라 구조적으로 안 잡힌다
  - **모바일이 전부 n=1이다**(009·009_1·009_2). 009_1이 남긴 **mobile 반례**도 그대로다
  - **좌석맵 FCP 이중분포가 아직 "판단 불가"다** — 산포 25.2%, 2,000석에서는 무스로틀로도 안 풀렸다
  - **path 합치기 교차점을 모른다** — 2,000석에서 RecalcStyle 18.71 → 31.59ms(**+68.8% 회귀**), 10,000석에서 71.84 → 29.20ms(**-59.4% 개선**). 두 점뿐이라 부호가 바뀌는 지점을 특정할 수 없다. 4,000·6,000석 시드를 추가한다. **구 `후속 Task 후보`의 `[FE] path 합치기 교차점 측정`을 흡수**했다
  - **`cacheComponents` 도입 검토** — Next 16의 통합 플래그(PPR + `use cache` + dynamicIO). 030_2의 공개/세션 진입점 분리가 선행 조건을 만들어 뒀고, `lib/api/client.ts`에 `revalidate`/`cacheLife`가 아직 안 켜져 있다. 전 페이지 Suspense 감사가 필요하다. **구 `후속 Task 후보`의 같은 항목을 흡수**했다
  - **favicon에 `.ico` 폴백이 없다** — Safari 16 미만에서 아이콘이 안 보인다

- **Task 042: [공통] 성능 잔챙이** (`feature/perf-small-fixes`)
  - **`@Cacheable`은 캐시를 꺼도 비용이 있다** — 2.20 → 2.63ms. 기본값이 `none`(`NoOpCacheManager`)인데 프록시 비용은 그대로 낸다(008 범위 밖 발견)
  - **dev에 `open-in-view: true`가 켜져 있다** — `prod`만 false다
  - **캐시가 이득이 되는 항목 크기의 경계를 모른다** — 690B 이득 / 337,885B 손해, 두 점뿐이다
  - **테스트 컨텍스트가 늘면 Postgres 커넥션이 고갈된다** — 008이 이미 한 번 부딪혔고 "컨텍스트를 더 추가하면 같은 벽에 다시 부딪힌다"고 남겼다. 테스트 전용 풀이 30/30, 5/1로 흩어져 있다

### Phase 12: 모놀리스 + 카프카 (성능)

**쪼개지 않는다.** 서비스 분리는 Phase 14로 미루고, 지금 구조에서 카프카가 **실제로 성능을 올리는 자리**만 공략한다.

전제를 먼저 분명히 한다 — **카프카는 지금 병목을 직접 줄이지 않는다.** 처리량 벽은 365 req/s이고 원인이 CPU이며(포화 11.4 of 16코어), 그 CPU의 68%가 좌석맵 조립이다. 큐를 넣어도 총 CPU 시간은 그대로다. 피크 평탄화도 Redis 대기열이 이미 한다. **그래서 "트래픽이 많을 것 같아서"는 이 Phase의 근거가 아니다.**

성립하는 근거는 하나다. 세 문서가 각자 남긴 한계가 한 줄로 이어진다.

| 문서 | 남긴 것 |
|---|---|
| 029 | 좌석맵 Redis 캐싱 **기각** — 적중률 99.97%에서도 느렸다(6.63 → 7.22ms). 이유는 337,885B의 왕복·역직렬화 |
| 010 | **"Caffeine 로컬 캐시에는 029의 기각 근거가 적용되지 않는다"** — 직렬화도 네트워크도 없다 |
| 008 | **"로컬 캐시의 2대 불일치를 실측하지 않았다"** — BE가 2대라 로컬 캐시는 서로 다른 값을 든다 |

**경로가 둘이다. 아래가 경로 A(읽기 캐시)이고, 그것이 Task 036에서 기각되면 Task 045_1(쓰기 버퍼링)이 경로 B다.** A를 먼저 두는 이유는 성능 근거가 직접적이기 때문이고, B를 적어 두는 이유는 **A가 036 하나에 매달린 단일 실패점**이기 때문이다.

**조립 68%를 없앨 수 있는 유일한 수단이 로컬 캐시이고, 그게 다중 인스턴스 일관성 때문에 막혀 있다.** 그 일관성을 푸는 것이 무효화 전파이고, 거기서 카프카가 근거를 얻는다. 순서는 이 레포의 방식을 지킨다 — **단순한 것 먼저, 한계를 재현한 뒤 교체한다.**

- **Task 043: [공통] 간섭 재현** (`feature/workload-interference`)
  - **지금 측정은 워크로드를 따로 쟀다.** `browse` 10,150 req/s, `seatmap` 846 req/s, 예매 포화 309 req/s — **12배 차이인데 섞어서 잰 적이 없다.** 같은 Tomcat 스레드풀과 Hikari 커넥션풀(기본 10)을 공유하는 상태다
  - **측정 대상은 처리량이 아니라 서버 측 자원 경합이다.** 처리량으로 재면 006이 경고한 함정에 빠진다 — 부하 생성기가 같은 호스트에 있어서 섞으면 **생성기 부하도 2배가 되고**, 떨어진 처리량이 서버 간섭인지 생성기 굶주림인지 구분할 수 없다("부하를 더 올리면 p95가 서버 지연인지 생성기 굶주림인지 구분할 수 없다"). **Phase 14를 열거나 닫는 판단을 그런 측정에 맡길 수 없다**
  - 그래서 보는 것은 **Hikari 커넥션 획득 대기 시간·`pending` 수, Tomcat 스레드 점유, 요청별 커넥션 보유 시간**이다. 서버 내부 지표라 생성기 한계와 덜 엉킨다. **Task 033이 이미 이 기법으로 거절 경로의 요청당 커넥션 1.0114 → 0.0173개를 잡아냈다** — 하네스(`load-test/results/t033-backend-metrics/`)와 수집 방식이 그대로 있다
  - 간섭의 직접 증거는 **섞었을 때 `browse` 요청이 커넥션을 얻기까지 기다리는 시간이 늘어나는 것**이다. 처리량 숫자는 보조 지표로만 기록하고 상한으로 읽지 않는다
  - 시나리오 조합은 기존 k6 하네스(`load-test/k6/browse.js`, `seatmap.js`)를 같은 런에서 돌리면 되므로 **새 인프라가 0이다**
  - **이 Task의 결과가 Phase 14(서비스 분리)의 유일한 근거다.** 간섭이 작으면 분리는 기각이고, 그 기각도 산출물이다
  - 간섭이 확인되면 **단순한 해법부터** — Tomcat 스레드풀 분리, Hikari 풀 분리(읽기/쓰기), Resilience4j bulkhead. 여기서 해결되면 Phase 14는 열지 않는다. 주의: Task 033이 **커넥션 풀 상향은 이미 반증했다**(10/20/30 모두 365 req/s, 140/s에서는 올린 쪽이 더 느렸다) — 풀을 키우는 것과 풀을 나누는 것은 다른 조치다
- **Task 044: [BE] 로컬 캐시 도입과 2대 불일치 재현** (`feature/local-cache-inconsistency`)
  - Phase 11의 **Task 036(좌석맵 조립 비용)이 선행이다.** 036에서 Caffeine 로컬 캐시가 단일 인스턴스에서 이득인지부터 확정한다 — 이득이 없으면 이 Task와 045는 근거를 잃는다
  - 이득이 확인되면 **BE 2대에서 불일치를 재현한다.** 008이 미실측으로 남긴 바로 그것이다. 좌석이 팔렸는데 한쪽 인스턴스만 그걸 알고, nginx 라운드로빈이라 **새로고침할 때마다 좌석 상태가 번갈아 바뀌는** 화면을 만든다
  - 재현 방법은 Task 026이 세션 불일치를 재현한 하네스를 그대로 쓴다(BE 2대 + nginx, 같은 쿠키로 반복 요청)
  - **좌석 상태는 캐시 대상이 아니라는 기존 규칙과 충돌한다** — `CacheNames`가 `status`와 `remainingQuantity`를 의도적으로 제외했다. 로컬 캐시에 좌표·등급만 넣고 상태를 실시간으로 합치면 조립의 어느 부분이 줄어드는지부터 분해해야 한다. 그 분해가 이 Task의 절반이다

- **Task 045: [BE] 무효화 전파 — Redis Pub/Sub → 카프카** (`feature/cache-invalidation-kafka`)
  - **Redis Pub/Sub으로 먼저 한다.** 이미 Redis가 있어서 추가 인프라가 0이고, 무효화 메시지는 수십 바이트라 029의 기각 근거(큰 값)가 적용되지 않는다
  - **그다음 한계를 재현한다.** Pub/Sub은 at-most-once다 — 구독자가 죽어 있는 동안 발행된 무효화는 **영구히 사라지고, 그 인스턴스의 캐시는 되돌아올 방법이 없다.** 재배포·GC 정지·네트워크 끊김으로 재현한다. 세션 축출 실험(010 7.2)처럼 **조건을 만들어서 재현하는** 방식이다
  - **그 재현이 카프카의 근거다.** 컨슈머가 오프셋을 들고 있어서 재시작하면 놓친 무효화를 따라잡는다. 비교 항목: 전파 지연(p95), 유실률, 인스턴스 재시작 후 수렴 시간, 그리고 **운영 비용**(브로커 1대가 먹는 메모리·CPU와 그게 365 req/s 벽에 주는 영향)
  - **기각 조건을 미리 적어둔다**: Pub/Sub 유실이 재현되지 않거나, 유실이 나도 캐시 TTL로 자연 수렴하는 시간이 허용 범위면 카프카는 기각한다. 그때는 "왜 안 썼나"가 산출물이 된다
  - 카프카 구성은 **KRaft 단일 브로커**(ZooKeeper 없음), 토픽 1개, 파티션 수와 키 전략의 근거를 기록한다 — 무효화는 좌석 단위 순서가 필요하므로 `scheduleId`를 키로 쓴다
  - 로컬 리소스 한계 때문에 측정 시 FE를 끈다. 브로커가 추가되면 Task 038(전용 부하 노드)의 한계가 더 커진다는 것을 수치와 함께 적는다

- **Task 045_1: [BE] 좌석 선점 쓰기 버퍼링 (경로 B, 조건부)** (`feature/seat-hold-buffering`)
  - **위 경로가 Task 036 하나에 매달려 있어서 두 번째 경로를 열어 둔다.** 036에서 로컬 캐시가 이득이 없으면 044·045가 근거를 잃고 **Phase 12의 성능 근거가 0이 된다.** 그 경우에 이 Task로 간다
  - 근거는 다른 곳에서 온다. 010의 결론이다 — **"대기열은 유입을 차단하는 장치가 아니라 계량하는 장치다. 배치를 키우면 그만큼 그대로 흘러들어간다."** `admit-batch-size`를 키우면 좌석 API가 받는 부하가 그대로 올라가고, **활성 인원 절대 상한이 없다**(037). 즉 대기열 뒤에 최종 방어선이 없다
  - 좌석 선점 요청을 카프카 토픽에 넣고 컨슈머가 순차 처리하면 **DB 쓰기 속도를 컨슈머 수로 고정**할 수 있다. 대기열이 못 막는 구간을 토픽이 막는다. 파티션 키는 `scheduleId`이고(회차 단위 순서만 필요하다) 파티션 수가 곧 쓰기 병렬도가 된다
  - **대가가 크고 그걸 먼저 기록한다**: ① 선점이 동기 응답에서 "접수됨"으로 바뀌어 **FE가 결과를 폴링해야 한다** — Task 032가 대기열에 만든 `setTimeout` 체인을 재사용할 수 있다 ② 대기열과 역할이 겹친다 — 둘 다 유입 조절이라 **어느 층이 실제 상한인지 흐려진다** ③ v0.2.0이 조건부 UPDATE를 고른 근거("모든 쓰기 경로가 규약을 지켜야 하는 방식은 아무것도 강제하지 않는다")에 **정면으로 걸린다** — 토픽을 안 거치고 DB를 쓰는 경로가 생기면 상한이 무너진다
  - **그래서 기각이 기본값이다.** 036이 성립하면 이 Task는 열지 않는다. 열더라도 ③을 어떻게 막을지(선점 SQL을 컨슈머만 호출하도록 강제하는 수단)를 먼저 정하지 않으면 시작하지 않는다

### Phase 13: 실패와 보상 (카프카 근거 완성)

Phase 12가 **읽기** 쪽에서 카프카를 정당화한다면, 이 Phase는 **쓰기와 외부 연동** 쪽이다. 지금 이 영역에 근거가 전혀 없는 이유는 단순하다 — **실패하는 외부 의존이 없다.** `MockPaymentGateway`는 절대 실패하지 않고, 알림 구현은 0건이며, `@Async`·`ApplicationEventPublisher`도 0건이다.

- **Task 046: [BE] 실패 주입 PG와 보상 실패 재현** (`feature/payment-failure-injection`)
  - `MockPaymentGateway`에 실패율·지연을 설정으로 주입할 수 있게 바꾼다. 실제 PG 연동이 아니라 **실패를 만들 수 있는 Mock**이다 — 외부 계정 없이 재현이 되고, 실제 PG로 가는 길도 막지 않는다
  - 002가 남긴 한계를 **재현 가능한 버그로 만든다**: "환불 호출이 실패하면 로그만 남긴다. 그 트랜잭션은 곧 롤백되므로 실패 사실을 DB에 남길 수 없다." → **돈은 빠져나갔고 예약은 없는 상태**를 재현한다
  - 같이 드러날 두 번째 문제: **PG 호출이 `@Transactional` 안에 있다**(`ReservationService.pay()`). 지연을 주입하면 커넥션 점유가 수치로 보인다. 측정 지표는 Task 033이 쓴 것과 같다 — 요청당 Hikari 커넥션 수(거절 경로에서 1.0114 → 0.0173개를 잡아낸 그 방법)
  - 해법도 033과 같은 기법이다 — **승인 호출을 트랜잭션 밖으로 빼고 짧은 트랜잭션으로 상태를 재확인 후 확정**한다. 주석에 이미 적혀 있는 설계다

- **Task 047: [BE] 아웃박스 패턴과 정산 배치** (`feature/outbox-pattern`)
  - 002가 적은 해법을 그대로 구현한다 — **아웃박스 테이블 + 정산 배치 + 멱등키.** 카프카가 아니다
  - **카프카를 써도 아웃박스는 필요하다**는 것을 기록한다. DB 커밋과 메시지 발행의 원자성 문제가 그대로 남기 때문이다. 즉 아웃박스는 카프카의 대안이 아니라 선행이다 — 이 순서를 뒤집으면 "카프카를 넣었는데 메시지가 유실된다"를 만난다
  - 배치는 폴링이다. **폴링 주기와 처리량의 한계를 측정한다** — 주기를 줄이면 DB 부하가 늘고, 늘리면 보상 지연이 커진다. 그 곡선이 다음 Task의 근거가 된다
  - `@SchedulerLock`을 반드시 붙인다(BE 2대). 그리고 **보상 작업은 멱등이 아니다** — 기존 만료 배치가 멱등이라 중복 실행이 안전했지만, PG 환불은 두 번 부르면 사고다. `tikkit-back/CLAUDE.md`가 경고한 "다음에 추가될 배치"가 이것이다

- **Task 048: [BE] 알림과 DLQ** (`feature/notification-dlq`)
  - 알림(이메일 또는 푸시)을 붙인다. **비멱등 외부 호출이 처음 생기는 지점**이다
  - **먼저 `@Async` + 재시도로 한다.** 그 한계를 재현한다 — 애플리케이션이 재시작하면 큐에 있던 작업이 사라지고, 재시도 횟수를 넘긴 실패를 보관할 곳이 없다
  - 그 재현이 **DLQ와 영속 큐의 근거**다. 여기서 Phase 12의 카프카를 재사용할지, 아니면 아웃박스 테이블로 충분한지 비교한다 — **브로커가 이미 있다는 사실이 판단을 흐리지 않게** 두 선택지를 같은 조건에서 잰다
  - 알림은 예매 응답 경로에서 빠져야 한다. 동기로 두면 메일 서버 지연이 예매 p95에 그대로 더해지는 것을 먼저 수치로 보여준다

### Phase 14: 서비스 분리 (MSA)

**Task 043(간섭 재현)의 결과가 이 Phase를 열거나 닫는다.** 간섭이 작거나 스레드풀·커넥션풀 분리로 해결되면 여기는 열지 않고, 그 기각 근거를 `docs/improvements/`에 남긴다.

분리 경계는 **측정과 결합도 둘 다**가 지목하는 곳으로 정한다. 현재 도메인 간 참조는 이렇다.

```
member      → (없음)        완전 독립
venue       → (없음)        완전 독립
performance → venue
queue       → performance
payment     → reservation
reservation → member, payment, performance, queue, venue      허브
```

여기서 두 가지가 드러난다. **`reservation`이 5개 도메인에 의존하는 허브**라서 떼면 분산 트랜잭션이 바로 생긴다. 그리고 **도메인 경계와 성능 경계가 다르다** — `browse`(10,150 req/s)와 `seatmap`(846 req/s)이 둘 다 `performance`(32개 소스, 최대 모듈) 안에 있다. 처리량으로 쪼개려면 도메인을 갈라야 하고, 도메인으로 쪼개면 12배 차이가 한 서비스에 남는다. **"어디서 쪼개나"가 이 Phase의 질문이다.**

- **Task 049: [공통] 경계 도출과 최소 분리** — 간섭 측정 + 결합도로 경계를 정하고 **하나만** 뗀다. 후보는 좌석 조회(성능 경계) 또는 `member`·`venue`(의존 0건이라 가장 싸다). HTTP 동기 호출로 먼저 쪼갠다 — 이벤트부터 시작하면 동기 호출의 문제를 재현할 기회를 잃는다
- **Task 050: [공통] 연쇄 장애 재현과 이벤트 전환** — 떼어낸 서비스를 죽여서 **예매가 같이 죽는 것**을 재현한다. 그 재현이 이벤트 기반(카프카)과 서킷 브레이커의 근거다. 둘 중 무엇이 맞는지도 이 측정이 가른다 — 조회 의존은 서킷 브레이커, 상태 전파는 이벤트다
- **Task 051: [공통] 분산 추적** — 서비스가 둘이 되면 "느린 요청이 어디서 느렸나"를 알 수 없게 되는 것을 먼저 겪는다. Task 025가 Prometheus를 넣었으니 Tempo 또는 Jaeger + OpenTelemetry로 잇는다. **p95가 버킷 경계를 읽던 004_3의 함정**과 같은 종류의 오독이 추적에도 있다는 것을 확인한다
- **Task 052: [BE] 데이터 일관성** — 허브인 `reservation`을 떼면 Saga 또는 2PC가 필요해진다. **여기까지 오면 "모놀리스가 옳았다"가 결론일 수도 있다.** v0.2.0이 조건부 UPDATE를 고른 근거가 "DB 제약은 경로와 무관하게 성립한다"였는데, 서비스를 쪼개는 것은 그 보장을 스스로 포기하는 일이다. 그 교환을 수치와 함께 기록하는 것이 이 Task의 목적이고, **되돌리는 결정도 산출물이다**

### Phase 12~14를 묶는 규칙

이 세 Phase는 기술을 도입하는 것이 목적이 아니라 **도입 근거를 만드는 것**이 목적이다. 그래서 모든 Task가 같은 모양을 지킨다.

1. **단순한 해법을 먼저 구현한다** — Redis Pub/Sub, `@Async`, 아웃박스 배치, HTTP 동기 호출
2. **그 해법이 깨지는 조건을 재현한다** — 구독자 사망, 애플리케이션 재시작, 폴링 지연, 연쇄 장애
3. **재현된 뒤에 교체하고 before/after를 잰다**
4. **기각 조건을 미리 적어둔다** — 깨지지 않으면 도입하지 않고, 그 사실을 기록한다

이 순서를 지키면 "왜 카프카인가"에 수치로 답할 수 있다. 뒤집으면 **브로커가 이미 있다는 사실이 모든 판단을 흐린다** — 029가 좌석맵 캐싱을 기각할 수 있었던 이유는 Redis를 이미 쓰고 있었음에도 **측정을 먼저 했기** 때문이다.

## 브랜드 디자인 토큰 (Task 003 적용)

퍼플을 `primary`, 강한 핑크를 별도 `--highlight` 토큰으로 분리한다. shadcn의 `--accent`는 hover 배경으로 쓰이므로 강한 핑크를 넣으면 과해져, 옅은 핑크 틴트로 둔다.

| 토큰 | Light | Dark |
|---|---|---|
| primary (보라) | oklch(0.52 0.24 295) | oklch(0.68 0.20 295) |
| accent (옅은 핑크 틴트) | oklch(0.95 0.03 350) | oklch(0.30 0.06 350) |
| highlight (강한 핑크) | oklch(0.58 0.22 355) | oklch(0.72 0.20 355) |
| destructive | oklch(0.52 0.245 27.3) | oklch(0.704 0.191 22.2) |

- Task 016에서 라이트 모드 highlight(0.60→0.58)·destructive(0.577→0.52)의 명도를 낮춰 텍스트 대비 4.5:1(WCAG AA)을 맞췄다. 알려진 한계: `--border`/`--input`은 배경 대비가 1.2~1.5:1이라 입력창 테두리의 비텍스트 대비 기준(3:1)에는 못 미친다. shadcn 기본값이고 전체 톤이 바뀌는 사항이라 이번에는 두지 않았다.

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

## 보류 Task

진행 트랙에서 빼 두되 **번호는 살려 둔다** — 본문과 `docs/improvements/`가 "Task 034에서 재판단"으로 이 번호를 참조하는 자리가 20곳이 넘고, 번호를 지우면 그 참조가 전부 미아가 된다.

- **Task 034: [공통] GHCR 푸시 및 클라우드 배포(CD)** — 보류
  - Task 024의 `docker build` 잡을 **푸시까지 확장** — GitHub Actions가 main 푸시마다 BE/FE 이미지를 GHCR에 올린다 (태그는 커밋 SHA + `latest`)
  - VM 프로비저닝 후 `docker-compose.prod.yml` 적용, SSH 배포 워크플로우, GitHub Secrets로 시크릿 주입
  - 도메인·HTTPS(Let's Encrypt), 배포 후 스모크 테스트
  - **배포 대상은 착수 시 사용자에게 확인한다** — Oracle Cloud Always Free가 비용 0 후보이고 EC2/Lightsail은 과금된다. 포트폴리오 제출 기간에만 띄우고 내리는 운영도 선택지다
  - **비용이 발생하는 유일한 Task라서 의도적으로 맨 끝에 뒀다.** Phase 7~9는 전부 로컬 Docker로 재현·측정하므로 이 Task 없이도 `docs/improvements/` 기록은 완결된다. 별도 태그를 붙이지 않는다 (배포는 기능이 아니라 운영이고, `v1.0.0`은 Phase 9의 기능 완성에 붙는다)
  - **보류 사유**: 당장 클라우드에 올릴 계획이 없다. 위 문장이 그대로 보류 근거가 된다 — 기록이 이미 완결돼 있어서 이 Task를 미뤄도 잃는 것이 없다. **재개 조건: 실제 배포가 필요해질 때**

### 이 Task를 기다리는 항목

보류하면 같이 멈추는 것들이다. 지금까지 본문 각지에 흩어져 있어서 "배포를 안 하면 무엇이 같이 멈추는지"가 보이지 않았다.

| 항목 | 어디서 넘어왔나 | 배포가 정말 필요한가 |
|---|---|---|
| actuator readiness probe | Task 025 | **필요하다.** probes가 의미를 갖는 건 오케스트레이터가 들어올 때다 |
| 구조화 로깅 + 수집기 | Task 025 | **필요하다.** 파일 출력이 Docker json-file과 이중 저장이 되고 비루트(uid 100)라 볼륨이 필요하다 |
| Prometheus `--web.enable-remote-write-receiver` 운영 반영 | Task 027 | **필요하다** |
| 세션 `repository-type` 재검토 | Task 026 | 애매하다. `indexed`를 쓰는 운영 코드가 0건이라 로컬에서도 판단할 수 있다 |
| nginx 접두사 라우팅 관리 | Task 032 | 애매하다. 로컬 compose의 nginx도 같은 파일이다 |
| **세션 쿠키 `secure`를 켜는 것** | Task 024·026 | **아니다.** Task 039가 로컬 self-signed TLS를 세우므로 거기서 켜고 e2e를 맞출 수 있다 → Task 039 |
| **층② gzip 재판정** | Task 030_1 | **아니다.** 재판단 조건이 "포화 구간 또는 호스트 분리"였고 **포화 하네스는 Task 033이 만들었다** → Task 039 |
| **HTTP/2와 단일 폰트 서브셋 재판단** | Task 030_1·030_2 | **아니다.** 로컬 self-signed TLS로 세울 수 있다 → Task 039 |

**마지막 세 줄이 이 정리의 수확이다 — 셋 다 Task 039 하나로 간다.** "Task 034를 기다린다"고 적어 둔 여덟 항목 중 **실제로 클라우드 배포가 필요한 것은 셋뿐**이고, 나머지는 로컬에 TLS를 세우거나(쿠키·HTTP/2·gzip) 애초에 로컬에서 판단할 수 있는 것이었다.

---

**📅 최종 업데이트**: 2026-10-27
**📊 진행 상황**: **Phase 9 완료** (`v1.0.0`) — 기능 트랙을 닫았다. 클라우드 배포(Task 034)는 **보류**하고, 다음은 **Phase 11(성능 잔여 과제)** → **Phase 12~14(카프카·MSA)** 다. 12~14는 기술 도입이 목적이 아니라 **도입 근거를 만드는 것**이 목적이다.

**Phase 8 완료** (`v0.5.0-performance`) — 성능 개선 트랙을 닫았다. 027에서 k6로 베이스라인을 잡고(600만 행 탐색 p95 2.36ms / 좌석맵 6.72ms / 예매 7.43ms), 028·029는 **둘 다 주된 후보를 기각으로 끝냈다**(인덱스 7종 전부 / 좌석맵 캐싱은 적중률 99.97%에서도 캐시 쪽이 느렸다). 030에서 FE를 처음 재며 방향이 바뀌었고 — 지표를 지배한 건 좌석맵이 아니라 서브셋 없이 통째로 받던 한글 폰트 2,009KB였다 — 030_1이 `@import` 한 줄로 **홈 전송 -76.8% / FCP 1,892 → 616ms**를 얻었다. 030_2는 거기서 남은 **구조** 작업이었고, 이 Task의 수확은 수치보다 **측정 설계**였다. "근거가 전송량이 아니라 확장성"을 재려면 기울기가 필요해서 **10,000석 시드를 새로 만들었고, 그게 결론을 뒤집었다** — 정적 좌석을 `<path>`로 합치는 변경이 2,000석에서는 RecalcStyle +68.8% 회귀인데 10,000석에서는 **-59.4% 개선**이다. 한 점만 봤으면 기각했을 변경이다. 두 번째 수확은 **무스로틀 군**이었다. Lantern 스로틀이 하이드레이션 비용을 평균화해 가리고 있어서, 10,000석 TBT가 스로틀에서 1ms인데 무스로틀에서 **53ms**였고 이 변경으로 **0**이 됐다(DOM 노드 -58.5%). Suspense 분리는 본문 도착을 좌석 수와 무관한 상수로 만들었지만(10,000석 58.2 → 6.92ms) **공짜가 아니었다** — 자리표시자 높이를 안 맞춰 **CLS가 0.0278 → 0.1996**으로 뛰었고, 푸터를 접힘선 아래로 밀어 **0.0005**로 닫았다. 기각이 셋이다: 단일 폰트 서브셋(홈 FCP -66.5%인데도 — **그 이득의 정체가 바이트가 아니라 요청 수**라 올바른 레버는 HTTP/2이고 Task 039에서 로컬 TLS로 재판단한다), BE 좌석 응답 축소(FE 변경만으로 목표 달성), `getMe()` 격리의 수치 효과(구조만 남겼다). 그리고 **009_1의 번들 귀속을 4.2배 과대평가로 정정했다**(@base-ui 34,562B → 실제 8,220B). 다음 레버는 좌석 클릭 32.5ms이고 그건 선택 레이어 오버레이 몫이다

**Phase 9 — Task 031(BE 대기열)·032(FE 대기열 화면)**. 수확은 **"로드맵 문구를 그대로 구현하지 않은 지점들"** 이다. ① "활성 세트 + TTL 토큰"을 ZSET score로 바꿨다 — 토큰 키가 만료되면 SET에 유령이 영구히 남아 `SCARD`가 틀리기 때문이고, 부수 효과로 **만료 테스트가 `sleep` 없이 결정적**이 됐다(과거 score를 심으면 된다) ② **별도 토큰을 발급하지 않았다** — 같은 인증 세션에 내려줄 수밖에 없어 보안 이득이 0이다 ③ **Lua를 쓰지 않고 명령 두 개의 순서로 닫았다**(`ZADD active` 먼저, `ZREM waiting` 나중 — 사이에서 죽으면 사용자가 양쪽에 남고 정상 입장한다. 거꾸로면 선두 몇 명이 조용히 맨 뒤로 간다) ④ "인터셉터 적용"이 **절반만 성립했다** — `POST /reservations`의 `scheduleId`가 요청 본문에 있어 인터셉터가 읽을 수 없다 ⑤ Task 032의 "2초 폴링"을 **백엔드가 지시하는 간격**으로 바꿨다(10만 명이면 상태 API에 50,000 RPS가 몰린다). 함정 셋을 밟았다: `@Repository` 하나를 조건부 설정 밖에 남겨 **테스트 176개가 한꺼번에 빨개졌고**(증상은 "대기열과 무관한 리포지토리 테스트 전부 실패"), Redis 팩토리가 생기면 **Spring Session이 따라 켜져** `MockHttpSession`이 무시되면서 403 대신 **401**이 나왔고(008 8절이 적어둔 구조가 대기열이 세 번째 트리거가 되며 처음 보였다), `QueueFlagRegistry`를 `@Scheduled`로 갱신하면 `@Profile("!test")` 때문에 **대기열 테스트 전체가 "플래그 off"로 조용히 통과**할 뻔했다. 그리고 `CacheEvictionTest`의 `DELETE FROM shedlock`이 불필요했음을 확인해 지웠다 — test 프로필에는 ShedLock 어드바이저가 아예 없다. 테스트 176 → **196개**. 수치 검증은 전부 Task 033 몫이다.

**Task 032(FE 대기열 화면)** 도 같은 성격의 수확을 남겼다 — 로드맵 문구 둘을 구현하지 않았고 양쪽 다 **그대로 만들면 조용히 틀리는** 것이었다. ① "좌석 선택 CTA를 대기열로"는 대기열 API가 전부 `authenticated()`라서 **대기열을 켜지 않은 회차에서도 로그인을 강제**한다 → Task 022가 일부러 연 공개 조회가 죽는다. 대신 **가드가 던지는 401/403을 라우팅 신호로 썼다**(403이 에러가 아니라 "이쪽으로 가라"가 된다). ② "이탈을 `sendBeacon`으로"는 `pagehide`가 새로고침에도 뜨는데 거기서 `DELETE`를 보내면 **새로고침 한 번에 순번이 꼬리로 간다** → 명시적 "나가기" 버튼으로 바꿨다(BE Javadoc도 "확인을 눌렀을 때"로 적고 있었다). 그리고 **화면을 만들어야 드러나는 버그**를 찾았다 — `getScheduleSeats`가 세션을 안 실어서 **대기열을 켜면 입장권을 받은 사람도 401**이었다. 범위 밖 발견이 가장 비쌌다: `npm run dev`가 루트의 빈 lockfile 때문에 CSS 컴파일에 실패하며 **PostCSS 워커 11,193개를 누수**해 13.8GB와 `.next/dev` 132GB를 잠갔다(빌드는 정상이라 dev에서만 보인다). `turbopack.root` 고정으로 막았고 커밋은 분리했다

**Phase 9 완료** (`v1.0.0`) — Task 033이 Phase 9의 수치 검증을 닫았고, 수확은 **"대기열은 유입을 차단하는 장치가 아니라 계량하는 장치다"** 였다. 포화점을 먼저 찾아야 비교가 성립하므로 006이 남긴 숙제부터 닫았다 — `R_sat` = 도착률 **120/s(309 req/s)**, 140/s에서 p95가 64.5 → 214.3ms로 꺾인다. 거기서 역산해 `admit-batch-size`의 유효 구간 **[6.7, 84]명/s**를 얻었고, **이 값이 전역이 아니라 회차당**이라 켠 회차가 2개면 상한을 넘는다는 것도 드러났다. 폴링 백오프는 3구간(2/5/10초)으로 10만 명에서 10,130 RPS를 요구하는데 실측 `R_poll`이 **2,986 req/s**여서 **약 28,000명**이 한계였다 — `rank ≥ 10,000 → 60초` 구간을 넣어 2,630 RPS로 내렸고 **FE는 한 줄도 고치지 않았다**(032가 값을 계약으로 내려준 설계가 여기서 효과를 봤다). 가장 날카로운 결과는 **거절이 공짜가 아니었다**는 것이다: 가드가 `@Transactional` 첫 줄이라 SQL을 한 줄도 돌리지 않는 403이 Hikari 커넥션을 **요청당 1.01개** 가져갔고, 컨트롤러로 올려 **0.0173개(-98.3%)** 가 됐다. 008이 넘긴 캐시 재판정도 닫혔다 — 포화에서 `browse` 처리량이 **7,400 → 10,150 req/s(+37%)** 이고, 008이 용량을 재지 못한 이유는 하네스의 think time이 iteration의 **99.8%** 였다는 것이다(VU 600에서도 p95가 2ms였다). 기각이 둘이다: **커넥션 풀 상향**(10/20/30 모두 365 req/s, 140/s에서는 올린 쪽이 더 느렸다 — pending은 원인이 아니라 증상이었고, 중간에 "풀이 벽"이라 적은 주석까지 같이 고쳤다)과 **BE 좌석 응답 축소**(바이트 -49.4%인데 처리량 +1~7%. 좌석맵 비용의 **68%가 조립**이고 쓰기 구간조차 82%가 고정비라, 008의 "해법은 응답 크기" 처방도 틀렸다). Redis 축출은 **2단계**였다 — 10만 명이 9.24 MiB라 512MB에서는 0건이지만, 압박이 오면 **큐가 아니라 세션이 먼저 죽는다**(폴링이 큐 키를 핫하게 유지한다). 그리고 **밟지 않아도 될 함정 넷**을 밟았다: compose에 `QUEUE_ENABLED`가 아예 없어 스택을 켤 수 없었고, 셸 환경변수가 `.env`를 덮어 "껐다고 믿으며 켜진 상태"를 쟀고, 대기열을 켠 채 예매 부하를 돌려 **p95가 64 → 2.95ms로 "개선"** 됐고(요청이 일을 안 한 것이다), 종료되지 않은 샘플러가 처리량을 40% 깎았다. 앞 둘은 전제 검증, 셋째는 워크로드 무결성 검사로 하네스에 박았다. 범위 밖 발견: `CacheQueryCountTest` 3개가 "넣고 바로 꺼내는" 패턴으로 간헐적으로 깨지고 있었다 — 이분 탐색으로 내 변경 탓이 아님을 먼저 가른 뒤 `awaitCached()`를 넣어 고쳤다 (기능 트랙 36개 완료 / 보류 1개 / Phase 11~14 20개 대기)
