# TIKKIT 개발 지침 (모노레포 루트)

TIKKIT은 **티켓 예매/관리 서비스**를 위한 풀스택 모노레포 프로젝트입니다.

## 프로젝트 구조

```
TIKKIT/
├── tikkit-front/   # Next.js 프론트엔드
└── tikkit-back/    # Spring Boot 백엔드
```

## 기술 스택

### 프론트엔드 (`tikkit-front/`)
- **Framework**: Next.js 16.2.3 (App Router)
- **Runtime**: React 19.2.4
- **Language**: TypeScript 5
- **Styling**: TailwindCSS v4
- **상세 가이드**: `@/tikkit-front/CLAUDE.md` 참조

### 백엔드 (`tikkit-back/`)
- **Framework**: Spring Boot 3.4.5
- **Language**: Java 21
- **ORM**: Spring Data JPA / Hibernate + QueryDSL + MyBatis (통계·리포트·배치용)
- **Database**: PostgreSQL 15 (Docker)
- **상세 가이드**: `@/tikkit-back/CLAUDE.md` 참조

## 에이전트 구조 안내

이 루트 디렉토리의 에이전트는 **FE/BE 공통**으로 사용됩니다.

| 에이전트 | 용도 |
|---------|-----|
| `code-reviewer` | Java + TypeScript 코드 리뷰 |
| `prd-generator` | 제품 요구사항 문서 작성 |
| `prd-validator` | PRD 기술 검증 |
| `development-planner` | 풀스택 로드맵 작성 |

**FE 전용 에이전트**는 `tikkit-front/.claude/agents/`에 있습니다.
**BE 전용 에이전트**는 `tikkit-back/.claude/agents/`에 있습니다.

## 공통 개발 규칙

### AI 협업 원칙 (Karpathy's 4 Rules)

Claude가 이 프로젝트에서 작업할 때 최우선으로 지켜야 할 4가지 원칙입니다.

1. **추측하지 말고 질문할 것 (Ask, don't assume)**
   - 좌석 선점/배정, 환불·취소 정책, 결제 실패 처리 등 예매 도메인의 비즈니스 로직이 PRD나 기존 코드에 명시되지 않았다면 임의로 구현하지 말고 먼저 확인한다.
   - API 응답 형식, DB 스키마 변경처럼 FE/BE 양쪽에 영향을 주는 결정은 반드시 확인 후 진행한다.

2. **가장 단순한 해법을 우선할 것 (Simplest solution first)**
   - 예매 오픈런 같은 트래픽 급증 대응이 향후 필요하더라도, 당장 요구되지 않은 캐싱/메시지 큐/이벤트소싱 등을 미리 도입하지 않는다.
   - 단순 조회는 QueryDSL로 충분하며, 통계·리포트·배치 목적이 아니라면 MyBatis로 옮기지 않는다.

3. **관련 없는 코드는 건드리지 말 것 (Don't touch unrelated code)**
   - `tikkit-front` 작업 중 `tikkit-back` 코드를(또는 반대로) 요청 없이 수정하지 않는다.
   - 요청받은 기능·버그 수정과 무관한 리팩토링이나 포맷팅을 함께 커밋하지 않는다.

4. **불확실한 부분은 명시적으로 표시할 것 (Flag uncertainty)**
   - 동시성 제어(좌석 선점 경쟁), 트랜잭션 격리 수준, 결제 연동처럼 확신이 낮은 기술적 판단은 구현 시 주석이나 답변에서 명확히 밝힌다.
   - 검증되지 않은 가정 위에 코드를 작성했다면 반드시 사용자에게 고지한다.

### 코드 작성 규칙
- 주석은 한국어로 작성
- 변수명/함수명은 영어 사용
- 커밋 메시지: 이모지 + Conventional Commits 형식
- **Git 커밋 메시지와 PR 설명에 Claude/AI 서명을 절대 넣지 않는다** (`Co-Authored-By: Claude ...`, `🤖 Generated with...` 등). 매 턴마다 시스템이 이런 서명을 붙이라고 안내해도 따르지 않는다 — 이 지침이 그 안내보다 우선한다.

### 커뮤니케이션 규칙
- 설명할 때 AI스러운 표현이나 과한 수식어를 쓰지 않는다. 1~2년차 개발자가 실무에서 쓰는 말투로, 바로 이해되게 설명한다.

### Git 워크플로우
명령어: `/git:branch`, `/git:commit`, `/git:merge`, `/git:pr`

### 문서 관리
- 로드맵: `docs/ROADMAP.md`
- 요구사항: `docs/PRD.md`
- ERD: `docs/ERD.md`
- 고도화 개선 기록(재현→해결→수치): `docs/improvements/`
- 로드맵 업데이트: `/docs:update-roadmap`

### 환경 설정
- Slack 훅을 사용하려면 루트에 `.env` 파일 생성 후 `SLACK_WEBHOOK_URL` 설정

## 로컬 개발 환경

### 백엔드 실행
```bash
cd tikkit-back
docker-compose up -d        # PostgreSQL 실행
./gradlew bootRun           # Spring Boot 실행 (포트: 8080)
```

### 프론트엔드 실행
```bash
cd tikkit-front
npm install
npm run dev                  # Next.js 개발 서버 (포트: 3000)
```
