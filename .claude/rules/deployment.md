# 배포 및 Docker 규칙

Jenkinsfile, Dockerfile, docker-compose 파일 수정 시 반드시 준수.

## 배포 인프라 개요

- **리전**: AWS **서울**(`ap-northeast-2`).
- **compose 서비스 5종**: `postgres` · `neo4j` · `backend` · `frontend`(nginx) · `cv`(FastAPI). Polyglot(Postgres 운영/설정 + Neo4j 지식그래프).
- **이미지 스토리지**: 이미지 영속 = **S3 서울**(운영) / **MinIO**(로컬·개발). StorageService 포트로 교체(backend `storage-service` 룰).
- **폴리레포 배포**: frontend·backend·cv·infra 각 독립 리포 → 리포별 개별 배포. **API 변경 포함 시 백엔드 먼저 배포**(하위 호환 유지) → 프론트 → 다음 사이클 호환코드 제거.
- **확정(2026-07-27)**: **AWS Lightsail 8GB/2vCPU 단일 인스턴스**에 데이터스토어·앱·모니터링·**Jenkins까지 전부** 배치.
  CI/CD = **Jenkins 선언형 파이프라인**(호스트 네이티브 설치, `agent any`로 로컬 docker 실행 — SSH 배포 아님).
  모니터링 = **Prometheus + Grafana + Loki/Promtail + cAdvisor/node-exporter + Dozzle**.
  UI 바인딩은 `MONITOR_BIND_ADDR` — **운영은 Tailscale IP(100.x.y.z)**, 미설정 시 `127.0.0.1`(SSH 터널 폴백).
  **`0.0.0.0` 바인딩 금지** — tailscale0 트래픽은 Lightsail 방화벽을 거치지 않으므로 방화벽이 안전장치가 되지 못한다.
  절차·메모리 배분·부트스트랩 순서는 [`docs/deployment-runbook.md`](../../docs/deployment-runbook.md).

## 단일 인스턴스 메모리 규율 (8GB — 중요)

8GB에 전부 올리므로 **메모리 상한 없는 컨테이너를 만들지 않는다.** 상한을 빠뜨리면 빌드 피크에 앱이 OOM킬 당한다.

- 새 컨테이너를 추가하면 반드시 `mem_limit`(compose) 또는 `--memory`(docker run)를 명시하고, 런북의 배분표를 갱신한다.
- **빌드 컨테이너에도 `docker build --memory`** 를 건다 — 배포 순간이 피크다(구 앱 컨테이너가 아직 살아 있음).
- 빌드 도구 힙도 상한: Gradle `GRADLE_OPTS=-Xmx640m --no-daemon`, node `NODE_OPTIONS=--max-old-space-size=768`.
- **Jenkins 실행기는 1개** — 동시 빌드는 빌드 컨테이너를 2개로 만들어 확실히 초과한다.
- 스왑 4GB 필수(런북 01). JVM 컨테이너는 `-XX:MaxRAMPercentage`로 상한에서 힙을 파생시킨다(고정 `-Xmx` 하드코딩 지양).

## Docker 이미지 태깅

- 태그 형식: `{IMAGE_NAME}:{BUILD_NUMBER}-{GIT_SHORT_SHA}` (예: `saife-backend:42-a1b2c3d`)
- 빌드 시 `latest` 태그도 동시 부여: `docker build -t name:tag -t name:latest .`
- 배포 성공 후 `stable` 태그 부여 (롤백 대상)

## 프론트 빌드 버전 주입 (2026-09-03)

프론트 이미지는 **반드시 `--build-arg APP_VERSION=${IMAGE_TAG}`** 로 빌드한다(frontend `Jenkinsfile`). 이 값이
번들 상수 `__APP_VERSION__`와 `dist/version.json`에 박히고, 배포 뒤에도 열려 있던 탭이 둘을 대조해
"새 버전 배포됨 — 새로고침" 배너를 띄운다. 빠뜨리면 버전이 `dev`로 박혀 감시가 **조용히 꺼진다** —
검사 탭은 하루 종일 열려 있어 낡은 번들이 신 서버에 발행 payload를 보내 422를 받는 사고가 재발한다.
nginx는 `version.json`을 index.html과 같은 no-cache로 서빙한다(캐시되면 배너가 영영 뜨지 않는다).

## 이미지 정리 정책

- **`docker image prune -f` 사용 금지** — 롤백용 이전 이미지가 삭제됨
- 최근 5개 빌드 이미지를 보존하고 나머지만 정리
- `latest`, `stable` 태그 이미지는 항상 보존

## 영속 데이터스토어 컨테이너 보호 (Postgres · Neo4j)

