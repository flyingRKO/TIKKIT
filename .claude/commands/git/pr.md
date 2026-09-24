---
description: 'GitHub Pull Request를 생성하고 관리합니다'
allowed-tools:
  [
    'Bash(gh pr:*)',
    'Bash(gh api:*)',
    'Bash(gh repo:*)',
    'Bash(git push:*)',
    'Bash(git status:*)',
    'Bash(git log:*)',
    'Bash(git diff:*)',
    'Bash(git branch:*)',
    'Bash(git fetch:*)',
  ]
---

# Claude 명령어: Pull Request

GitHub Pull Request를 자동으로 생성하고 관리하는 통합 도구입니다.

## 사용법

```
/git:pr                       # 현재 브랜치로 PR 생성 (대화형)
/git:pr "PR 제목"              # 제목 지정하여 PR 생성
/git:pr --draft               # Draft PR 생성
/git:pr --ready               # Draft PR을 Ready로 전환
```

## 주요 기능

### 1. 스마트 PR 생성
- 커밋 히스토리 기반 제목/설명 자동 생성
- 변경된 파일 분석으로 PR 유형 자동 분류
- 브랜치명에서 작업 유형 추출 (feature, fix, docs 등)

### 2. 자동 메타데이터 설정
- 라벨 자동 할당 (feat, fix, docs, breaking-change)
- 리뷰어 자동 할당 (팀 규칙 기반)
- 마일스톤 및 프로젝트 연결

### 3. 템플릿 기반 PR 설명
- 체크리스트 자동 생성
- 변경사항 요약
- 테스트 계획 포함

## 프로세스

### PR 생성 전 점검
1. **브랜치 상태 확인**: 최신 상태, 원격 푸시 상태, uncommitted 변경사항
2. **변경사항 분석**: 수정된 파일 목록, 변경 유형 분류
3. **PR 요구사항 검증**: 브랜치명 규칙, 커밋 메시지 품질

### 자동 PR 내용 생성

**제목 생성 규칙:**
```
feature/user-auth  → "✨ feat: 사용자 인증 기능 추가"
fix/login-bug      → "🐛 fix: 로그인 버그 수정"
docs/readme        → "📝 docs: README 문서 업데이트"
```

**라벨 자동 할당:**
```
새 파일 추가        → "feature"
버그 수정 패턴      → "bug", "fix"
문서 파일 변경      → "documentation"
테스트 파일        → "test"
설정/빌드 파일      → "chore"
Breaking Change    → "breaking-change"
```

## PR 템플릿

```markdown
## 📋 변경사항 요약

[자동 생성된 변경사항 요약]

## 🎯 목적 및 배경

[브랜치명과 커밋 메시지 기반 목적 설명]

## 🔧 주요 변경내용

- [ ] [변경사항 1]
- [ ] [변경사항 2]

## ✅ 체크리스트

### 코드 품질
- [ ] 코드가 프로젝트의 스타일 가이드를 따름
- [ ] Self-review 완료
- [ ] 불필요한 console.log/debug 코드 제거

### 테스트
- [ ] 기존 테스트 모두 통과
- [ ] 새로운 기능에 대한 테스트 추가

### 문서화
- [ ] 코드 변경에 따른 문서 업데이트

## 🧪 테스트 방법

[테스트 시나리오 및 확인 방법]

## 🔗 관련 이슈

Closes #[issue-number]
```

## 고급 기능

```
/git:pr --status          # PR 상태 확인
/git:pr --draft           # Draft로 전환
/git:pr --ready           # Ready for review로 전환
/git:pr --merge           # 자동 병합 (조건 충족시)
/git:pr --close           # PR 닫기
```

## 사용 예시

```
/git:pr
# 대화형으로 PR 생성

/git:pr "사용자 인증 기능 구현"
# 제목 지정하여 PR 생성

/git:pr --draft --reviewer="@team-lead"
# Draft PR로 생성하고 특정 리뷰어 지정
```

이 커맨드는 GitHub Pull Request 생성의 모든 과정을 자동화합니다.
