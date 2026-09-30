---
globs: ["Dockerfile*", "docker-compose*.yml", ".dockerignore", "**/db/migration/*.sql"]
---

# 패키징 및 기동 규칙 (경진대회)

**이 프로젝트에는 배포 서버가 없다.** CI도, 스테이징도, 롤백 파이프라인도 없다.
"배포"에 해당하는 것은 딱 두 가지다.

1. **제출물 ③ 저장소** — 심사위원이 clone 받아 돌려보는 것
2. **10/12 무대 기동** — 라이브 발표심사에서 노트북 한 대로 띄우는 것

여기에 없는 것을 만들지 않는다. 모니터링 스택, 이미지 레지스트리, 무중단 배포,
멀티 인스턴스 — 전부 이번 범위 밖이고 만들면 순수 손해다.

## 제1원칙 — 심사위원은 키가 없다

심사위원이 `git clone` 후 **API 키 하나 없이** 앱을 띄울 수 있어야 한다.
키가 필요하면 그 심사위원은 앱을 안 본다.

이걸 가능하게 하는 것:

| 요소 | 규칙 |
|---|---|
| 공공 API 응답 | **전량 캐시를 저장소에 동봉**한다 (`backend/src/main/resources/seed/`). 런타임에 외부 호출 0 |
| 가상 사업장 데이터 | Flyway seed 마이그레이션으로 자동 적재. 별도 스크립트 실행을 요구하지 않는다 |
| Gemini 키 | **없으면 `SAIFE_DEMO_MODE=true`로 자동 폴백**해 픽스처 응답으로 돈다. 앱이 죽지 않는다 |
| 실제 키 | `.env.example`만 추적. `.env`는 절대 커밋하지 않는다 |

**부팅 시 키가 없다고 예외를 던지지 않는다.** 경고 로그 한 줄을 남기고 데모 모드로 내려간다.

⚠️ **경고만 찍고 끝내면 안 된다.** Spring AI의 Google GenAI 임베딩 autoconfiguration은
api-key가 비어 있으면 Vertex AI 모드로 해석해 `"Google GenAI project-id must be set!"`로
컨텍스트를 죽인다. `DemoModeConfig`가 경고를 찍어도 그 직후에 기동이 막힌다.

→ `DemoModeEnvironmentPostProcessor`가 키가 비었을 때 자리표시자를 **`addFirst`로** 주입해
autoconfiguration을 통과시킨다. `addLast`로는 `application.yml`이 이미 해석해 둔 빈 문자열에
밀린다. 실제 호출은 데모 모드가 픽스처로 가로채므로 자리표시자가 밖으로 나가지 않는다.

**이 경로는 회귀하기 쉽다. 키를 비우고 기동하는 테스트를 10/5 패키징 점검에 포함한다.**

## 5분 룰

`README.md`의 실행 절차는 **실제로 5분 안에 끝나야 한다.** 10/5 패키징 때
**깨끗한 클론에서 한 번 직접 돌려본다.** 자기 머신에서만 되는 건 안 된 것이다.

```bash
git clone <repo> && cd saife
cp .env.example .env
docker compose up -d --build
# → http://localhost:5173
```

확인 항목: 컨테이너 3개 기동 · Flyway 마이그레이션 통과 · 시드 데이터 적재 ·
프론트에서 UC3 대화 1건 완주.

## compose 파일 2종

| 파일 | 용도 |
|---|---|
| `docker-compose.dev.yml` | **개발용.** DB만 띄우고 backend/frontend는 로컬에서 직접 실행 |
| `docker-compose.yml` | **제출용 전체 스택.** 심사위원과 무대에서 쓰는 것 |

개발 중에는 `dev`를 쓴다. 전체 compose는 **10/4 코드 프리즈 전에 반드시 한 번
빌드해서 돌려본다** — 마지막 날 처음 빌드하면 거기서 터진다.

## nginx — `/form/*`도 프록시해야 한다

법정 서식 화면(`/form/work-plan/{id}` 등)은 SPA 라우트가 아니라 **백엔드가 직접
렌더하는 Thymeleaf 페이지**다. 개발 중엔 Vite 프록시가 `/form`도 `:8080`으로 넘겨
문제가 안 보이지만, `frontend/nginx.conf`에 `/api/` 블록만 있으면 **컨테이너 스택에서
서식 버튼이 전부 404**가 된다(2026-09-29 클린 빌드 검증에서 실측). `/form/` 블록을
`/api/`와 같은 `backend:8080`으로 추가하되, SSE 버퍼링 옵션(`proxy_buffering off` 등)은
`/api/` 전용으로 남긴다 — 서식 페이지는 스트림이 아니다.