- **Postgres·Neo4j는 영속 컨테이너** — 항상 유지. 두 스토어 모두 데이터 볼륨 보유.
- **`docker compose down` 금지** — 영속 스토어까지 재시작/볼륨 위험. 앱 컨테이너(`backend`·`frontend`·`cv`)만 stop/rm/run으로 교체.
- 배포 파이프라인에서 postgres·neo4j 실행 여부 확인 후 필요시만 기동.
- ⚠️ **예외(charset 변경)**: Postgres 인코딩은 initdb 시점 확정 → 문자셋 변경 시에만 `docker compose down -v`로 볼륨 재생성(개발 데이터 폐기, dev+운영 양쪽). 일반 배포에서는 금지.

## 배포 실패 시 롤백 패턴

- CI 파이프라인(도구 미확정) 실패 단계에서 `stable` 태그 이미지로 자동 롤백
- 롤백 실패 시(stable 이미지 없음) 에러 메시지만 출력, 프로세스 중단하지 않음

## .dockerignore 필수 항목

Docker 빌드 컨텍스트에서 제외해야 하는 파일:
- `.git`, `.gitignore`, `.claude`, `.gstack`, `.agent`
- `node_modules`, `dist`, `build/`, `.gradle`
- `.env`, `.env.*`, `*.md`, `*.log`
- `monitoring/`, `uploads/`, `docker-compose*.yml`
- (cv 리포) `__pycache__`, `*.pyc`, `.venv`, `models/`(대용량 가중치)

## Flyway 마이그레이션 롤백

- 모든 마이그레이션 파일 상단에 `-- ROLLBACK:` 주석으로 되돌리기 SQL 명시
- 롤백 스크립트: `src/main/resources/db/rollback/R{번호}__undo_{설명}.sql`
- **파괴적 변경(DROP COLUMN, DROP TABLE)**: 엔티티에서 필드 제거와 동일 마이그레이션에서 `DROP COLUMN` 즉시 실행. `_deprecated` rename 단계 불필요 — 개발 단계에서는 단일 배포로 정리

## 프론트/백 동시 배포 순서

API 변경이 포함된 경우:
1. **백엔드 먼저 배포** (하위 호환 유지 — 새 필드 추가, 기존 필드 유지)
2. 프론트엔드 배포
3. 다음 사이클에서 하위 호환 코드 제거

## 스케일아웃 선행 조건 (2026-08-05, 백엔드 최적화 백로그 P2-5)

**현재 배포(Lightsail 8GB 단일 인스턴스, backend 컨테이너 1개)는 이 표 항목들의 위험이 발현되지 않는다.**
backend를 2개 이상 인스턴스로 수평 확장(멀티 컨테이너·오토스케일 등)하기 **전에** 아래 인메모리 상태
지점을 먼저 손보지 않으면 조용히 깨진다 — 지금 구현을 바꿀 필요는 없다(YAGNI, 분석 문서 P2-5), 스케일아웃
착수 시점에 이 표부터 다시 확인한다.

| 구성요소 | 위치 | 멀티 인스턴스에서 깨지는 이유 | 스케일아웃 전 조치 |
|----------|------|-------------------------------|-------------------|
| SSE 세션 | `SseService`(emitters 등 `ConcurrentHashMap`, 82-94행) | sessionId→emitter가 그 인스턴스의 JVM 힙에만 존재 — 다른 인스턴스가 받은 이벤트를 원래 emitter를 쥔 인스턴스로 전달할 방법이 없다 | sticky session(로드밸런서 세션 고정) 또는 메시지 브로커(Redis pub/sub 등)로 브로드캐스트 경로 신설 |
| 권한 캐시 | `PermissionResolver`(rolePresetCache·userDeltaCache, 37-38행) | `evictAllRolePresets()`/`evictUser()`가 호출된 그 인스턴스의 로컬 캐시만 비운다 — 권한 프리셋/사용자 델타를 편집해도 다른 인스턴스는 스테일 캐시로 계속 응답 | 캐시 무효화를 전 인스턴스에 브로드캐스트(pub/sub) 하거나 캐시 자체를 공유 스토어(Redis 등)로 이전 |
| 로그인 시도 카운터 | `LoginAttemptService`(attempts, 29행) | 인스턴스마다 별도 카운터라 실패 5회 제한이 인스턴스 수만큼 실질적으로 완화됨(브루트포스 방어 약화) | 공유 스토어(Redis 등)로 카운터 이전, 또는 로드밸런서가 사용자를 인스턴스에 고정 |
| export 잡 클레임 | `ExportJob` 클레임 픽업 루프 | 이미 `SKIP LOCKED` 기반 DB 클레임이라 멀티 인스턴스에서도 안전(중복 처리 없음) | 조치 불요 |
| 고아 정리 스위퍼 2종 | `CvStuckSweeper`·export 스위퍼 | 멱등 설계 — 여러 인스턴스가 동시에 스케줄을 돌려도 중복 정리로 인한 부작용 없음 | 조치 불요 |
