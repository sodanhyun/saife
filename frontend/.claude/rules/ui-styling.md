---
globs: ["src/**/*.tsx", "tailwind.config.js"]
---

# UI 스타일링 규칙

## cn()
`@/lib/cn` (clsx + tailwind-merge). 조건부 클래스는 `cn()` 인자로, 외부 `className`은 마지막 인자. 템플릿 리터럴 삼항 금지.

## 클래스 순서
레이아웃 → 크기·여백 → 타이포 → 색·테두리·반경·그림자 → 상호작용(`hover:` `transition`).

## 타이포 (무대 밀도)
| 역할 | 클래스 |
|---|---|
| 페이지 제목 | `text-xl font-bold text-slate-900` |
| 페이지 설명 | `text-sm text-slate-500` |
| 섹션 제목 | `text-base font-semibold text-slate-900` |
| 라벨 | `text-xs font-semibold text-slate-500` |
| 본문 | `text-sm text-slate-900` |
| 메타 | `text-xs text-slate-400` |
| 강조 문장·트레이스·타임라인 제목 | `text-stage` |

## 밀도
- 페이지 컨테이너 `max-w-[1600px] px-6 pt-6 pb-4` (`PageLayout`)
- 표 셀 `px-3 py-2`, 카드 `p-4`, 카드 사이 `gap-4`
- 폰트: Pretendard Variable 자체 호스팅(`src/styles/index.css`). CDN 금지 — 무대 오프라인 대비.

## 토큰 레퍼런스
- 주요 버튼 `bg-slate-900 hover:bg-slate-800`, 위험 버튼 `bg-risk-high`
- 입력 `border-slate-300 focus:border-slate-500 focus:ring-1 focus:ring-slate-500`
- 페이지 배경 `bg-page`, 패널 `bg-panel`, 테두리 `border-slate-200`
