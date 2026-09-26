# TIKKIT

[![CI](https://github.com/flyingRKO/TIKKIT/actions/workflows/ci.yml/badge.svg)](https://github.com/flyingRKO/TIKKIT/actions/workflows/ci.yml)

단순한 예매 MVP에서 출발해 동시성·지정석·대기열·성능·운영을 단계적으로 개선하며, 매 단계를 재현 → 해결 → 수치로 증명하는 티켓 예매 서비스 (포트폴리오 프로젝트)

## 진행 상황

**Phase 2 진행 중 (9/33 Task 완료)** — 세션 인증과 공연·회차 조회 API가 준비됐고, 이어서 관련 화면을 붙입니다. 전체 계획은 [`docs/ROADMAP.md`](docs/ROADMAP.md)에서 확인할 수 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | MVP 요구사항, 기능 명세, API 계약 |
| [`docs/ERD.md`](docs/ERD.md) | DB 스키마, 정규화·동시성·마이그레이션 설계 |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Phase 0~9, Task 001~032 개발 로드맵 |

## 기술 스택

| 영역 | 기술 |
|---|---|
| 프론트엔드 | Next.js 16.2.3 (App Router), React 19.2.4, TypeScript 5, TailwindCSS v4 |
| 백엔드 | Spring Boot 3.4.5, Java 21, Spring Data JPA + QueryDSL + MyBatis |
| 데이터베이스 | PostgreSQL 15 (Docker) |

## 프로젝트 구조

```
TIKKIT/
├── tikkit-front/   # Next.js 프론트엔드
├── tikkit-back/    # Spring Boot 백엔드
└── docs/           # PRD, ERD, ROADMAP
```

## 로컬 개발 환경

### 백엔드

```bash
cd tikkit-back
cp .env.example .env   # 최초 1회
docker-compose up -d   # PostgreSQL 실행 (Docker Desktop 필요)
./gradlew bootRun       # http://localhost:8080
```

기동 시 Flyway가 V1 스키마와 dev 시드 데이터를 자동 적용합니다. 테스트 계정(비밀번호 모두 `Password1!`):

| 이메일 | 권한 |
|---|---|
| `user@tikkit.com` | USER |
| `admin@tikkit.com` | ADMIN |

### 프론트엔드

```bash
cd tikkit-front
npm install
npm run dev              # http://localhost:3000
```

## 포트폴리오 포인트 (예정)

MVP 완성 이후 아래 항목을 순서대로 진행하며, 각 단계는 `docs/improvements/`에 **문제 → 재현 → 원인 분석 → 해결 → before/after 수치**로 기록할 예정입니다.

- **동시성 제어 진화**: 의도적으로 동시성을 보장하지 않는 재고 차감으로 MVP를 출시한 뒤, 초과 판매를 테스트로 재현하고 DB 락 → Redis 분산 락 순으로 비교·개선
- **지정석 전환**: 등급별 수량 모델 → 실제 좌석 모델로 무중단(expand → backfill → contract) 스키마 마이그레이션
- **성능 최적화**: k6 부하 테스트로 병목을 찾고 인덱스·캐싱 전후 수치 비교
- **대기열 시스템**: Redis 기반 대기열로 오픈런 트래픽 대응

자세한 내용은 [`docs/ROADMAP.md`](docs/ROADMAP.md)의 Phase 5~9를 참고하세요.
