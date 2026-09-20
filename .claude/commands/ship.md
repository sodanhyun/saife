# 폴리레포 커밋 및 PR 생성

변경이 있는 각 리포(frontend, backend, cv)의 모든 변경사항을 빠짐없이 커밋하고 각 리포에 PR을 생성합니다.

## Git 브랜치 전략 (GitHub Flow)

### 브랜치 네이밍 규칙

| 접두사 | 용도 |
|--------|------|
| feature/{설명} | 기능 개발 (main에서 분기) |
| fix/{설명} | 버그 수정 |
| hotfix/{설명} | 긴급 운영 수정 |

- **개인 이름 브랜치 사용 금지** — 위 접두사 패턴으로 전환
- 설명은 영문 kebab-case: `feature/inspection-dashboard`, `fix/report-sse-timeout`

### 현재 브랜치 확인

각 리포 작업 시작 전 반드시 확인:
1. 현재 브랜치가 `feature/`, `fix/`, `hotfix/` 접두사인지 확인
2. 개인 이름 브랜치에 있다면 **경고 후 올바른 브랜치로 전환 제안**
3. `main`에서 직접 작업 중이라면 **새 브랜치 생성 제안**

## 폴리레포 구조

SAIFE는 독립 git 리포 3개로 구성됩니다:
- **`backend/`** — Spring Boot 3 백엔드 (`io.saife`)
- **`frontend/`** — React 19 프론트엔드 (Vite + TypeScript)
- **`cv/`** — 컴퓨터 비전 서비스 (Python)

각 리포는 독립 git 히스토리를 가지므로, 브랜치·커밋·PR을 리포별로 독립 실행합니다.

## 실행 절차

### Phase 1: 상태 확인 (변경 있는 리포 특정)

각 리포 디렉토리에서:
- `git status` — 변경 파일 목록 확인
- `git branch` — 현재 브랜치 확인
- 브랜치명이 규칙 위반이면 사용자에게 알리고 진행 여부 확인

### Phase 2: 리포별 커밋 (변경사항이 있는 리포만)

각 리포 내에서 독립적으로:
1. `git diff` 로 변경 내용 확인
2. Conventional Commits 형식 + 한국어 본문으로 커밋 메시지 작성
3. `.env`, `application.properties`, `application-*.yaml` 등 민감 파일 커밋 방지
4. 변경이 없는 리포는 건너뜀

### Phase 3: PR 생성

**순서 규칙 (backend + frontend 동시 변경 시)**:
1. **backend PR 먼저 생성** (하위 호환 유지 — 새 필드 추가, 기존 필드 유지)
2. **frontend PR 생성**
3. **cv PR 생성** (해당 시)
4. PR 본문에 연관 리포 PR 링크 포함

**PR 설정**:
- base 브랜치: `main`
- Squash Merge 권장 메시지 포함
- 커밋 히스토리 깔끔, 롤백 단위 명확

### Phase 4: 결과 보고

- 각 리포별 PR URL 출력
- 동시 변경 시 **배포 순서 안내**: backend PR 먼저 머지 → frontend → cv

## 충돌 방지 규칙

### 이미 머지된 PR 이후 추가 수정이 필요한 경우

**금지**: 구버전 `main`에서 새 브랜치 분기 후 cherry-pick → `main`과 파일 충돌 발생

**올바른 절차**:
1. 해당 리포에서 `git fetch origin main` 으로 최신 main 반영
2. **최신 main 기준**으로 새 브랜치 분기: `git checkout -b <branch> origin/main`
3. 필요한 변경사항을 직접 작성 (cherry-pick 대신)

### 일반 규칙
- 같은 목적의 수정은 **기존 브랜치에 추가 커밋** (불필요한 브랜치 분기 자제)
- 브랜치 분기 직전에 반드시 해당 리포에서 `git fetch origin` 최신화
- cherry-pick은 네트워크 분리된 환경 등 불가피한 경우에만 사용
- 폴리레포 특성상 cross-repo 의존성은 PR 본문 링크로만 명시 (submodule/workspace 없음)
