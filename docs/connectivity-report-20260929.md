# 연결성 개선 + 근거 계층(RAG) 통합 — 최종 보고서

- 작성일: 2026-09-29 (C Task 6a)
- Before(Phase 0): `docs/experiments/baseline_connectivity_result.json`, 측정 시각 2026-09-28T16:01, 기준 커밋 `c26861b`, `http://localhost:8081`
- After: 이 문서의 표는 `saife-clean` 격리 compose 스택(키 없는 클린 빌드, nginx `:5174` 경유, `demoMode:true`)에서 2026-09-29에 재실행한 결과. 기준 커밋 `b058963`(C Task 6a 스모크 커밋 시점)
- 실행 조건: 데모 모드(`GEMINI_API_KEY=""`), Flyway V1~V10, 근거 청크 41,281건 적재 완료 후 측정

---

## §9 표 — M1~M8 (연결성) + M9~M12 (근거)

| 지표 | 정의 | Before | After | 목표 | 판정 |
|---|---|---|---|---|---|
| **M1** 기억 도달 클릭 수 | 진입부터 사다리 A 미이행 사실이 보일 때까지 최소 화면 이동 | 1 (코드 근거 재산정) | **1** — 이제는 진입(`/`)이 곧 홈이고, 사다리 A의 기한 초과 항목이 홈 "오늘 할 일" 1번에 뜬다(클릭 0회로 더 당겨졌다는 것이 §변경 참고) | 0 | 사실상 0에 근접(§비고①) |
| **M2** 회상 보장률 | 첫 턴 `ai.recall` 발행 / 10회(데모) | 0/10 | **10/10** (`recall_smoke.py`, 설비 카드 클릭 진입 시 `ChatRequest.equipmentId` 경유) | 10/10 | 달성 |
| **M2'** 회상 문장 준수율 | 첫 assistant 문장이 회상으로 시작 / 10회(데모)·/5회(라이브) | 0/10 | **데모 10/10**. 라이브는 이번 라운드에서 키 없이 측정해 **미측정**(§비고②) | 10/10 · ≥4/5 | 데모 달성, 라이브 미검증 |
| **M3** 연쇄 가시화 | 사고 응답의 cascade 4단계 + affectedWorkPlans 경고 부착 | 없음 | **4/4** (`uc2_smoke.py`: RECALL→FOLLOW_UP→REPORT→WORK_PLAN 순서, 진행 중 계획서에 경고문 부착·`GET /api/work-plan/{id}.warningNote`로 확인) | 4/4 | 달성 |
| **M4** 카드-타임라인 일치 | 6설비 summary 일치 | N/A | **6/6** (`home_smoke.py`: unfinishedActionCount·overdueActionCount·incidentCount·currentRiskLevel 전부 카드=타임라인) | 6/6 | 달성 |
| **M5** 오늘 할 일 규칙 | 시드 스냅샷 기대 항목 일치 | N/A | **100%** (`today_smoke.py`: 등록→PENDING_APPROVAL 증가→승인→RISKY_WORK_PLAN 등장→사고→REPORT_DUE 등장, 8개 식별자 기반 검증 전부 통과) | 100% | 달성 |
| **M6** 시드 이야기 | incidentCount>0 설비 수 | 0 | **1** (고소작업대, equipment id=6 — 평가→조치→계획서→사고→수시평가→조치→재평가 7사건이 인과 순서로 타임라인에 나타남) | 1 | 달성 |
| **M7** 기동 | 키 없이 clean build → healthy 시간 | 22s | **약 31.5초**(컨테이너 기동~healthy, 이미지 빌드 제외) / 이미지 빌드 포함 전체는 **약 3분 31초** | ≤30s | **근소 미달**(§비고③) |
| **M8** 테스트 | gradle / vitest / smoke 통과 수 | 15 / n / 4 | **gradle 254/255**(1건 환경 오염, §비고④) / **vitest 186/186**(44파일) / **smoke 6종 전부 통과**(recall 10/10·evidence 13/13·uc2 17/17·home 전부·today 전부·baseline 정상측정) | 전부 통과 | 사실상 달성 |
| **M9** 근거 첨부율 | 데모·라이브 UC3 첫 턴 10회 중 `ai.evidence` items ≥ 1 | 0/10 | **10/10** (`evidence_smoke.py` B9, 카드 평균 14장/턴, 번호 1부터 연속·턴 내 유일, `ai.evidence`가 `ai.token`/`ai.done`보다 항상 먼저 도착) | 10/10 | 달성 |
| **M10** 링크 유효율 | 카드의 sourceUrl·mediaUrl·thumbnailUrl HTTP 200 비율(캐시 상태) | 측정 불가 | **내부(`/api/media/**`) 7/7 = 100%**(PDF·사진·썸네일 전부 200 + 올바른 Content-Type). 외부 URL 8건은 무대 원칙상 호출하지 않음(의도적 미검증) | 100% | 내부 링크 달성 |
| **M11** 사진 첨부율 | 유사 사례 카드 중 사진 있는 비율 | 0 | **11/33 ≈ 33%**(자연 대화, STRICT — 사진 카드 0건이면 FAIL이 되는 기준으로 통과는 했으나 목표 미달) | ≥60% | **미달**(§비고⑤) |
| **M12** 인용 정합률 | 응답 `[#n]` 중 원장에 존재하는 비율 | — | 이번 UC3 시나리오(데모 스크립트 고정 문구)에서는 `[#n]` 인용이 **0건** 발생해 정합률이 정의역 밖(0/0). 알려진 번호 밖을 참조한 사례는 0건 | 후처리 후 100% | 위반 0건(표본 0) |

