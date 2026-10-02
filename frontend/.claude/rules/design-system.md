---
globs: ["src/components/**", "src/pages/**"]
---

# 디자인 시스템 규칙 (SAIFE)

SAIFE는 **무대 밀도** 한 표면이다. 프로젝터(1280x720)와 시연 영상(1920x1080)에서 읽혀야 한다. 본문 14px, 트레이스와 타임라인과 강조 문장은 `text-stage`(15px), 결과 카드 제목은 `text-headline`(22px), 화면의 결정적 한 줄(예고된 사고)은 `text-display`(30px).
글자 크기 토큰은 모두 rem이다. 영상 녹화는 html font-size를 125%로 올려 1920 네이티브로 찍는다(px 고정 크기를 새로 만들지 않는다).

## 색 — 편차에만 쓴다

`tailwind.config.js`의 토큰이 SSOT다. **hex·`red-*`·`amber-*`·`emerald-*`·`gray-*` 금지.** 중립은 `slate-*`.

| 토큰 | 뜻 | 쓰는 곳 |
|---|---|---|
| `risk-high` | 등급 상 · 사고 · 기한 경과 · 오류 | RiskBadge, 사고 소환 배너, 실패 점 |
| `risk-medium` | 등급 중 | RiskBadge |
| `risk-low` | 등급 하 · 성공 토스트 | RiskBadge, 확인됨 표기 |
| `pending` | 되묻기 슬롯 · 승인 대기 · 제출 필요 | SlotPrompt, StatusBadge |
| `progress` | 도구 실행 중 · 활성 네비 · 선택/연결 강조 | 트레이스 점, 사이드바, 타임라인 링 |
| `neutral` | 그 밖의 전부 | Badge 기본, 이벤트 타입 태그 |
| `brand` | 제품 강조(상태 의미 없음) | 주요 버튼, 사이드바, 에이전트 흐름 진행선, 결과 카드 머리 |

상태→클래스 매핑은 `@/utils/statusColors`(`toneColor`, `riskColor`, `workPlanStatusTone`, `emphasisTone`, `reportDutyTone`)로만 한다. 컴포넌트에서 상태를 직접 색으로 분기하지 않는다.

## 형태

- 반경 `rounded-sm`(6) · `md`(8) · `lg`(10) · `xl`(12)까지. `2xl` 이상 금지. 알약(`rounded-full`)은 상태 점만.
- 그림자는 `shadow-card`. 화면의 주인공 면 하나(결과 카드, 사고 히어로)만 `shadow-lift`. `shadow-modal`, `shadow-toast`는 해당 컴포넌트 전용.
- 모션: `animate-rise-in`(새 결과 등장, 지연으로 순차), `animate-fade-in`, `animate-ping-soft`(실행 중 점), `animate-grow-x/y`(연쇄 진행선). 이동은 8px 이내.
- 등급의 큰 표식은 `RiskGradeMark`, 옆에 룰 근거를 반드시 같이 둔다.
- 화면 문자열에 가운뎃점, 대시(—, –)를 쓰지 않는다. 쉼표, 슬래시, 괄호로 쓴다.
- 금지: 그라데이션, `backdrop-blur`(Modal 배경 제외), 이모지, 장식 아이콘, 임의 픽셀 클래스(`text-[10px]` 등).
- 배지는 각진 태그(`Badge`). 카드는 `Card`(흰 배경 + slate-200 테두리).

## 공통 컴포넌트 우선

| 패턴 | 컴포넌트 |
|---|---|
| 페이지 쉘 / 제목 | `PageLayout` / `PageHeader` |
| 카드 / 섹션 제목 | `Card` / `SectionTitle` |
| 안내 상자(배너·슬롯·진행·데모) | `Callout tone=…` |
| 표 | `DataTable` |
| 수치 요약 | `KpiCell` |
| 버튼 · 입력 · 셀렉트 · 여러 줄 | `Button` · `Input` · `Select` · `Textarea` (+`FormField`) |
| 버튼 모양의 링크(법정 서식 등) | `LinkButton` (`external`이면 새 탭) |
| 배지 | `Badge` · `RiskBadge` · `StatusBadge` |
| 모달 / 확인 | `Modal` / `ConfirmModal`(+`useConfirm`) |
| 빈 상태 / 로딩 | `EmptyState` / `Skeleton`·`PageSkeleton` |
| 탭 / 세그먼트 | `TabBar` / `SegmentedControl` |
| 토스트 | `useToastStore.getState().success|error|info|warning` |

인라인으로 같은 패턴을 다시 만들지 않는다. 등급 배지 옆에는 **항상 룰 트레이스**를 같이 띄운다.

## Skeleton

`src/pages/{Domain}/{Domain}Skeleton.tsx`. 실제 레이아웃과 같은 골격. 첫 로딩에만 보이고 재조회 때는 보이지 않는다(`useApiData.skipFirstSkeleton`).
