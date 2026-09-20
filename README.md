# SAIFE (세이프)

소규모 제조 사업장 산재 예방을 위한 **위험성평가 AI Agent**.
제4회 경남 AI·SW 경진대회 출품작 (일반부 개인, 분야 01 사회문제 해결형).

> 현장의 안전 문서들은 서로를 기억하지 못합니다. SAIFE는 기억합니다.

위험성평가·위험작업 작업계획서·산업재해조사표를 **하나의 설비 ID** 위에서 잇는다.
사고가 나면 그 설비의 과거 평가·조치·작업계획이 자동으로 소환되고, 작업 브리핑은 그
장소의 위험요인에서 바로 나오며, 대시보드는 그 데이터를 그대로 한 줄로 그린다.

## 5분 안에 띄우기

```bash
cp .env.example .env        # GEMINI_API_KEY 등 채우기
docker compose -f docker-compose.dev.yml up -d   # PostgreSQL 16 + pgvector

cd backend && ./gradlew bootRun                  # :8080
cd frontend && npm install && npm run dev        # :5173
```

DB 스키마는 Flyway가 만든다(`backend/src/main/resources/db/migration`).
`ddl-auto: validate`라 엔티티만 고치고 마이그레이션을 안 쓰면 부팅에 실패한다.

## 구성

| | 스택 | 포트 |
|---|---|---|
| backend | Spring Boot 3.4 · Java 21 · Spring AI 1.1.5 (Gemini) · QueryDSL | 8080 |
| frontend | React 19 · Vite · TypeScript · Tailwind | 5173 |
| db | PostgreSQL 16 + pgvector | 5432 |

루트 패키지 `io.saife`. 모노레포(git 1개).

## 모듈

| 유즈케이스 | 패키지 | 상태 |
|---|---|---|
| **UC3** 위험작업 작업계획서 대화형 등록 | `workplan`, `ai/agent`, `ai/tools` | 시연 주인공 |
| **UC1** 위험성평가 (사진 → 빠진 안전조치 탐지) | `core/*`, `ai/vision` | E2E |
| **UC2** 산재 사후 등록 · 이력 소환 | `incident` | 축소판 |
| **UC4** 설비 타임라인 뷰 | `dashboard` | 읽기 전용 |

## 이 리포를 만지기 전에 읽을 것

- **`CLAUDE.md`** — 구조·규칙·대회 제약
- **`.claude/rules/ai-tool-calling.md`** — Gemini 연동. `ai/config/`의 클래스들을 지우면 안 되는 이유
- **`.claude/rules/public-api-integration.md`** — 공단 API `callApiId` 게이트웨이 함정
- **`.claude/rules/risk-domain.md`** — 위험성평가 법·제도 제약
- **`.claude/rules/sse-streaming.md`** — SSE 봉투 규약 + 되묻기 턴(`ai.slot.request`)

## 데이터

데이터셋(AI Hub 샘플)과 공공 API 키는 **리포 밖 상위 폴더**(`../`)에 둔다. 커밋 금지.

공공 API는 전량 로컬 캐시로 내려 `public_case` / `kosha_guide` / `msds_cache` 테이블에
적재한다. **무대에서는 외부 API를 호출하지 않는다.**

## 라이선스·출처

공공 데이터: 한국산업안전보건공단(공공데이터포털), 법제처 국가법령정보.
이미지 데이터셋: AI Hub (재배포 조건 확인 필요).
사용 모델·라이브러리·데이터는 **별지2 출처·AI 활용 신고서에 전부 기재한다.**
