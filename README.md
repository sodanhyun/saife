# SAIFE (세이프)

소규모 제조 사업장 산재 예방을 위한 **위험성평가 AI Agent**.
제4회 경남 AI·SW 경진대회 출품작 (일반부 개인, 분야 01 사회문제 해결형).

> 현장의 안전 문서들은 서로를 기억하지 못합니다. SAIFE는 기억합니다.

위험성평가·위험작업 작업계획서·산업재해조사표를 **하나의 설비 ID** 위에서 잇는다.
사고가 나면 그 설비의 과거 평가·조치·작업계획이 자동으로 소환되고, 작업 브리핑은 그
장소의 위험요인에서 바로 나오며, 타임라인은 그 데이터를 그대로 한 줄로 그린다.

---

## 실행 — API 키가 없어도 됩니다

```bash
git clone <repo> && cd saife
docker compose up -d --build
# → http://localhost:5173
```

이게 전부다. `.env`도, API 키도 필요 없다.

**키가 없으면 데모 모드로 내려간다.** 모델의 문장 생성만 고정 스크립트로 대체되고,
**도구 호출과 데이터 조회는 실제로 실행된다** — 미이행 조치도, 위험성 등급도,
MSDS 노출기준도 전부 진짜 조회 결과다. 화면에 데모 모드임이 표시된다.

실측(빈 볼륨 기준, 2026-09-21): **빌드 후 기동 22초**, 공공 데이터 10,351건 자동 적재.

### 실제 모델로 돌리려면

```bash
cp .env.example .env     # GEMINI_API_KEY 채우기
docker compose up -d --build
```

### 개발자용 (코드를 고칠 때)

```bash
docker compose -f docker-compose.dev.yml up -d   # DB만
cd backend && ./gradlew bootRun                  # :8080
cd frontend && npm install && npm run dev        # :5173
```

DB 스키마는 Flyway가 만든다(`backend/src/main/resources/db/migration`).
`ddl-auto: validate`라 엔티티만 고치고 마이그레이션을 안 쓰면 부팅에 실패한다.

---

## 화면

| | 화면 | 하는 일 |
|---|---|---|
| **UC1** | 사진 판독 | 현장 사진에서 **빠진 안전조치**를 찾는다. 등급은 룩업테이블이 정하고 채택은 사람이 한다 |
| **UC3** | 작업계획서 | 말로 설명하면 서식을 채우고, **데이터 코어가 모르는 것만** 되묻는다 |
| **UC2** | 사고 등록 | 등록 한 번으로 이력 소환·수시평가 생성·법정 기한 계산이 동시에 일어난다 |
| **UC4** | 설비 타임라인 | 평가 → 작업계획서 → 사고 → 재평가가 하나의 설비 ID 위에 놓인다 |

법정 서식은 각 화면의 "법정 서식" 버튼에서 연다. 인쇄(Ctrl+P → PDF로 저장)하면
그대로 제출 가능한 문서가 된다. 위험성평가표의 항목 순서는 시행규칙 제37조의
3요소(유해·위험요인 / 위험성 결정 내용 / 조치 내용)를 그대로 따른다.

---

## 구성

| | 스택 | 포트 |
|---|---|---|
| backend | Spring Boot 3.4 · Java 21 · Spring AI 1.1.5 (Gemini) | 8080 |
| frontend | React 19 · Vite · TypeScript · Tailwind | 5173 |
| db | PostgreSQL 16 + pgvector | 5432 |

루트 패키지 `io.saife`. 모노레포(git 1개).

---

## 검증

```bash
cd backend && ./gradlew test                 # 단위 15건
cd docs/experiments
python reset_demo_data.py                    # 시드 상태로 복원
python uc3_smoke.py                          # 대화형 작업계획서 (도구 6종)
python uc2_smoke.py                          # 사고 → 이력 소환 → 수시평가 → 기한
python uc1_smoke.py                          # 사진 판독 → 채택률
python edge_cases.py                         # 잘못된 입력·없는 자원 23건
```

QA 결과와 고친 것들: [`docs/qa-report-20260921.md`](docs/qa-report-20260921.md)

---

## 이 리포를 만지기 전에 읽을 것

- **`CLAUDE.md`** — 구조·규칙·대회 제약
- **`.claude/rules/ai-tool-calling.md`** — Gemini 연동. `ai/config/`의 클래스들을 지우면 안 되는 이유
- **`.claude/rules/public-api-integration.md`** — 공단 API `callApiId` 게이트웨이 함정
- **`.claude/rules/risk-domain.md`** — 위험성평가 법·제도 제약
- **`.claude/rules/sse-streaming.md`** — SSE 봉투 규약
- **`.claude/rules/deployment.md`** — 패키징·무대 기동 체크리스트

---

## 데이터

공공 API 수집 결과 **10,351건**을 `backend/src/main/resources/seed/`에 동봉했다
(사고사망 2,940 · KOSHA GUIDE 1,039 · 국내재해사례 6,372). 기동 시 테이블이 비어
있으면 자동 적재된다. **무대와 심사 환경에서 외부 API를 호출하지 않는다.**

데이터를 갱신하려면 서비스키를 `.env`에 넣고:

```bash
curl -X POST http://localhost:8080/api/admin/public-api/crawl
```

크롤러는 페이지마다 체크포인트를 남겨 중간에 끊겨도 이어받는다(쿼터가 KST 자정 리셋).

이미지 데이터셋(AI Hub 샘플)은 **리포 밖 상위 폴더**(`../`)에 둔다. 커밋 금지.

---

## 알려진 제약

- **인증이 없다.** 가상 사업장 1곳에 역할 2종이라 로그인 UI를 만들지 않았다.
  `/api/**`가 열려 있으므로 **공개 네트워크에 띄우지 않는다.**
- 사진 판독의 **협착(CAUGHT) 축**은 정지 사진에서 판정이 어렵다(사전 판독 8장 중 0건).
  정지 사진은 기계의 동력 상태를 담지 못한다 — 이 한계는 숨기지 않고 기재한다.
- 사업장·설비·서식·인물은 **전부 가상**이다.

---

## 라이선스·출처

공공 데이터: 한국산업안전보건공단(공공데이터포털), 법제처 국가법령정보.
이미지 데이터셋: AI Hub (재배포 조건 확인 필요).
사용 모델·라이브러리·데이터는 **별지2 출처·AI 활용 신고서에 전부 기재한다.**
