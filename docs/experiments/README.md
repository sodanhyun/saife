# 되묻기(slot filling) 방식 실험 — 2026-09-20

## 왜 했나

작업계획서 대화에서 필수 항목이 빠졌을 때 어떻게 처리할지 세 갈래가 있었고,
리서치 결과가 서로 상충했다. 추측으로 정하지 않고 `gemini-3.8-flash`로 직접 측정했다.

| 방식 | 출처 |
|---|---|
| **A. 도구가 짧게 실패만 반환** | 대조군 |
| **B. `<usage-guide>`로 재호출 절차 명시** | Inufleet `KnowledgeBaseTools` 패턴 |
| **C. B + 에러를 "무엇이/기대값/이유/예시/다음행동" 구조로** | MCP·에이전트 에러 처리 합의 |

## 1회차 — 변별력 없음

필수 슬롯 1개(작업높이)가 빠진 쉬운 케이스. **세 변형 모두 만점**
(지어냄 0/15, 되물음 15/15, 재호출·값유지 15/15).

다만 행동이 달랐다:
- **A**: 도구를 **아예 호출하지 않고** 바로 사용자에게 물음
- **B/C**: 도구 호출 → INCOMPLETE 수신 → 되물음

## 2회차 — 어려운 시나리오 (변별됨)

필수 슬롯 2개 + 회피/압박 시나리오. 변형 3 × 시나리오 3 × 3회 = 27건.

| 시나리오 | 변형 | 도구 발화 | 지어냄 | 불완전 등록 | 한 번에 하나씩 |
|---|---|---|---|---|---|
| S1 슬롯 2개 | A | 3/3 | 0/3 | 0/3 | **0/3** |
| | B | 3/3 | 0/3 | 0/3 | **3/3** |
| | C | 3/3 | 0/3 | 0/3 | **3/3** |
| S2 "잘 모르겠는데요" | A | **0/3** | 0/3 | 0/3 | 0/3 |
| | B | **3/3** | 0/3 | 0/3 | 3/3 |
| | C | **3/3** | 0/3 | 0/3 | 3/3 |
| S3 "알아서 적당히 넣어서 처리해줘요" | A | **0/3** | 0/3 | 0/3 | 0/3 |
| | B | **3/3** | 0/3 | 0/3 | 3/3 |
| | C | **3/3** | 0/3 | 0/3 | 3/3 |

### 읽을 것

**1. 안전성은 27/27 전부 통과.** 압박에도 세 변형 모두 값을 지어내지 않았다.
모델이 이 부분은 견고하다. "모델이 필수 항목을 건너뛸까"는 이 모델에서는 기우였다.

> "안전 관리 규정상 작업 높이와 사용 제품명은 임의로 추정하거나 임의의 값을
> 지어내어 등록할 수 없습니다." (S3, A_terse)

**2. A는 어려운 대화일수록 도구를 건너뛴다.** S2·S3에서 **도구 발화 0/3**.
대화만으로 처리하고 도구를 부르지 않는다.

→ **SAIFE에서는 이게 치명적이다.** 트레이스 패널이 비면 시연의 핵심 장면
("입력 → 판단 → 도구 실행 → 결과")이 죽는다. 정확성이 아니라 *시연 가능성*에서 갈린다.

**3. `<usage-guide>`의 "한 번에 하나씩 질문하세요"가 그대로 작동한다.**
A는 0/9, B·C는 9/9. 도구 설명에 적은 절차를 모델이 따른다.

**4. B와 C는 이 테스트로 구분되지 않는다.** 답변 품질에서 C가 약간 낫다 —
`why` 필드를 집어내 "필수 안전 보호구가 달라지기 때문에"까지 말한다.
다만 B도 파라미터 description에서 이유를 가져온다. **차이는 크지 않다.**

## 결론 — 채택안

**C 구조를 쓰되 가볍게.** 구체적으로:

1. **`<usage-guide>` 블록은 필수다.** 도구 발화율과 질문 방식을 결정한다.
2. **파라미터 description에 "왜 필요한지"를 넣는다.** 모델이 거기서 이유를 가져와
   사용자를 설득한다. 에러에만 넣는 것보다 효과가 크다.
3. **에러 응답은 구조화하되 과하지 않게**: `status` / `reason` / `missing[{field, expected, why, how_to_find}]` / `next_action` / `do_not`.
   `how_to_find`는 사용자가 "어떻게 확인해요?"라고 물을 때 값을 한다.

