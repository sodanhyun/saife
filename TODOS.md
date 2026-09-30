# TODOS — SAIFE

승인된 기획서: `~/.gstack/projects/SAIFE/taeli-unknown-design-20260920-163000.md`

> 2026-09-29 갱신(C Task 6a) — 연결성 개선(회상·연쇄 가시화·오늘 할 일)과 근거 계층(RAG)
> 통합이 끝났다. 아래 블록①·평일 항목 대부분은 이미 구현·검증됐다(체크 반영). 상세:
> `docs/connectivity-report-20260929.md`, `docs/qa-report-20260929.md`.

## 즉시 (사용자 직접)
- [ ] **9/21(월) 오전 사무국 전화 4건** — ① 9/29 이전 개발분 신고 방식 ② 제출물 정확 마감일시
      ③ 발표심사 환경(노트북/현장PC·해상도·네트워크·시간배분) ④ 10/5 공휴일 포털 지원 여부
- [ ] **9/29(화) 12:00 접수 제출** — 신청서·재직확인·동의서. 놓치면 참가 무산
- [ ] AI Hub 약관 확인 — few-shot 이미지를 저장소에 포함 가능한지
- [ ] 10/5 문서 작업량 조정 (리뷰 산정 15~23h)
- [ ] 건설 데이터 44~55장을 안전조치 범주로 재라벨링

## 셋업 (9/21~9/23 저녁)
- [x] 리포 스캐폴딩 (모노레포, `io.saife`)
- [x] Gemini 안정화 계층 이식 (Inufleet)
- [x] SSE 서비스 + ToolCallTracker
- [x] 데이터 코어 스키마 V1
- [x] `.env` 작성 후 `bootRun` 기동 확인 — Flyway V1 적용(205ms), 23테이블, pgvector 0.8.2, /actuator/health UP
- [x] **`docker compose up -d --build` 전체 스택 검증** — 컨테이너 3종 healthy, nginx HTTP 200
- [x] 키 없이 기동 확인 (데모 모드 하강, healthy)
- [ ] 깨끗한 클론에서 5분 룰 실측 (10/5 패키징 시)
- [ ] 공공 API 재개가능 캐싱 크롤러 (+ 데이터셋별 필드 검증)
- [ ] 법령 조문 ~15건 수기 큐레이션
- [ ] 가상 사업장 시드 (설비 10~20, 공정, 장소)
- [ ] 평가셋 30장 구성 + **채택 규칙 사전 문서화** (결과 보기 전)
- [ ] **9/23 저녁: VLM 선검증 게이트 go/no-go** — 미통과 시 체크리스트 모드로 강등

## 블록① (9/24~9/27, 4일) — UC3 E2E
- [x] 도구 6종 (`findLocationEquipment` `extractWorkPlan` `analyzeHazards` `searchCases` `getMsds` `createWorkPlan`)
- [x] 에이전트 루프 (도구 호출 루프, 데모 모드 스크립트 폴백)
- [x] 중단/재개 배관 3건: `ai.slot.request` + 재개 엔드포인트 + `conversation_state`
- [x] 트레이스 패널 (프론트)
- [x] 가상 회사 서식 디자인 (`/form/*`, Thymeleaf)
- [x] 승인 큐 (홈 "오늘 할 일"의 PENDING_APPROVAL)

## 평일 (9/28~10/2 저녁)
- [x] 되묻기 턴 + 등급 룩업테이블 (`work_height`가 등급을 뒤집는다)
- [x] UC4 설비 타임라인 뷰 (+ 프로젝터 가독성 체크 — `docs/qa-report-20260921.md` 시연 관점 메모)
- [x] UC1 비전 파이프라인 골격 (+ 사진 판독 후보별 근거 3건, 2026-09-29)
- [x] 연결성 개선 — 홈 "오늘 할 일" 인박스, 설비 카드, 진입 회상(`ai.recall`), 사고 연쇄
      4단계 가시화(`docs/SAIFE_연결성_개선_프롬프트.md` 전 구간)
- [x] 근거 계층(RAG) — 하이브리드 RRF 검색·parent 확장·Flash 리랭크·키워드 폴백,
      근거 카드(`ai.evidence`)·인용 후처리·미디어 프록시(`docs/superpowers/specs/2026-09-28-evidence-rag-and-connectivity-design.md`)
- [ ] 기술설명서 1p 초안 — **Memory/Feedback 필드에 시간을 몰아준다**

## 블록② (10/3~10/5)
- [ ] 10/3: **UC2 콜백 경로 먼저** → UC1 완성 (대부분 완료 — 잔여 항목은 `final-review-fixlist.md` 참고)
- [ ] 10/4: 안정화 · **코드 프리즈** · **1차 영상 촬영(백업본)**
- [ ] 10/5: 저장소 패키징 · 기술설명서 최종 · 보고서 5p · 발표자료 10장 · 별지2 · 제출
      (별지2 초안 섹션은 `docs/connectivity-report-20260929.md`에 마련해 뒀다 — 그대로
      옮겨 채우면 된다)

## 블록③ (10/9~10/11)
- [ ] 라이브 시연 리허설 10회+ (무중단 정의: 수동개입 0, 재시작 0)
- [ ] 오프라인 폴백 구축 + 전환 절차 리허설
- [ ] 예상질문: "GS AIR와 뭐가 다릅니까" / "상시평가 요건" / "그건 시스템이 이미 아는 것 아닙니까"

## 미해결
- [ ] 재해사례 첨부 API(1070) — 가이드 샘플대로도 400. `atcflcnt`로 대체 가능
- [ ] data.go.kr 응답을 저장소에 동봉하는 게 재배포에 해당하는지
- [ ] 법제처 OPEN API 실제 호출 한도
- [ ] GUIDE 청크 약 18,500건 Contextual Enrichment 미적용(Gemini 쿼터) — 쿼터 리셋 후
      `rebuild?kind=GUIDE`로 재적재, 커밋 전이면 시드 재수출
- [ ] `final-review-fixlist.md`의 Backend/Frontend 항목 (C Task 6 범위 밖 — 최종 리뷰어가 처리)
- [ ] `TodayServiceTest.periodicDue_within60Days_included`가 이미 대화·스모크로 오염된
      DB에서 돌면 실패할 수 있다(2026-09-29 실측 — 원인은 테스트 격리 부재, 코드 결함 아님).
      다음 전체 테스트 실행은 시드 상태 그대로의 DB에서 한 번 더 확인할 것
- [ ] `docs/experiments/reset_demo_data.py`를 격리 스택에 돌릴 땐 **반드시**
      `SAIFE_PG_CONTAINER=<대상 컨테이너>` 지정 — 안 하면 공유 세션 DB가 리셋된다
      (2026-09-29 실측 사고, `docs/qa-report-20260929.md`)