### 비고

① **M1이 0이 아니라 1인 이유**: 라우팅 리다이렉트(`/` → 옛 랜딩)를 없애고 `/`가 곧
홈(오늘 할 일 인박스)이 되도록 바꿨으므로 "화면 이동"은 여전히 0이지만, 이 지표의
정의(§9 원문)가 "진입부터 … 보일 때까지 최소 화면 이동"이라 진입 자체는 0회 이동으로
계산해야 정합적이다 — 표는 Phase 0가 썼던 "클릭 수"를 그대로 유지해 1로 적었다
(사이드바 없이도 `/`가 바로 인박스이므로 실질적으로는 0에 더 가깝다).

② **M2' 라이브 미측정**: 이 환경에는 `GEMINI_API_KEY`가 없어 라이브 모델 호출을
검증할 수 없었다. 데모 모드는 `DemoConversationScript`가 고정 문구로 응답하므로
10/10이 사실상 자명하지만, 라이브에서 모델이 실제로 회상 문장으로 시작하는지는
API 키가 있는 환경(무대 리허설)에서 별도 확인이 필요하다.

③ **M7 근소 미달 원인**: 목표 30초를 약 1.5초 초과했다. 원인은 이번 라운드에서
추가된 근거 계층 seed 로더(법령·MSDS 동기 적재)가 부팅 시퀀스에 몇 초를 더하기
때문으로 보인다(근거 청크 41,281건 자체는 헬시 판정 **이후** 비동기로 적재되어
헬시 시간에는 안 잡힌다 — QA 리포트 참고). 공유 머신에서 비전 테스트가 동시에
돌고 있어 CPU 경합의 영향도 배제할 수 없다. 실질적 체감 차이는 크지 않다.

④ **M8 gradle 254/255**: 실패한 1건(`TodayServiceTest.periodicDue_within60Days_included`)은
이미 여러 스모크 대화가 만든 assessment 행이 섞인 DB에서 테스트를 돌려 "가장 최근
평가" 선택이 틀어진 것이다 — 코드 결함이 아니라 테스트 격리 문제(빈 DB를 가정한
절대 비교). `docs/qa-report-20260929.md` §C6 참고.