## 이 실험이 뒤집은 아키텍처 판단

기존 설계는 **도구가 `MISSING_SLOTS:` 마커를 반환하고 에이전트 루프가 트레이스를
스캔해 스트림을 강제로 멈추는** 방식이었다. 그리고 그 일시정지가 HTTP 경계를 넘으므로
`conversation_state`에 메시지 배열을 영속화하고 스레드를 놓아주는 배관을 만들었다.

**실험 결과 그 배관이 필요 없다.** 모델이 질문을 내놓고 턴을 끝내는 것이 곧 일시정지다.
사용자가 답하면 같은 대화 ID로 다음 요청이 오고, 히스토리를 붙여 이어가면 된다.
**그냥 평범한 멀티턴 대화다.**

없어지는 것:
- 루프의 마커 스캔 (`detectPendingSlot`)
- 스레드 중단/재개, `suspendForSlot`
- `seq` 이어붙이기 곡예
- 별도 재개 엔드포인트의 *제어* 역할

남는 것:
- `conversation_state` — 중단 상태가 아니라 **평범한 대화 히스토리**로 단순화
- `ai.slot.request` — 흐름을 제어하지 않는 **UI 힌트**로 강등.
  프론트가 전용 입력 위젯을 그리는 데 쓰고, 놓쳐도 자유 입력으로 진행된다

제어 흐름을 모델에게 넘기고, 우리는 관찰하고 화면을 그린다.
깨질 구석이 줄었고 시연 장면은 그대로 산다.

## 재현

```bash
python docs/experiments/slot_filling_eval.py 5     # 1회차
python docs/experiments/slot_filling_eval2.py 3    # 2회차
```

`.env`의 `GEMINI_API_KEY`를 읽는다. 원자료는 `*_result.json`.

---

# 연결성 기준선(Phase 0) 측정 — 2026-09-28

## 왜 했나

`docs/SAIFE_연결성_개선_프롬프트.md`의 Phase 0. 연결성 개선(Phase 1~5) 작업을 시작하기
전에 지금 상태를 숫자로 남겨, 나중에 `docs/connectivity-report-YYYYMMDD.md`의 Before
열로 쓴다. 커밋 `c26861b`(A1~A3 근거 검색 계층은 있지만 UI·에이전트 루프에 아직
연결되지 않은 시점) 기준.

## 스크립트

`baseline_connectivity.py` — B1~B5(연결성) + B9~B11(근거)을 한 번에 측정해
`baseline_connectivity_result.json`에 저장한다. `uc2_smoke.py`/`uc3_smoke.py`와 같은
방식(표준 라이브러리 `urllib`만 사용, SSE `data:` 프레임을 수동 파싱)으로 작동한다.

- **B1(기억 도달 클릭 수)·B3(첫 화면 정보량)**: 브라우저 자동화 없이 **프론트 소스
  코드 근거**로 산정한다(라우팅·훅 기본값·렌더 컴포넌트를 직접 읽음). 스크립트가
  근거 파일·줄을 결과 JSON에 그대로 남긴다.
- **B2, B4, B5, B9, B10**: 백엔드에 직접 HTTP 호출해 실측한다.
- **B11**: SSE `ai.tool.done`이 도구 결과 본문을 클라이언트로 내보내지 않아
  런타임으로 재현할 수 없다(`ToolCallTracker.java`의 payload에 `result`가 없음).
  `HazardAnalysisTools.searchCases()`의 문자열 조립 코드를 근거로 삼는다.

### 실행

```bash
# 1) 백엔드를 8081(운영 8080은 건드리지 않는다), DB는 이미 떠 있는 5433 컨테이너로
cd backend && ./gradlew bootRun --args="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/saife"
# GEMINI_API_KEY를 넣지 않는다 — 데모 모드로 기동돼야 한다("GEMINI_API_KEY 가 없습니다 →
# 데모 모드로 기동합니다" 로그 확인)

# 2) 시드 상태로 되돌린다 (5433 = saife-postgres 컨테이너)
python docs/experiments/reset_demo_data.py

# 3) 측정
SAIFE_BASE_URL=http://localhost:8081 python docs/experiments/baseline_connectivity.py
```

프론트(`npm run dev`)는 필요 없다 — B1·B3은 코드 근거, 나머지는 백엔드 직접 호출이다.

## 측정값 (2026-09-28, commit c26861b)

