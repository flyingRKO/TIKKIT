@AGENTS.md

# TIKKIT 프론트엔드 개발 지침

> ⚠️ **중요**: 이 프로젝트는 **Next.js 16.2.3**을 사용합니다.
> Next.js 16은 15와 다수의 Breaking Changes가 있습니다.
> 코드 작성 전 `node_modules/next/dist/docs/` 가이드를 반드시 확인하세요.
> Deprecation 경고를 무시하지 마세요.

## 핵심 기술 스택

| 기술 | 버전 | 용도 |
|------|------|------|
| Next.js | 16.2.3 | App Router 기반 풀스택 프레임워크 |
| React | 19.2.4 | UI 라이브러리 |
| TypeScript | 5 | 타입 안전성 |
| TailwindCSS | v4 | 스타일링 (설정 파일 없는 새 엔진) |
| ESLint | 9 | 코드 품질 |

## 프로젝트 구조

```
tikkit-front/
├── app/                    # Next.js App Router
│   ├── layout.tsx          # 루트 레이아웃
│   ├── page.tsx            # 홈페이지
│   └── globals.css         # 전역 스타일
├── components/             # 재사용 컴포넌트
├── lib/                    # 유틸리티, API 클라이언트
├── types/                  # TypeScript 타입 정의
└── public/                 # 정적 에셋
```

## 개발 규칙

### 컴포넌트 작성 원칙
- Server Component를 기본으로 사용
- 상호작용이 필요한 경우에만 `'use client'` 사용
- 컴포넌트 파일명: PascalCase (예: `UserCard.tsx`)
- 페이지 파일: `page.tsx`, 레이아웃: `layout.tsx`

### API 통신
- 백엔드 API 기본 URL: `http://localhost:8080` (개발)
- API 클라이언트는 `lib/api/` 디렉토리에 위치
- 모든 API 호출에는 에러 처리 포함

### 스타일링
- TailwindCSS v4 유틸리티 클래스 사용
- 인라인 스타일 지양
- 반응형 디자인: mobile-first 접근

### 코드 품질
- 주석은 한국어로 작성
- 변수명/함수명은 영어 사용
- TypeScript `any` 타입 사용 금지

## npm 스크립트

```bash
npm run dev       # 개발 서버 실행 (포트: 3000)
npm run build     # 프로덕션 빌드
npm run lint      # ESLint 검사
npm start         # 프로덕션 서버 실행
```

## 에이전트·스킬 안내

**프로젝트 전용 서브에이전트는 두지 않는다**(2026-11-01에 `nextjs-app-developer`·
`ui-markup-specialist`·`starter-cleaner`를 지웠다 — 실사용 0). 이유는 루트 `CLAUDE.md`의
「서브에이전트」 절에 있다.

Next.js 16은 학습 데이터와 다르므로 **`AGENTS.md`가 요구하는 대로
`node_modules/next/dist/docs/`의 해당 가이드를 먼저 읽는다.**

## 백엔드 연동

백엔드 API 서버: `http://localhost:8080`
백엔드 문서: `../tikkit-back/CLAUDE.md` 참조