## 이미지 태깅

- 레지스트리에 올리지 않는다. 로컬 빌드가 전부다
- 태그는 `saife-backend:local` / `saife-frontend:local` 고정. 빌드번호·SHA 태깅 불필요
- `stable` / 롤백 태그 없음 — 롤백할 운영 환경이 없다

## Flyway

- **전진만 한다.** 롤백 스크립트를 쓰지 않는다. 스키마가 꼬이면
  `docker compose down -v`로 볼륨을 날리고 다시 만든다 (데이터가 전부 시드라 손실이 없다)
- `ddl-auto: validate` 고정. 엔티티를 고쳤으면 마이그레이션을 같이 쓴다. 안 쓰면 부팅이 실패한다
- 파일명 `V{n}__{설명}.sql`. 시드는 `V{n}__seed_{설명}.sql`로 구분해 둔다
- **이미 커밋한 마이그레이션 파일을 수정하지 않는다.** 체크섬이 깨져 부팅이 막힌다.
  고칠 일이 생기면 새 번호로 추가한다
- **V8 = 연결성 컬럼(`work_plan.warning_note`), V9 = 근거(RAG) 스키마, V10 = 고소작업대 이야기 시드.**
  연결성 개선 프롬프트는 V8에 시드까지 넣으라고 했지만, V9(근거 스키마)가 먼저 커밋돼야 해서
  V8은 컬럼만 두고 시드는 번호를 밀어 V10으로 옮겼다 — 이미 커밋된 V8·V9를 건드리지 않기 위해서다
- ⚠️ **시드 날짜 드리프트.** V10 시드의 사건 날짜(기한 초과 31일 경과, D-7 등)는 시드
  마이그레이션이 적용된 시점(=이미지를 처음 기동한 날) 기준 상대값으로 계산된다.
  즉 **무대 전날 이미지를 새로 빌드하지 않고 오래된 볼륨을 그대로 쓰면** "31일 경과"
  같은 문구가 실제 경과일과 어긋난다. **무대 하루 전에 데모 볼륨을 새로 기동해
  시드 날짜를 오늘 기준으로 재계산시킬 것** (`docker compose down` 후 `up -d --build`,
  `-v` 금지 사유는 없다 — 데이터가 전부 시드이므로 볼륨을 날려도 무방하다).
  단, **날짜를 실제로 다시 계산하려면 `-v`가 필요하다** — 기존 DB 볼륨에서는 Flyway가 V10을
  다시 돌리지 않는다.
- ⚠️ **이 브랜치(V8~V11)를 기존 볼륨 위에 올리려면 `docker compose down -v`가 필요하다.**
  V10은 `work_plan.id=1`·`incident.id=1`·`assessment` 4~6을 **고정 id로** 넣는다. main에서
  리허설하며 작업계획서·사고를 만든 볼륨이면 id가 겹쳐 부팅 시 Flyway가 실패한다.
  V10 파일은 체크섬 때문에 고칠 수 없으므로 볼륨을 새로 만드는 것이 유일한 경로다(최종 리뷰 F14).
- **V11 = 법제처 OC 자격증명 제거**(조문 링크를 사람용 조문 페이지로 재작성, 근거 payload의 `OC=` 삭제).
  멱등이라 새 볼륨에서는 사실상 no-op이다.
- ⚠️ **첫 부팅 JVM 힙 여유 ≥ 1.5 GB.** `EvidenceSeedLoader`가 근거 청크 약 4만 행의 child JSON을
  한 번에 메모리에 올린다(원시 JSON 약 108 MB). 이미지 기본값 `MaxRAMPercentage=75`로 Docker
  VM 메모리가 2 GB 이상이면 충분하지만, 그보다 작은 VM에서는 OOM(`Error`라 잡히지 않는다)으로
  부팅이 멈춘다. Docker Desktop 메모리 할당을 먼저 확인한다(최종 리뷰 F16).

## .dockerignore 필수 항목

빌드 컨텍스트에서 제외:

```
.git .gitignore .claude .gstack .superpowers
node_modules dist build/ .gradle bin/ out/
.env .env.* *.log
docs/ *.md
docker-compose*.yml
```

⚠️ **`backend/src/main/resources/seed/`는 제외하지 않는다.** 심사위원이 키 없이
돌리려면 이게 이미지 안에 들어가야 한다.

## 10/12 무대 기동 — 최대 리스크 구간

10/6 이후 남은 가장 큰 수상 상실 요인은 **라이브 시연 실패**다.

### 무대 원칙