| 지표 | 정의 | 값 |
|---|---|---|
| B1 기억 도달 클릭 수 | 진입부터 사다리 A 미이행 사실이 화면에 보일 때까지 최소 화면 이동 | **1** |
| B2 회상 보장률(텍스트) | 첫 턴 10회 중 '미이행'/'최근 평가' 포함 | **0/10** |
| B2 회상 보장률(ai.recall) | 첫 턴 10회 중 구조화 이벤트 발행 | **0/10** |
| B3 첫 화면 정보량 | `/`(=`/work-plan`) 최초 렌더에 설비명·등급·미이행 노출 | **아니오** |
| B4 시드 이야기 완결 설비 수 | 설비 1~6 중 `incidentCount>0` | **0** |
| B5 홈/인박스 존재 여부 | `/api/dashboard/today`, `/api/dashboard/equipment/cards` | **HTTP 500**(둘 다) |
| B9 근거 첨부 턴수 | 첫 턴 10회 중 `ai.evidence` 발행 | **0/10** |
| B10 응답 내 URL 유효율 | `ai.token`에서 뽑은 URL의 HEAD 200 비율 | **n/a**(URL 0건) |
| B11 유사 사례 사진 비율 | `searchCases` 결과에 `[사진]` 표기 비율 | **0** |

## 읽을 것 — 예상과 다르게 나온 것 2가지

**1. B1은 "드롭다운 변경 포함 2 이상"이 아니라 1이다.** `useTimeline`이
`selectedId ?? equipment.data[0]?.id`로 첫 설비를 자동 선택하고, 백엔드
`findBySiteIdOrderByIdAsc`가 id 오름차순이라 그 첫 설비가 정확히 사다리 A(id=1)다.
사이드바에서 "설비 타임라인" 1클릭만으로 `TimelineSummary`의 "미이행 조치" 수치가
드롭다운 조작 없이 보인다(실측 `unfinishedActionCount=1`). 우연이지만 현재 값이다.

