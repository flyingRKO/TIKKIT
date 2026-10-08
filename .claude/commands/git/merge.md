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

### 프로젝트 정책: Squash를 쓰지 않는다

**TIKKIT은 병합할 때 커밋을 하나로 합치지 않는다.** `--squash`와 GitHub의 "Squash and merge"를
쓰지 않고, 항상 **No-Fast-Forward 병합 커밋**(`git merge --no-ff` / `gh pr merge --merge`)으로
브랜치의 커밋을 그대로 보존한다.

이유는 이 프로젝트가 **"재현 → 해결 → 수치"를 커밋 단위로 남기는 포트폴리오**이기 때문이다.
커밋을 레이어 단위(Migration → Entity → Repository → Service → Test → Docs)로 나누고 각 커밋
메시지에 "왜 이렇게 짰는지"와 "다른 선택지를 왜 안 골랐는지"를 적는데, 스쿼시하면 그 기록이
한 덩어리로 뭉개져 사라진다. `docs/improvements/`의 개선 기록이 커밋을 참조하는 경우도 있다.

대가로 `main` 히스토리가 길어지고, 커밋 단위로는 빌드가 깨지는 중간 상태가 생길 수 있다
(예: API 요청 DTO가 바뀌면 호출부를 모두 고치는 커밋까지 테스트 컴파일이 실패한다 — Task 022).
그래서 CI는 PR 단위로 검증하며, `git bisect`를 쓸 때는 머지 커밋 기준으로 봐야 한다.

- `gh pr merge <번호> --merge` ✅ (스쿼시 금지, Task 021의 PR #17과 Task 022의 PR #18이 이 방식)
- `gh pr merge <번호> --squash` ❌
- `gh pr merge <번호> --rebase` ❌ (커밋은 보존되지만 머지 커밋이 없어져 브랜치 경계가 사라진다)

### Fast-Forward 병합
선형 히스토리 유지, 간단한 변경사항에 적합. **`main`으로 들어오는 PR에는 쓰지 않는다** —
브랜치 경계가 사라져 "이 커밋들이 어느 작업이었나"를 알 수 없게 된다.

### No-Fast-Forward 병합
병합 커밋으로 브랜치 히스토리 보존. **TIKKIT의 기본 전략이다.**

### Squash 병합
여러 커밋을 하나로 통합. **이 프로젝트에서는 쓰지 않는다** (위 정책 참조).
로컬 작업 브랜치 안에서 "오타 수정" 같은 의미 없는 커밋을 정리할 때만 `git rebase -i`로
직접 묶고, `main`으로 병합할 때는 쓰지 않는다.

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
/git:merge --no-ff feature/user-auth    # No-fast-forward (기본 전략)
```

PR을 머지할 때도 같다 — 커밋을 합치지 않는다.

```bash
gh pr merge 18 --merge --delete-branch   # ✅
gh pr merge 18 --squash                  # ❌ 커밋 기록이 뭉개진다
```

이 커맨드는 Git 병합의 모든 복잡성을 처리하면서도 안전하고 직관적인 인터페이스를 제공합니다.
