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
| 인증 | Spring Security (세션 기반, in-memory HttpSession) | Task 008 |
| 캐시/락/대기열 | Redis + Redisson | Task 020, 029, 031 |
| 모니터링 | Actuator + Micrometer + Prometheus + Grafana | Task 025 |
| 세션 확장 검증 | Spring Session Data Redis vs JWT 비교 | Task 026 |
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

### Phase 6: 지정석 전환

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
- **Task 022: [BE] 좌석 조회·선점 API 전환**
  - `GET /schedules/{id}/seats` 추가, `POST /reservations` 요청 바디에 `seatIds` 추가 (`ticketGradeId`/`quantity`는 유지 — 한 예약=한 등급 정책)
  - 선택한 `seatIds`가 모두 동일한 `ticketGradeId`에 속하는지 애플리케이션 레벨 검증 (여러 테이블에 걸친 조건이라 DB CHECK로 불가)
  - 정렬된 ID 기준 다중행 조건부 UPDATE, "좌석당 한 명만 선점 성공" 동시성 테스트
  - 만료·취소 시 좌석 반환 처리 (`reservation_seats` 경유 UPDATE). **필수 후속** — V5가 기존 PENDING 예약을 HELD로 만들었지만 `ReservationExpiryScheduler`는 좌석을 모른다. 이걸 넣기 전까지 만료된 예약의 좌석이 HELD로 고착된다 (Task 021 알려진 한계)
  - 좌석 중복 선점이 `uk_schedule_seats_schedule_seat`를 때리면 Task 020과 같은 방식으로 **서비스 레이어에서 제약명으로 가려** 409로 바꾼다. 이때 **이름 없는 CHECK의 자동 생성 이름(`{테이블}_{컬럼}_check`)에 의존하지 않는다** — 컬럼명이 바뀌거나 CHECK가 여러 개면 `_check1`, `_check2`로 붙는다 (Task 021에서 확인)
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
- **Task 026: [BE] 다중 인스턴스 세션 불일치 재현 및 개선**
  - Task 024로 컨테이너화한 BE를 2개 인스턴스로 띄우고 로드밸런서(nginx 등) 뒤에 두어, 세션이 서버 메모리에만 있어 A 서버에서 로그인 후 B 서버로 요청이 가면 로그아웃되는 현상을 재현
  - Redis 세션(Spring Session Data Redis)과 JWT(stateless) 두 가지 해결책을 각각 적용해보고, Task 025의 Grafana로 응답 지연을 비교하고 강제 로그아웃(권한 회수) 가능 여부·인프라 비용도 함께 따짐
  - 최종 선택과 이유를 `docs/improvements/005-session-scaling.md`에 재현→해결→수치 형식으로 기록, `v0.4.0-ops` 태그
- **Task 026_1: [BE] Spring Boot 4 업그레이드**
  - Spring Boot 3.4.5 → 4.1.x. 3.4 라인의 OSS 보안 패치가 2025-12-31에 끊겼고 3.5도 2026-06-30에 끝나서, 패치를 받는 라인은 4.0/4.1뿐이다
  - 동시에 올라가는 메이저: Spring Framework 6.2→7.0, Hibernate 6.6→7.4, Spring Security 6.4→7.1, Jackson 2→3(패키지 이동), netty 4.1→4.2(Redisson도 4.x 필요), springdoc 2.x→3.x. QueryDSL 5.1.0은 그대로다
  - 영향 범위 실측(Task 020 시점): Jackson 직접 사용 6파일(`ApiResponse`의 `@JsonInclude`, security 핸들러 2개의 `ObjectMapper`, 통합 테스트 3개), `nativeQuery = true` 2파일(`ReservationRepository`의 만료 CTE, `PerformanceRepository`의 파생값 재계산), security 설정 5파일
  - **Hibernate 7에서 native 쿼리와 벌크 UPDATE 동작이 바뀌는지 먼저 확인한다** — Phase 5(Task 019)의 조건부 UPDATE가 전부 거기 걸려 있다. `@Modifying(flushAutomatically = true)`의 flush 시점과 영향 행 수 반환이 핵심 검증 대상
  - 업그레이드 후 Task 019·020의 동시성 측정을 재실행해 수치가 유지되는지 확인하고, 마이그레이션 과정을 `docs/improvements/`에 기록
  - 번호를 `027`이 아니라 `026_1`로 붙인 이유: Task 027~033이 ERD 마이그레이션 이력과 기술 스택 표에서 참조되고 있어 번호를 밀면 여러 곳을 같이 고쳐야 한다 (마이그레이션에서 `V4` 대신 `V3_1`을 쓴 것과 같은 이유)

### Phase 8: 성능 개선

- **Task 027: [공통] k6 부하 테스트 환경 및 베이스라인**
  - 대량 데이터 스크립트(`generate_series`로 공연 1,000개 × 회차당 좌석 약 2,000석, 전체 `schedule_seats` 약 600만 행 목표), dev 시드와 분리 관리
  - 시나리오: 탐색, 좌석 조회, 예매 스파이크
  - 베이스라인 리포트(p95, TPS, 에러율 + Grafana 캡처), `docs/improvements/006-performance-baseline.md` 작성
- **Task 028: [BE] 쿼리·인덱스 최적화**
  - N+1 쿼리 탐지(SQL 로그/쿼리 카운트) 및 fetch join·`@BatchSize`·DTO 프로젝션으로 해결
  - `EXPLAIN ANALYZE` 기반 인덱스 추가(`V7__add_indexes.sql`)
  - before/after 수치 기록, `docs/improvements/007-query-optimization.md` 작성
- **Task 029: [BE] 캐싱 적용**
  - Spring Cache + Redis로 공연 목록/상세 캐싱 (TTL, 변경 시 evict)
  - 좌석·잔여 수량은 캐싱 대상에서 제외하고 이유를 문서화
  - before/after 수치 기록, `docs/improvements/008-caching.md` 작성
- **Task 030: [FE] 렌더링 성능 개선**
  - Lighthouse 베이스라인 측정
  - `next/image`, `revalidate`/`cacheLife`(Next 16 문서 확인 필요), Suspense 스트리밍 적용
  - before/after 수치 기록, `docs/improvements/009-frontend-performance.md` 작성, `v0.5.0-performance` 태그

### Phase 9: 대기열 시스템

- **Task 031: [BE] Redis 대기열**
  - ZSET(score = 입장 시각) 기반 대기열, ZRANK로 순번 계산
  - 스케줄러가 초당 N명씩 활성 세트로 입장시키고 TTL 토큰 발급
  - 좌석·예매 API에 토큰 검증 인터셉터 적용, `V8` 마이그레이션의 `queue_enabled` 플래그로 회차별 on/off
  - 순서·입장·만료 테스트
- **Task 032: [FE] 대기열 화면**
  - `/queue/[scheduleId]`: 순번, 예상 대기시간, 그라디언트 진행바
  - 2초 간격 폴링, 입장 시 자동 이동, 페이지 이탈 경고
- **Task 033: [공통] 오픈런 부하 검증 및 최종 회고**
  - 대기열 유무에 따른 k6 스파이크 비교 (DB 커넥션, 에러율, p95)
  - `docs/improvements/010-waiting-queue.md`와 요약 인덱스 `docs/improvements/README.md` 작성
  - README 포트폴리오 섹션 마무리, `v1.0.0` 태그

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

---

**📅 최종 업데이트**: 2026-10-07
**📊 진행 상황**: Phase 6 진행 중 — Task 021 완료, Task 022 진행 예정 (21/34 Tasks 완료)