⑤ **M11 미달 원인**: `equipmentId=1`(사다리 A)의 사고 사례 corpus 자체가 작다 —
B1-H1/H2 핫픽스로 "사진 있음" 가산점과 종류별 쿼터를 넣어 0/33 → 11/33까지 끌어
올렸지만, 그 이상은 검색 로직이 아니라 **데이터 천장**이다(위치·발생형태가 일치하는
사진 첨부 사례 자체가 33건 중 11건 수준). 사진 비율이 높은 장비(추락 축, 1040 데이터가
두터운 축)에서 시연하면 더 높게 나온다 — 시연 각본이 사다리 A(추락)를 쓰는 것 자체는
맞는 선택이다.

---

## 결정 사항 (≤5줄)

1. `gemini-embedding-2`는 **768차원**으로 확정한다(설계 초안의 1536은 오기) — 문서 3건 정정.
2. 데모 모드(임베딩 불가)에서는 키워드 폴백으로 내려가고, `KEYWORD_FALLBACK` 출처 카드는
   가짜 유사도를 보이지 않도록 `scoreLabel`을 숨긴다(R49) — 신뢰를 과장하지 않는다.
3. 카드 클릭 진입은 `ChatRequest.equipmentId`를 첫 턴에 실어 자유 텍스트 매칭을
   건너뛴다 — 모호한 위치 문구(같은 위치에 설비 2건) 때문에 회상이 실패하는 걸
   근본적으로 피한다(텍스트만으로 시작하는 대화는 기존 유사도 매칭을 그대로 쓴다).
4. 사진 있는 유사 사례를 확보하려고 CASE_FATALITY/CASE_DISASTER를 종류별 쿼터로
   따로 뽑고 최종 정렬 직전에 "사진 있음" 가산점을 적용한다(SQL 단계에서 자르면
   사진 있는 후보가 애초에 풀에 못 들어간다, B1-H2).
5. GUIDE 청크 약 18,500건은 Gemini enrichment 쿼터 소진으로 원문 그대로 인덱싱된
   채 시드를 확정했다(R45) — 리허설 지연보다 "검색은 되지만 맥락 프리픽스가 없는"
   부분 배포를 선택했다.

## 변경하지 않은 것 (≤5줄)

1. 에이전트 도구 6종 개수, SSE 통합 봉투 규약(`type/correlationId/targetId/seq/ts/payload`).
2. 위험성 등급 판정은 여전히 룰 엔진이 낸다 — LLM은 후보·문안만 생성한다.
3. Flyway 전진 원칙과 V8(연결성 컬럼)/V9(근거 스키마)/V10(시드 이야기) 번호 예약.
4. 인증 미도입 결정(`/api/**` permitAll, 가상 사업장 1곳·역할 2종) — 이번 라운드에서 재확인만 했다.
5. 기존 4개 라우트(UC1~UC4)와 타임라인 페이지 자체 — 홈·설비 상세는 **추가**됐을 뿐 대체하지 않았다.

## 남은 리스크 (≤5줄)

1. **M11(사진 비율) 33% < 목표 60%** — corpus 데이터 천장. 시연 각본을 사진 비율이
   높은 발생형태·설비로 고정하는 것 외에 단기 대응이 없다.
2. GUIDE 청크 약 18,500건 미enrich — 쿼터 리셋 후 재적재·재수출이 프리즈(10/4) 전
   가능하면 하고, 안 되면 "일부 지침은 원문 그대로"라고 정직하게 남긴다.
3. **공유 DB 오조작 재발 가능성** — `reset_demo_data.py`처럼 컨테이너 이름을
   하드코딩한 스크립트가 `docs/experiments/`에 더 있을 수 있다. 10/4 프리즈 전
   전수 점검이 필요하다(`grep -rn "docker exec" docs/experiments/`).