- **외부 공공 API 호출 0.** 전량 로컬 캐시(`public_case`·`kosha_guide`·`msds_cache`)
- **모델은 라이브로 호출한다.** 심사위원이 대본 밖 입력을 넣어도 돌아야 한다.
  등급은 어차피 룰 엔진이 내므로 흔들리는 건 문장 표현뿐이다
- **`SAIFE_DEMO_MODE=true`는 네트워크 장애 전용 폴백**이다. 기본값이 아니다.
  **전환 절차 자체를 블록③(10/9~11)에서 리허설한다**
- 슬라이드 9에 "시연은 라이브입니다. 네트워크 장애 시 오프라인 폴백으로 전환합니다"를
  **먼저** 밝힌다. 먼저 말하면 강점, 질문받고 말하면 변명이 된다

### 무대 전날 (최종 리뷰 F6·F14)

1. `docker compose down -v` → `docker compose up -d --build` — 시드 날짜를 오늘 기준으로 재계산
   (`-v`는 DB 볼륨과 함께 미디어 캐시 볼륨 `saife-media`도 지운다)
2. 백엔드 로그에서 `[SEED] 근거 청크 …건 적재`까지 확인
3. **그다음 `POST /api/admin/media/prefetch`** — 시연 설비의 위험요인 축으로 도구와 같은 검색을 돌려
   나오는 사례 사진·지침 PDF를 먼저 받고 상한(사진 200·PDF 30)까지 채운다. 응답의
   `demoPhotos`/`demoPdfs`가 0이 아니어야 한다. 캐시는 `saife-media` 볼륨에 남아 당일 `up -d`에도 유지된다
4. 근거 재색인(`POST /api/admin/index/rebuild`)은 무대 준비에 필요 없다 — 완료된 kind는
   `force=true` 없이 no-op이고, 키 없는 데모 모드에서는 아무것도 지우지 않고 `SKIPPED`를 돌려준다

### 무대 전 체크리스트 (발표 30분 전)

1. `docker compose up -d` 후 컨테이너 3개 healthy
2. 시드 데이터 적재 확인 (설비 목록이 보이는지)
3. UC3 대화 1회 완주 — 도구 6개 점등, 되묻기 턴 동작
4. **프로젝터 해상도에서 트레이스 패널과 타임라인 뷰가 읽히는지**
5. 네트워크 끊고 `SAIFE_DEMO_MODE=true` 전환 → 다시 완주되는지
6. 백업: 녹화 영상 파일을 로컬에 두고 재생 가능 상태로

### 하지 말 것

- 무대에서 `docker compose build` — 빌드는 전날 끝낸다
- 무대에서 Flyway 신규 마이그레이션 — 스키마는 프리즈 상태여야 한다
- 발표 당일 코드 수정. **10/4가 프리즈다**

## ⚠️ 공유 머신에서 스모크 돌릴 때 — `reset_demo_data.py`는 컨테이너 이름을 직접 때린다

`docs/experiments/reset_demo_data.py`는 `SAIFE_BASE_URL`을 보지 않는다.
`docker exec <컨테이너명> psql ...`로 **DB 컨테이너 이름을 직접 지정**한다
(기본값 `saife-postgres`). 격리된 clean 스택(예: `docker compose -p saife-clean`)에
스모크를 돌리면서 `SAIFE_BASE_URL`만 바꾸고 이 스크립트를 그대로 실행하면,
**공유 세션 DB(`saife-postgres`)가 조용히 리셋된다** — 2026-09-29 클린 빌드
검증 중 실제로 발생(상세: `docs/qa-report-20260929.md`). 격리 스택을 리셋할 때는
반드시 `SAIFE_PG_CONTAINER=<그 스택의 postgres 컨테이너명>`을 같이 준다.

## 제출 전 저장소 점검 (10/5)

- [ ] `.env`가 추적되지 않는지 (`git ls-files | grep -c "^\.env$"` → 0)
- [ ] API 키·인증키가 소스에 하드코딩되지 않았는지 (`grep -rn "serviceKey=\|api-key:" --include=*.java --include=*.yml`)
- [ ] 깨끗한 클론에서 5분 룰 통과
- [ ] **`GEMINI_API_KEY=""` 로 기동해 healthy 되는지** (심사위원 시나리오. 회귀하기 쉬운 경로)
- [ ] `README.md` 실행 절차가 실제와 일치
- [ ] AI Hub 이미지가 포함됐다면 **재배포 조건 확인 완료** (미확인이면 로컬 경로 참조로 전환)
- [ ] 비공개 저장소 → 심사 기간 공개 전환 또는 심사위원 초대
- [ ] **별지2 출처·AI 활용 신고서**와 실제 의존성이 일치 (`build.gradle`·`package.json` 대조)