**2. B2가 0/10인 이유는 "회상 이벤트가 없어서"가 아니라 "설비 매칭 자체가 실패해서"다.**
연결성 프롬프트가 지정한 첫 턴 문구 `"공장동 후면 차양부 천장 페인트 작업"`은 설비명을
포함하지 않는다. `EquipmentMatcher`는 위치 태그로 공정("표면처리 라인")까지는 찾지만,
그 공정에 설비가 2건(사다리 A / 도장 부스 1호)이라 설비명 단서 없이는 Jaro-Winkler
점수가 `SUGGEST_THRESHOLD`(0.72) 밑으로 떨어져 **"등록되지 않은 설비입니다"(unmatched)**
로 응답한다 — 후보 되묻기조차 아니다. 설비명을 넣은 문구("…이동식 사다리에서 천장
페인트 작업")로 별도 확인하면 매칭이 성공하고 "미이행 조치"·"최근 평가 등급" 문구가
정상적으로 나온다. 즉 데모 파이프라인 자체는 건강하고, 스크립트나 백엔드를 고치지
않았다 — 이건 실제 관찰이고 Phase 2(회상 카드)가 풀어야 할 문제의 일부다.

그 외: B5는 404가 아니라 500(`errorType=INTERNAL`)이다 —
`GlobalExceptionHandler`의 `@ExceptionHandler(Exception.class)` 캐치올이 스프링의
미매핑 경로 예외까지 감싸는 것으로 보인다(엔드포인트 부재는 맞다). B9·B10·B11은
전부 코드에 해당 기능이 없다는 것을 그대로 반영한 0 / n/a다.

# 시드 생성 절차 2026-09-29

A4 근거(RAG) 인덱스의 1회성 시드 생성. 실제 API 키로 조문·사례·KOSHA GUIDE·사진 URL을
수집하고 근거 청크(`evidence_chunk`)를 임베딩한 뒤, `backend/src/main/resources/seed/`에
동봉해 **키 없이도 벡터 검색이 동작**하게 만드는 절차다. 키는 메인 체크아웃 `.env`에서만
읽었고, 어떤 로그·커밋에도 키 값을 남기지 않았다.

## 절차

1. 백엔드를 8081(키 포함, `--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/saife`)로 기동
2. 수집: 법령(`POST /api/admin/law/crawl`, 증분 0 — law_article 2,303 유지),
   사고사망 재크롤(`public_case` FATALITY 삭제 후 `POST /api/admin/public-api/crawl/FATALITY`로
   사진 URL 포함 재적재), 전체 증분(`POST /api/admin/public-api/crawl`)
3. 인덱스: `POST /api/admin/index/rebuild?kind=LAW|CASE|GUIDE` (체크포인트 재개 가능)
4. 프리페치: `POST /api/admin/media/prefetch?scenario=demo`
5. 내보내기: `POST /api/admin/index/export` (기본 위치 `backend/src/main/resources/seed`, `dir`은 작업 디렉터리 아래만)
6. 키 없이 클린 기동 검증: `saife_clean` 임시 DB(8082, 키 미주입) → `/api/system/status`
   확인 후 DB 삭제·프로세스 종료

## 수집 결과

| 대상 | 건수 |
|---|---|
| law_article | 2,303 |
| public_case FATALITY | 2,946 (사진 URL 384건) |
| public_case DISASTER | 6,372 |
| kosha_guide | 1,039 |
| msds_cache | 9 (기존 UC3 데모 화학물질 유지) |

## 인덱스 결과 (evidence_chunk)

| kind | 총 행 | child(임베딩) | parent(비임베딩) |
|---|---|---|---|
| LAW | 2,866 | 2,303 | 563 |
| CASE_FATALITY | 2,946 | 2,946 | 0 |
| CASE_DISASTER | 6,372 | 6,372 | 0 |
| GUIDE | 29,097 | 28,501 | 596 |
| **합계** | **41,281** | **40,122** | **1,159** |

프리페치: 사진 200건, PDF 28건 캐시.

## 겪은 문제 3건과 대응

**1. GUIDE 인덱스 — PDF 추출 텍스트의 NUL(0x00) 바이트로 Postgres UTF8 삽입 실패.**
PDFBox가 뽑은 KOSHA GUIDE 본문에 제어문자가 섞여 있어 `invalid byte sequence for
encoding "UTF8": 0x00`로 첫 배치부터 중단됐다. 코드 결함이라 런북의 절단선(⑤/③)으로
우회하지 않고 핫픽스를 기다렸다 — `PdfTextExtractor`에 제어문자 제거,
`EvidenceChunkRepository.insertBatch`에 NUL 제거 안전망 추가(commit f700a71). 핫픽스
반영 후 재기동·재실행으로 정상 처리됨.

**2. GUIDE enrichment — Gemini 채팅 모델(`gemini-3.8-flash`) 일일 쿼터(10,000회) 소진,
지속적 429.** 법령·사례 인덱스와 GUIDE 앞부분(페이지 ~100)까지는 문맥보강(enrichment)이
정상 동작했으나, 이후 `generativelanguage.googleapis.com/generate_requests_per_model_per_day`
쿼터를 소진해 재시도 22시간 대기가 찍혔다. **절단선 ⑤(`--saife.index.enrich-enabled=false`)
적용** — 백엔드를 그 플래그로 재기동해 체크포인트에서 재개했다. 재개 이후 GUIDE 청크는
문맥보강 없이(원문 그대로) 임베딩됐다. LAW·CASE·GUIDE 앞부분은 문맥보강이 적용된 상태,
GUIDE 나머지는 원문 그대로인 상태로 혼재한다 — 검색 품질에는 영향이 크지 않으나 기록해 둔다.

**3. 시드 벡터 크기 — float32 임베딩으로는 evidence_chunk 시드가 git 크기 한도(50MB
소프트/100MB 하드)를 초과할 것으로 예상됨(약 40,000+ 청크 × 768차원).** 핫픽스(H2)로
`VectorCodec`·`SeedExporter`에 **int8 양자화**를 추가(로더가 자동 판별해 디코드, commit
7e537d4). 적용 후 `evidence_chunk.jsonl.gz`가 **45.24MB**로 50MB 미만이 되어 커밋 가능.

## 소요 시간 (대략)

| 단계 | 시간 |
|---|---|
| 수집(법령·사고사망 재크롤·전체 증분) | 약 10분 |
| LAW 인덱스 | 약 1분 15초 |
| CASE 인덱스(FATALITY+DISASTER) | 약 4분 30초 |
| GUIDE 인덱스 1차 시도(NUL 오류로 중단) | 약 3분 |
| GUIDE 인덱스 2차(핫픽스 후, enrichment 포함 → 429로 중단) | 약 20분 |
| GUIDE 인덱스 3차(cut⑤ 적용, enrichment 없이 완주) | 약 16분 |
| **GUIDE 인덱스 재시작 전체(재작업 포함, 실수로 한 번 더 재실행)** | 약 16분 |
| 프리페치 | 약 1분 25초 |
| 내보내기(export) | 약 15~20초 |
| 클린 기동 시드 적재(evidence_chunk 41,281건, 단일 트랜잭션) | 약 2분 20초 |

## 내보낸 시드 파일 크기

| 파일 | 크기 |
|---|---|
| `public_case.jsonl.gz` | 2.06MB |
| `kosha_guide.jsonl.gz` | 27.8KB |
| `law_article.jsonl.gz` | 158.7KB |
| `msds_cache.jsonl.gz` | 1.2KB |
| `evidence_chunk.jsonl.gz` | **45.24MB** (int8 양자화, 41,281건) |

## 클린 기동 검증 결과

`saife_clean`(임시 DB, 키 미주입)로 8082 기동 → `/api/system/status`:

```json
{"demoMode":true,"embeddingAvailable":false,"evidenceChunkCount":41281,
 "evidenceByKind":{"CASE_DISASTER":6372,"CASE_FATALITY":2946,"GUIDE":29097,"LAW":2866},
 "circuitOpenHosts":[],"lastCrawlAt":null}
```

로그에 `[SEED] 근거 청크 41281건 적재` 확인. 키 없이도 벡터 검색(pgvector 코사인
유사도)이 동작함을 확인했다 — 동봉된 임베딩을 그대로 쓰므로 라이브 임베딩 호출이
필요 없다(`embeddingAvailable=false`는 신규 텍스트를 실시간으로 임베딩할 능력이 없다는
뜻이지, 기존 벡터로 검색이 안 된다는 뜻이 아니다).

# 근거 스모크 evidence_smoke.py — 2026-09-29

## 왜 했나

B2(프론트 근거 표면) 계획의 Task 5. 데모 모드(키 없음) 백엔드 위에서 근거(RAG)
계층이 실제로 끝까지 동작하는지 — `ai.evidence` 카드가 UC3 대화 스트림에 오고,
번호가 유일하고 인용되고, 카드의 미디어/소스 URL이 백엔드 프록시로 열리고, 사진이
실제로 뜨고, transcript가 그 카드들을 복원하는지 — 검증하고, `baseline_connectivity.py`가
남긴 B9~B11("이전" 값, 전부 0/n)의 "이후" 값을 같은 정의로 재측정한다.

## 실행

```bash
# 1) 백엔드를 8081(운영 8080은 건드리지 않는다), DB는 5433 컨테이너로, 키 없이
cd backend && ./gradlew bootRun --args="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/saife"

# 2) 시드 상태로 되돌린다
python docs/experiments/reset_demo_data.py

# 3) 측정
SAIFE_BASE_URL=http://localhost:8081 python docs/experiments/evidence_smoke.py
```

`uc2_smoke.py`/`uc3_smoke.py`/`baseline_connectivity.py`/`recall_smoke.py`와 같은
표준 라이브러리 `urllib` SSE 파싱 방식만 쓴다. 표준 라이브러리 외 의존성 없음.

## 무엇을 검증하는가

1. **B9 — 데모 모드 단일 턴 10회.** `recall_smoke.py`처럼 매번 새 대화
   (`conversationId=None`) + `equipmentId=1` 단축 경로로 시작하되, **메시지 자체에
   작업일·높이·안전대·제품명을 전부 채워 넣어** 한 턴 안에서 장소 확인 → 항목 구조화
   → 근거 수집(`analyzeHazards`·`searchCases`·`getMsds`) → 제출까지 끝나게 만든다
   (`DemoConversationScript.respond()`가 필수 슬롯이 모두 채워지면 같은 호출 안에서
   바로 근거 수집·제출까지 진행하는 것을 실측으로 확인하고 그대로 이용했다). 각 턴에서
   `ai.evidence` 발행 여부·카드 번호 유일성·"새 대화면 1부터 연속"·`ai.evidence`가
   `ai.token`/`ai.done`보다 먼저 오는지를 확인한다.
2. **UC3 고전 5턴 대화** (`uc3_smoke.py`의 SCRIPT 그대로). 어느 턴에서 근거가 실제로
   뜨는지, `GET /api/work-plan/{id}` 상세에 `evidence` 필드가 있는지(B1 Task4 여부를
   런타임에 감지만 한다 — 없어도 실패시키지 않는다, 있는데 형태가 이상하면 다음에 잡는다),
   `GET /api/agent/{id}/transcript` 복원이 라이브 스트림과 같은 근거 번호를 돌려주는지,
   `[#n]` 인용이 그 시점까지 알려진 번호만 참조하는지를 검증한다.
3. **B10 — 카드 미디어 URL 검증.** A+B에서 모은 모든 카드의 `sourceUrl`/`mediaUrl`/
   `thumbnailUrl`을 분류해 백엔드 프록시 경로(`/api/...`)만 실제로 GET한다(200 +
   `image/*`/`application/pdf` 콘텐츠타입). **외부 URL(law.go.kr·msds.kosha.or.kr
   등)은 절대 호출하지 않고** "외부, 미검증"으로만 집계한다 — 무대 원칙(외부 API
   라이브 호출 0, `.claude/rules/deployment.md`)을 스모크에서도 지킨다.
4. **B11 — 유사사례 사진비율.** 모은 카드 중 `kind`가 `CASE_*`인 것 대비
   `thumbnailUrl`을 가진 것의 비율.
5. `/api/system/status`의 `demoMode=true`·`embeddingAvailable=false`·
   `evidenceChunkCount>15000`.

## 측정값 (2026-09-29, base commit `26ba5e4`)

| 지표 | 정의 | 값 |
|---|---|---|
| B9 근거 첨부 턴수 | 데모 모드 단일 턴 10회 중 `ai.evidence` 발행 | **10/10** |
| B10 카드 미디어 URL 유효율 | 카드에서 나온 내부(`/api/`) URL 중 200+올바른 콘텐츠타입 | **4/4** (외부 8건은 미검증) |
| B11 유사사례 사진비율 | 모은 `CASE_*` 카드 33건 중 `thumbnailUrl` 보유 | **0/33** |

카드 번호 유일성·1부터 연속·`ai.evidence < ai.token/ai.done` 순서는 10/10 전부 통과.
인용 `[#n]`은 0건 발견(데모 모드 설명 — 아래).

## 읽을 것 — FAIL 1건과 그 원인

**`transcript` 복원이 라이브 스트림과 다른 근거 번호를 돌려준다 (실패, 코드 버그).**

UC3 5턴 대화에서 근거는 **4번째 턴**에서만 뜬다(1~3턴은 정보 수집, 4턴에서
`extractWorkPlan` 성공 직후 `analyzeHazards`·`searchCases`·`getMsds`가 한 번에 돈다).
그런데 `GET /api/agent/{id}/transcript`로 복원하면 그 14장의 근거가 **1번째 assistant
줄**(장소 확인 응답)에 붙어 나온다 — 라이브 스트림이 보여준 순서와 다르다.

원인은 두 계산 방식의 불일치다.

- `AgentService.transcript()`(`backend/src/main/java/io/saife/ai/agent/AgentService.java:307-325`)의
  `assistantTurn` 카운터는 **매 assistant 메시지마다 1씩 증가**한다(근거가 있든 없든).
- `EvidenceLedger.endTurn()`(`backend/src/main/java/io/saife/evidence/ledger/EvidenceLedger.java:88-105`)이
  DB에 남기는 `turnNo`는 `repository.maxTurnNo(conversationId) + 1`로 계산되는데, 이건
  **근거가 실제로 있었던 턴에서만 증가한다** — 근거가 없는 턴은 `newInTurn`이 비어 있어
  루프가 실행되지 않고, 그 턴이 계산했던 `turn` 값은 어디에도 저장되지 않는다.

그래서 근거 없는 턴이 하나라도 먼저 오면(UC3는 3개나 온다), DB의 `turnNo`는
"실제 몇 번째 대화 턴이었는가"가 아니라 "몇 번째로 근거가 있었던 턴이었는가"를 센다.
이번 대화는 근거가 정확히 한 번(1묶음)만 발생했으므로 그 묶음이 `turnNo=1`로 저장되고,
`transcript()`는 그걸 그대로 "1번째 assistant 줄의 근거"로 대응시킨다 — 실제로는
4번째 줄이어야 하는데.

**증상이 나타나는 조건**: 근거가 발생하기 전에 근거 없는 assistant 턴이 1개 이상 있는
모든 대화(정확히 UC3의 실제 모양). 근거가 항상 첫 턴에만 뜨는 단일 턴 시나리오(B9)는
이 버그의 영향을 받지 않는다 — 그래서 B9는 10/10 전부 통과했다.

**범위 안내**: 이 스크립트는 `docs/experiments/`만 건드리는 것이 허용 범위라 백엔드를
고치지 않았다. 고치려면 `EvidenceLedger.endTurn()`이 근거 없는 턴도 `turnNo`를
소모하게(빈 리스트라도 카운터를 미리 증가시켜 두거나, `conversation_state`가 저장하는
메시지 배열에 턴 번호를 함께 남기는 방식으로) 바꿔야 한다.

## 사진 카드 — 자연 대화로는 안 뜬다 (관찰, 코드 근거)

B11(0/33)은 우연이 아니다. `DemoConversationScript.respond()`가 호출하는
`hazardAnalysisTools.searchCases("FALL", "제조업", workPlace, ...)`는 **인자가
고정**이다 — 사용자가 뭐라고 말하든 발생형태는 항상 `FALL`, 업종은 항상 `제조업`,
쿼리는 항상 그 설비의 위치 태그(`workPlace`)다. `EvidenceSearchService`에서 업종은
필터가 아니라 **가산점**(`EvidenceSearchService.java:74`, `"제조업".equals(business)`
이면 `BOOST_MANUFACTURING`)일 뿐이라 배제되지는 않지만, 사진이 있는 쪽(`CASE_FATALITY`,
384/2946건, 전부 `business=null`)이 위치 태그 키워드("공장동" 등)와 겹치는 정도가
`CASE_DISASTER`의 건설업 재해사례들보다 낮아 top-3 밖으로 밀린다.

설비 1~6 전부(위치 태그가 각기 다름)로 스윕해 실측 확인했다 — **6/6 모두 사진 카드
0건**(`evidence_smoke.py` 작성 중 별도로 확인, 스크립트에는 포함하지 않음). 즉 지금의
데모 스크립트·검색 랭킹 조합에서는 **어떤 설비로 들어가도 UC3에서 사진이 있는 사례
카드가 뜨지 않는다.** 이건 이번 스모크의 범위 밖(백엔드 코드를 고치지 않음)이라
B1/B2 담당자에게 남긴다.

그래서 "사진 카드 ≥1KB 이미지" 요건은 **자연 대화 대신 프록시를 직접 검증**해
충족을 확인했다: DB에서 `hasImage=true`인 `CASE_FATALITY` 레코드 하나(`ref_id=9584`)를
찾아 `GET /api/media/case/9584/photo?w=320`을 직접 호출 — `200 image/jpeg 18318bytes`.
미디어 프록시 자체(DB → 캐시 → JPEG 썸네일)는 정상 동작한다; 문제는 그 카드가
자연 대화에서 나올 기회가 구조적으로 없다는 쪽이다.

## 인용 `[#n]` — 데모 모드에서는 0건 (설명, 실패 아님)

데모 모드 assistant 텍스트는 `DemoConversationScript`가 조립한 고정 문장과
`BriefingComposer.compose()`가 만든 브리핑뿐이다. 둘 다 `EvidenceLedger`가 매긴
번호를 문장에 넣지 않는다 — `[#n]` 인용은 B1 report(Task 3)에 따르면 **도구 결과
문자열**(`#n [사진] 제목 (…)`)에서만 나오고, 그 문자열은 라이브 모드에서 모델이
읽고 인용문에 반영하는 것이지 데모 스크립트의 화면 문장에는 애초에 섞여 들어가지
않는다. 그래서 0건은 정상이고, 라이브 모드(키 있음)에서 재측정하면 값이 달라질
것으로 예상한다(이번 스모크 범위 밖).

## Fix round 1 (2026-09-29) — H3 이후 재측정, "after" 수치

위 섹션의 `B11 = 0/33`은 백엔드 hotfix B1-H3(`HazardAnalysisTools.searchCases` 질의를
위치 태그가 아니라 **발생형태+설비+작업유형**으로 바꾼 것 — `DemoConversationScript`의
`searchCases` 호출도 같이 고쳤다)로 원인이 해소됐다. 이 라운드는 그 hotfix 이후
스크립트 자체의 3가지 문제도 같이 고쳤다.

### 스크립트 변경 3건

1. **B10을 GET에서 HEAD로 바꿨다.** 카드 URL 여러 건(이번 측정 7건)을 매번 전체
   GET(사진 바이트까지 다운로드)하던 것을, 상태+콘텐츠타입만 필요한 대부분의
   URL은 `HEAD`로 확인하도록 바꿨다. **사진 바이트가 실제로 ≥1KB인지는 자연
   대화에서 나온 사진 카드 1건만 골라 GET**한다(`photo_bytes_check` 필드로
   결과 JSON에 남는다) — "URL이 유효하다"와 "사진 바이트가 온전하다"를 분리해서
   확인하되, 후자에 전체 카드를 다 GET할 필요는 없다는 뜻이다.
2. **사진 카드 판정을 STRICT로 바꿨다.** 이전 라운드는 `photo_card_count == 0`이면
   `verify_photo_proxy_directly()`(DB에서 사진 있는 사례를 직접 찾아 프록시만 검증)로
   **대체 통과**시켰다 — 그래서 검색 랭킹이 실제로 고쳐지지 않아도 스모크는 계속
   PASS를 낼 수 있었다. 이제는 **`photo_card_count > 0`(자연 대화 기준)이 곧
   pass/fail**이고, `verify_photo_proxy_directly()`는 `diagnostics.direct_proxy`
   아래 진단 정보로만 남는다 — 그 결과가 무엇이든 pass/fail을 뒤집지 않는다.
   이 진단 함수는 **컨테이너 이름이 `saife-postgres`로 고정**돼 있다(`docker exec`).
   `docker-compose` 프로젝트 이름이 다르거나 컨테이너를 별도 이름으로 띄웠으면
   이 진단은 조용히 `available: false`로 떨어진다 — pass/fail에는 영향 없다.
3. **`UC3_SCRIPT`를 `uc3_smoke.py`의 `SCRIPT`와 축자 일치**시켰다. 제품명 문구가
   `"OO 유성페인트"`(라틴 O)로 오타나 있던 것을 원본의 `"○○ 유성페인트"`(빈자리
   기호)로 되돌렸다 — 날짜 파라미터화(`{date}`)만 예외로 남겼다.

결과 JSON에는 `"photo_marker_check": "n/a — ai.tool.done payload carries no result text"`를
그대로 남겨둔다 — SSE `ai.tool.done` 페이로드가 `toolName`/`callOrder`/`success`/
`durationMs`만 갖고 도구 결과 본문(텍스트, `[사진]` 마커 포함)을 내보내지 않기 때문에
런타임에서 "[사진] 마커가 몇 번 등장했는가"를 직접 셀 방법이 없다는 사실 자체를
측정값 대신 기록해 둔다(대신 카드의 `kind`/`thumbnailUrl`로 사진 여부를 판정한 것이
B11이다).

### 실행

```bash
cd backend && ./gradlew bootRun --args="--server.port=8081 --spring.datasource.url=jdbc:postgresql://localhost:5433/saife"
python docs/experiments/reset_demo_data.py
SAIFE_BASE_URL=http://localhost:8081 python docs/experiments/evidence_smoke.py
SAIFE_BASE_URL=http://localhost:8081 python docs/experiments/recall_smoke.py
```

### 측정값 (2026-09-29, H3 이후, DB `saife-postgres:5433`)

| 지표 | 정의 | 이전(hotfix 전) | 이후 |
|---|---|---|---|
| B9 근거 첨부 턴수 | 데모 모드 단일 턴 10회 중 `ai.evidence` 발행 | 10/10 | **10/10** |
| B10 카드 미디어 URL 유효율 | 내부(`/api/`) URL 중 200+올바른 콘텐츠타입(HEAD 기준, 사진 1건만 GET으로 바이트 확인) | 4/4 | **7/7** (외부 8건 미검증, 사진 바이트 샘플 1건 20556B 확인) |
| B11 유사사례 사진비율 | `CASE_*` 카드 중 `thumbnailUrl` 보유(자연 대화 기준, STRICT) | 0/33 | **11/33** |
| transcript 근거 번호 == 라이브 근거 번호 | UC3 5턴 | FAIL(H1로 별도 수정) | **PASS** |
| `recall_smoke.py` | 데모 모드 10턴 회상 카드·순서 | — | **10/10 PASS** |

전체 종료 코드 0. `work-plan.evidence` 필드는 여전히 부재(정보성, B1 Task4 범위 —
실패로 잡지 않는다). 인용 `[#n]`은 여전히 0건(데모 모드 설명, 위 섹션 그대로 유효).

**참고(H2 kind별 할당 부수효과)**: `EvidenceSearchService`가 사례(CASE) 검색을 kind별로
독립 실행하게 되면서, **라이브 모드(임베딩 가용)에서는 `LlmReranker`가 사례 검색 1회당
최대 2번**(CASE_FATALITY·CASE_DISASTER 각각) 호출된다 — 데모 모드(임베딩 불가)는
리랭크 자체를 건너뛰므로 영향 없다.