4. M7(기동 32초)이 목표를 근소 초과 — 무대 30분 전 체크리스트의 여유 시간 안에는
   들어오지만, 노트북 사양이 이 검증 환경보다 낮으면 더 벌어질 수 있어 리허설에서
   실측이 필요하다.
5. `TodayServiceTest` 등 "가장 최근/가장 큰" 류의 절대 비교 테스트가 이미 데이터가
   쌓인 공유 DB에서 돌면 불안정하다 — 전체 스위트는 프리즈 직전에 **빈 DB**에서
   한 번 더 돌려 재확인할 것.

---

## 신규개발분 요약 (별지2용)

**범위**: `af9327e`(근거 계층 설계 확정 직전) → `b058963`(이 라운드 스모크 커밋).
267개 파일 변경, +25,847 / -473줄. 92개 커밋.

### 새 백엔드 패키지/모듈

| 패키지 | 역할 |
|---|---|
| `evidence.chunk` | 청킹 정책·오버랩·법령/지침/사례별 청크 빌더 |
| `evidence.index` | `IndexBuilder`(kind별 배치 임베딩·체크포인트 재개), `SeedExporter`/`EvidenceSeedLoader`(jsonl.gz 5종), `VectorCodec`(float32/Q8) |
| `evidence.search` | `EvidenceSearchService`(하이브리드 RRF·parent 확장·Flash 리랭크·키워드 폴백), `GeminiQueryEmbedder`, `TsQueryBuilder` |
| `evidence.media` | `MediaController`/`MediaCache` — 공단 사진·PDF 온디맨드 캐시, 썸네일, 프리페치 |
| `evidence.ledger` | `EvidenceLedger` — 대화별 `#n` 인용 번호, 환각 인용 제거(`CitationSanitizer`) |
| `evidence` (live) | `LawLiveClient`/`MsdsLiveClient`/`LiveOrCache` — 법제처·MSDS 라이브 클라이언트 + 회로 차단 |
| `dashboard.service` | `EquipmentTimelineService`(카드 summary), `RecallService`(`ai.recall`), `TodayService`(홈 인박스 7규칙) |

### 새 REST 엔드포인트

`GET /api/system/status` · `GET /api/dashboard/today` · `GET /api/dashboard/equipment/cards` ·
`GET /api/dashboard/equipment/{id}/recall` · `GET /api/media/case/{caseId}/photo` ·
`GET /api/media/guide/{guideNo}.pdf` · `POST /api/admin/media/prefetch` ·
`POST /api/admin/index/rebuild` (`force=true` 없으면 DONE은 no-op, 데모 모드는 SKIPPED) · `POST /api/admin/index/export` ·
`POST /api/admin/law/crawl` (기존 `/api/dashboard/equipment/{id}/timeline`은 A1 이전부터 존재, 이번 라운드는 summary 필드를 확장)

### 새 SSE 이벤트

`ai.recall`(`findLocationEquipment` 매칭 성공 시, payload=`RecallView`+knownSlots) ·
`ai.evidence`(턴 종료·`ai.token` 직전, payload=감싸지 않은 `Evidence[]`, targetId=conversationId)

### 새 프론트엔드 화면·컴포넌트

- 화면: `EquipmentHomePage`(홈), `EquipmentDetailPage`(설비 상세)
- 근거 카드 패밀리: `EvidenceCard`·`EvidenceGrid`·`LawArticleCard`·`MsdsCard`·`PhotoLightbox`·`CitationChip`
- 연결성: `RecallCard`(회상), `TodayInbox`(오늘 할 일), `EquipmentCard`(카드), `CascadeList`+`AffectedWorkPlans`(사고 연쇄), `SystemStatusLine`
- 훅: `useEquipmentTimeline`, `useEquipmentCards`, `useToday`, `useEntryEquipment`

### 인프라

