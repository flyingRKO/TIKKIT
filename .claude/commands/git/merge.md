---
description: '브랜치를 안전하게 병합하고 충돌을 해결합니다'
allowed-tools:
  [
    'Bash(git merge:*)',
    'Bash(git status:*)',
    'Bash(git diff:*)',
    'Bash(git log:*)',
    'Bash(git branch:*)',
    'Bash(git fetch:*)',
    'Bash(git pull:*)',
    'Bash(git reset:*)',
    'Bash(git checkout:*)',
    'Bash(git stash:*)',
  ]
---

# Claude 명령어: Merge

브랜치를 안전하게 병합하고 충돌을 자동으로 해결하는 Git 병합 전문 도구입니다.

## 사용법

```
/git:merge [브랜치명]           # 지정된 브랜치를 현재 브랜치에 병합
/git:merge                    # 대화형 병합 메뉴
```

## 주요 기능

### 1. 안전한 병합 프로세스
- 병합 전 상태 점검 (uncommitted changes, conflicts)
- 자동 백업 및 복구 지점 생성
- 병합 충돌 자동 감지 및 단계별 해결 가이드

### 2. 다양한 병합 전략
- **Fast-forward**: 선형 히스토리 유지
- **No-fast-forward**: 병합 커밋 생성하여 브랜치 히스토리 보존
- **Squash**: 여러 커밋을 하나로 압축하여 병합

### 3. 지능적 충돌 해결
- 충돌 파일 자동 식별
- 충돌 내용 시각적 표시
- 단계별 해결 가이드 제공
- 해결 후 자동 검증

## 프로세스

### 병합 전 검사 단계

1. **현재 브랜치 상태 확인**
   - Uncommitted 변경사항 확인
   - Working directory 정리 상태 점검

2. **대상 브랜치 검증**
   - 브랜치 존재 여부 확인
   - 원격 브랜치 동기화 상태 점검
   - 브랜치 간 분기점 분석

3. **병합 가능성 사전 점검**
   - Potential conflicts 미리 감지
   - 병합 후 예상 결과 미리보기

### 병합 실행 단계

1. **자동 백업 생성**
   - 현재 상태를 stash로 백업
   - 병합 전 커밋 SHA 기록

2. **병합 전략 선택**
   - Fast-forward 가능 여부 확인
   - 프로젝트 정책에 따른 전략 결정

3. **병합 수행**
   - 선택된 전략으로 병합 실행
   - 실시간 진행 상황 모니터링

### 충돌 해결 단계

1. **충돌 파일 식별**
2. **대화형 해결 프로세스**
   - 파일별 충돌 내용 표시
   - 해결 옵션 제시 (ours/theirs/manual)
3. **해결 검증**
   - 모든 충돌 해결 확인
   - 최종 커밋 생성

## 병합 전략

### Fast-Forward 병합
선형 히스토리 유지, 간단한 변경사항에 적합

### No-Fast-Forward 병합
병합 커밋으로 브랜치 히스토리 보존, 협업 프로젝트에 권장

### Squash 병합
여러 커밋을 하나로 통합, 깔끔한 히스토리 유지

## 충돌 해결 가이드

### 충돌 유형별 해결법

#### 내용 충돌 (Content Conflict)
- `ours`: 현재 브랜치 내용 유지
- `theirs`: 병합할 브랜치 내용 채택
- `manual`: 수동으로 편집

#### 파일 삭제/수정 충돌
- 파일 삭제 유지
- 수정된 내용 채택
- 새로운 버전으로 재작성

## 안전 기능

### 병합 취소 및 복구

```
/git:merge --abort    # 진행 중인 병합 중단
/git:merge --reset    # 병합 전 상태로 복구
```

### 안전 검사
- 중요 브랜치 보호 (main, develop)
- 병합 권한 확인
- 코드 리뷰 상태 점검

## 사용 예시

```
/git:merge feature/user-auth
/git:merge --no-ff feature/user-auth    # No-fast-forward
/git:merge --squash feature/user-auth   # Squash merge
```

이 커맨드는 Git 병합의 모든 복잡성을 처리하면서도 안전하고 직관적인 인터페이스를 제공합니다.
