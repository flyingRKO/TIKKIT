# TIKKIT 백엔드 개발 지침

## 핵심 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| Spring Boot | 3.4.5 | 메인 애플리케이션 프레임워크 |
| Java | 21 | 백엔드 언어 |
| Spring Data JPA | - | ORM / 데이터 접근 레이어 |
| QueryDSL | - | 타입 안전 동적 쿼리 |
| MyBatis | 3.x | 통계/배치/벤더 특화 SQL |
| Spring Security | - | 인증 및 인가 (ROADMAP Task 008에서 도입 예정, 현재 build.gradle에 미포함) |
| Spring Web | - | REST API |
| PostgreSQL | 15 | 메인 데이터베이스 (Docker) |
| Lombok | - | 보일러플레이트 코드 감소 |
| JUnit 5 | - | 테스트 프레임워크 |

## 프로젝트 구조

```
tikkit-back/
├── src/
│   ├── main/
│   │   ├── java/com/tikkit/api/
│   │   │   ├── TikkitApplication.java      # 메인 클래스
│   │   │   ├── domain/                     # 도메인 모듈
│   │   │   │   └── {domain}/
│   │   │   │       ├── controller/         # REST 컨트롤러
│   │   │   │       ├── service/            # 비즈니스 로직
│   │   │   │       ├── repository/         # JPA/QueryDSL 데이터 접근
│   │   │   │       ├── mapper/             # MyBatis 매퍼 (통계/배치/벤더 특화)
│   │   │   │       ├── entity/             # JPA 엔티티
│   │   │   │       └── dto/                # 요청/응답 DTO
│   │   │   ├── common/                     # 공통 모듈
│   │   │   │   ├── exception/              # 예외 처리
│   │   │   │   ├── response/               # 공통 응답 형식
│   │   │   │   └── config/                 # 설정 클래스
│   │   │   └── security/                   # 보안 설정
│   │   └── resources/
│   │       ├── application.yml             # 프로필 설정
│   │       ├── application-dev.yml         # 개발 환경
│   │       └── application-test.yml        # 테스트 환경
│   └── test/
│       └── java/com/tikkit/api/
└── build.gradle
```

## 개발 규칙

### 레이어드 아키텍처 준수

```
Controller → Service → Repository → Entity
```

- **Controller**: HTTP 요청/응답, 입력 검증, 인증 확인
- **Service**: 비즈니스 로직, 트랜잭션 관리
- **Repository**: 데이터 접근 — JPA/QueryDSL (CRUD, 동적 조건 조회)
- **Mapper**: MyBatis 매퍼 — 통계/리포트/배치/벤더 특화 SQL 한정
- **Entity**: JPA 매핑, 도메인 모델

### ORM 선택 기준

| 작업 유형 | 담당 |
|---------|------|
| 쓰기 (INSERT/UPDATE/DELETE) | JPA |
| 단순 조회 / 동적 조건 조회 | QueryDSL |
| 통계 / 리포트 / 집계 | MyBatis |
| 대용량 배치 | MyBatis |
| DB 벤더 특화 SQL | MyBatis |
| 레거시 스키마 매핑 | MyBatis |

### 코딩 컨벤션

- 주석은 한국어로 작성
- 변수명/메서드명은 영어 사용 (camelCase)
- 클래스명은 PascalCase
- 상수는 UPPER_SNAKE_CASE
- Lombok 어노테이션 적극 활용 (@Builder, @RequiredArgsConstructor)

### API 설계 원칙

- RESTful URL 구조: `/api/v1/{resource}`
- HTTP 상태 코드 올바르게 사용
- 일관된 응답 형식 (`ApiResponse<T>`)
- DTO를 사용하여 Entity 직접 노출 금지

### 보안 규칙

- 민감한 데이터는 응답에 포함하지 않음
- 모든 비밀번호는 BCrypt 암호화
- JWT 토큰 기반 인증
- SQL Injection 방지 (JPA 파라미터 바인딩)

## Gradle 스크립트

```bash
./gradlew bootRun           # 개발 서버 실행 (포트: 8080)
./gradlew build             # 프로덕션 빌드
./gradlew test              # 테스트 실행
./gradlew clean             # 빌드 산출물 삭제
```

## 데이터베이스

최초 1회 `.env.example`을 복사해 `.env`를 만든다 (`.env`는 gitignore 대상이라 저장소에는 올라가지 않는다):

```bash
cp .env.example .env
```

```bash
# PostgreSQL 컨테이너 실행 (Docker Desktop이 켜져 있어야 한다)
docker-compose up -d

# 접속 정보 (application-dev.yml 참조)
# Host: localhost
# Port: 5432
# Database: tikkit_db
```

DB 자격증명은 `application-dev.yml`에서 `${DB_USERNAME:tikkit_user}` 형태의 기본값을 쓴다. 평소엔 아무 설정 없이 `.env`의 값과 동일하게 동작하고, 필요할 때만(CI 등) `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 환경변수로 재정의한다.

## 에이전트 안내

백엔드 전용 에이전트는 `.claude/agents/`에 있습니다:
- `spring-boot-developer`: Spring Boot 레이어드 아키텍처 설계 전문가
- `api-designer`: REST API 설계 및 문서화 전문가

공통 에이전트는 루트 `TIKKIT/.claude/agents/`에서 상속됩니다.

## 프론트엔드 연동

프론트엔드 개발 서버: `http://localhost:3000`
CORS 설정 필요: `tikkit-front` 도메인 허용

## 환경 프로파일

| 프로파일 | 용도 | DB |
|---------|------|-----|
| `dev` | 로컬 개발 | PostgreSQL (Docker) |
| `test` | 테스트 실행 | H2 인메모리 |