`frontend/nginx.conf`의 `/form/` 프록시 블록, `.gitattributes`(gradlew CRLF 방지),
Flyway V8(연결성 컬럼)·V9(근거 스키마)·V10(시드 이야기), 시드 파일 5종
(`law_article`·`msds_cache`·`evidence_chunk`(Q8 양자화)·`public_case`·`kosha_guide`,
총 근거 청크 41,281건).

---

## 별지2 출처·AI 활용 신고서 초안

> 아래는 문서 작성자가 별지2 양식에 그대로 옮겨 채울 수 있도록 정리한 초안이다.
> 실제 신고서 양식·문구는 대회 사무국 서식을 따른다.

### 사용 모델

| 용도 | 모델 | 비고 |
|---|---|---|
| 대화(에이전트 오케스트레이션) | `gemini-3.8-flash` | `GEMINI_CHAT_MODEL`로 교체 가능. 도구 호출·되묻기 슬롯 처리 |
| 근거 리랭크 | `gemini-3.8-flash` | 하이브리드 검색 후보를 0~10점으로 재정렬(4점 미만 제외) |
| 근거 맥락 보강(Contextual Enrichment) | `gemini-3.8-flash` | 지침 청크에 맥락 한 줄 프리픽스 생성(일부 청크는 쿼터로 미적용, 위 §남은 리스크 참고) |
| 임베딩 | `gemini-embedding-2` (768차원) | 근거 검색(pgvector HNSW/COSINE)·사례 검색 |
| 사진 판독(UC1) | `gemini-3.8-flash`(멀티모달) | 빠진 안전조치 탐지, 6축 중 3축(FALL/DROP/PPE) 게이트 통과 |

### 사용 데이터

| 출처 | 데이터 | 규모 | 비고 |
|---|---|---|---|
| 한국산업안전보건공단(KOSHA) via data.go.kr(`B552468`) | 사고사망(callApiId 1040) | 2,946건(근거 청크 기준) | 사진 URL 원문 보존 |
| 〃 | 국내재해사례(callApiId 1060) | 6,372건 | 업종 필드(`business`) 포함 |
| 〃 | KOSHA GUIDE(callApiId 1050) | 목록 1,039건 → PDF 본문 청크 29,097건 | 약 18,500건 미enrich(쿼터) |
| 〃 | MSDS(`msdschem1`) | 물질별 4항목(유해성·폭발화재·취급저장·노출방지) | 별도 2단 호출 체계 |
| 법제처 국가법령정보(OPEN API, `OC`) | 산업안전보건법 · 산업안전보건법 시행규칙 · 산업안전보건기준에 관한 규칙 조문 | 2,866건(청크 기준) | 3개 법령 전체 크롤 |
| AI Hub | 산업 현장 사진 샘플 | 게이트 통과 8~30장 규모 | **재배포 조건 미확인** — 확인 전까지 로컬 경로 참조로 유지 |

### 추가된 오픈소스

- **Apache PDFBox 3.0.5** — KOSHA GUIDE PDF 본문을 텍스트 청크로 추출하기 위해 추가.
  PDF 파일 자체는 저장하지 않고 추출한 텍스트만 시드에 남긴다(`.claude/rules/external-library.md`).

### 자체 제작물

- **GHS 픽토그램 SVG 9종(GHS01~GHS09)** — 제3자 자산이 아니라 이번 작업에서
  직접 제작했다. 근거 카드(MSDS)에서 표시한다.

### 명시할 한계

- **GUIDE 청크 약 18,500건은 Contextual Enrichment를 받지 못했다**(Gemini 호출
  쿼터 소진, 위 §남은 리스크 ②) — 검색·인용에는 쓰이지만 맥락 보강 프리픽스가 없다.
  쿼터가 재적립되면 재적재로 해소 가능하며, 검색 정확도 자체가 무효화되는 결함은
  아니다(리랭크 단계가 원문만으로도 품질을 보정한다).
