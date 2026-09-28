# 근거 계층 B2 — 프론트 근거 카드·인용 칩·화면 연결 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ai.evidence`·UC2·UC1 응답의 근거를 카드로 그리고, 본문 `[#n]`을 칩으로 바꿔 카드로 스크롤하며, 전역 상태 줄로 데모/실시간을 알린다.

**Architecture:** `components/evidence/`(EvidenceCard·EvidenceGrid·PhotoLightbox·LawArticleCard·MsdsCard·CitationChip)를 Tone 디자인 시스템 위에 만들고, `useAgentStream`에 `ai.evidence` 누적·복원을 넣는다. UC3·UC2·UC1 화면은 카드 컴포넌트를 재사용한다.

**Tech Stack:** React 19 · TypeScript · Tailwind(토큰만) · vitest + Testing Library.

**Spec:** §8 전체 · 선행: B1(백엔드 DTO)

## Global Constraints

- `frontend/.claude/rules/*.md` 전부 적용: hex·`red-*`·그라데이션·이모지 금지, 상태색은 `utils/statusColors.ts`의 Tone만, `cn()`으로 조건부 클래스, UI 컴포넌트에서 fetch 금지, 폴링 금지.
- 타입은 `types/evidence.ts`가 백엔드 `Evidence` 레코드와 1:1(camelCase). `sse.ts`에 `ai.evidence` 추가.
- `AgentMessage`는 innerHTML을 쓰지 않는다. 칩도 React 요소로만.
- 썸네일은 `thumbnailUrl`, 원본은 `mediaUrl`. 이미지 실패 시 깨진 아이콘 없이 kind 아이콘으로 degrade(`onError`).
- 접근성: 카드 제목 `text-stage`, 썸네일 `alt=제목`, 라이트박스는 `Modal` 재사용(ESC·포커스).
- `npm run lint`(경고 0)·`npm run build`·`npx vitest run` 통과 후 커밋.

## Review Focus

1. `[#n]`이 문장 중간·끝·연속(`[#1][#2]`)으로 와도 칩 사이 텍스트가 사라지지 않아야 한다. → Task 2 `CitationChip.test`.
2. 썸네일 이미지 로드 실패 시 카드가 여전히 제목·발췌·링크를 보여줘야 한다. → Task 1 `EvidenceCard.test.사진_실패_degrade`.
3. 새로고침 복원 시 `/transcript`의 턴별 근거가 각 assistant 턴 아래에 붙어야 하고, `send()` 후 도착한 늦은 복원 응답이 새 턴을 덮으면 안 된다(기존 sentRef 가드 유지). → Task 3 `useAgentStream.test.복원_근거`.
4. 같은 대화의 두 번째 턴에서 `ai.evidence`가 오면 그 턴의 assistant 말풍선에만 붙고 이전 턴 카드는 그대로다. → Task 3 `useAgentStream.test.턴별_근거_누적`.
5. `origin=KEYWORD_FALLBACK` 카드는 "키워드 검색" 배지를, `LIVE`는 "실시간 조회 HH:mm"을, `CACHE`는 "캐시 MM-DD"를 보여야 한다. → Task 1 `EvidenceCard.test.origin_배지`.

---

### Task 1: 타입 + `EvidenceCard`·`EvidenceGrid`·`PhotoLightbox`·`LawArticleCard`·`MsdsCard`

**Files:**
- Create: `frontend/src/types/evidence.ts`
- Modify: `frontend/src/types/sse.ts` (`"ai.evidence"`, `EvidencePayload`)
- Create: `frontend/src/components/evidence/EvidenceCard.tsx`, `EvidenceGrid.tsx`, `PhotoLightbox.tsx`, `LawArticleCard.tsx`, `MsdsCard.tsx`, `evidenceMeta.ts`
- Create: `frontend/public/ghs/GHS01.svg` … `GHS09.svg` (UN GHS 픽토그램, 퍼블릭 도메인 SVG 9종. 파일명 = 코드)
- Test: `frontend/src/components/evidence/__tests__/EvidenceCard.test.tsx`, `MsdsCard.test.tsx`

**Interfaces:**
- Produces:
```ts
// types/evidence.ts
export type EvidenceKind = "CASE_FATALITY" | "CASE_DISASTER" | "GUIDE" | "LAW" | "MSDS";
export type EvidenceOrigin = "LIVE" | "CACHE" | "KEYWORD_FALLBACK";
export interface Evidence {
  no: number; kind: EvidenceKind; refId: number | null; refKey: string; title: string; snippet: string;
  sourceUrl: string | null; mediaUrl: string | null; thumbnailUrl: string | null;
  origin: EvidenceOrigin; score: number; fetchedAt: string; meta: Record<string, unknown>;
}
export const EVIDENCE_KIND_LABEL: Record<EvidenceKind, string> = { CASE_FATALITY: "사고사망 사례", CASE_DISASTER: "재해 사례", GUIDE: "KOSHA GUIDE", LAW: "법 조문", MSDS: "MSDS" };
```
  - `evidenceMeta.ts`: `originLabel(e: Evidence) → string`, `originTone(e) → Tone`(LIVE=progress, CACHE=neutral, KEYWORD_FALLBACK=pending), `scoreLabel(e) → string|null`
  - `EvidenceCard({ e, onOpenPhoto? })`, `EvidenceGrid({ items, collapsedByDefault? })`, `PhotoLightbox({ e, onClose })`, `LawArticleCard({ e })`, `MsdsCard({ e })`

- [ ] **Step 1: 실패하는 테스트**

`EvidenceCard.test.tsx`:
```tsx
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import EvidenceCard from "@/components/evidence/EvidenceCard";
import type { Evidence } from "@/types/evidence";

const base: Evidence = {
  no: 3, kind: "CASE_FATALITY", refId: 1, refKey: "F:1", title: "[협착] 스크류에 끼임", snippet: "설비 내 슬러지 제거 중 …",
  sourceUrl: null, mediaUrl: "/api/media/case/1/photo", thumbnailUrl: "/api/media/case/1/photo?w=320",
  origin: "CACHE", score: 0.84, fetchedAt: "2026-09-21T10:00:00+09:00", meta: { hasImage: true },
};

describe("EvidenceCard", () => {
  it("번호·제목·발췌·유사도·origin을 그린다", () => {
    render(<EvidenceCard e={base} />);
    expect(screen.getByText("#3")).toBeInTheDocument();
    expect(screen.getByText("[협착] 스크류에 끼임")).toBeInTheDocument();
    expect(screen.getByText(/유사도 84%/)).toBeInTheDocument();
    expect(screen.getByText(/캐시 09-21/)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "[협착] 스크류에 끼임" })).toHaveAttribute("src", "/api/media/case/1/photo?w=320");
  });

  it("origin 배지", () => {
    const { rerender } = render(<EvidenceCard e={{ ...base, origin: "LIVE", fetchedAt: "2026-09-28T14:02:00+09:00" }} />);
    expect(screen.getByText(/실시간 조회 14:02/)).toBeInTheDocument();
    rerender(<EvidenceCard e={{ ...base, origin: "KEYWORD_FALLBACK" }} />);
    expect(screen.getByText("키워드 검색")).toBeInTheDocument();
  });

  it("사진 실패 degrade — 제목과 원문 링크는 남는다", () => {
    render(<EvidenceCard e={{ ...base, sourceUrl: "https://portal.kosha.or.kr/x" }} />);
    fireEvent.error(screen.getByRole("img"));
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.getByText("[협착] 스크류에 끼임")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /원문 보기/ })).toHaveAttribute("href", "https://portal.kosha.or.kr/x");
  });

  it("썸네일 클릭이 onOpenPhoto를 부른다", () => {
    const onOpen = vi.fn();
    render(<EvidenceCard e={base} onOpenPhoto={onOpen} />);
    fireEvent.click(screen.getByRole("button", { name: /사진 크게 보기/ }));
    expect(onOpen).toHaveBeenCalledWith(base);
  });

  it("법 조문은 LawArticleCard로", () => {
    render(<EvidenceCard e={{ ...base, kind: "LAW", mediaUrl: null, thumbnailUrl: null, title: "산업안전보건법 제36조(위험성평가의 실시) — 위험성평가의 실시", snippet: "① 사업주는 …", sourceUrl: "https://www.law.go.kr/x", meta: { effectiveOn: "2026-06-01", fullText: "① 사업주는 … 전체" } }} />);
    expect(screen.getByText(/시행 2026-06-01/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /법제처/ })).toBeInTheDocument();
  });
});
```

`MsdsCard.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import MsdsCard from "@/components/evidence/MsdsCard";

describe("MsdsCard", () => {
  it("픽토그램·H코드·노출기준을 그린다", () => {
    render(<MsdsCard e={{ no: 1, kind: "MSDS", refId: null, refKey: "MSDS:001032", title: "톨루엔 MSDS (공단)", snippet: "", sourceUrl: "https://msds", mediaUrl: null, thumbnailUrl: null, origin: "LIVE", score: 1, fetchedAt: "2026-09-28T14:02:00+09:00",
      meta: { pictograms: "GHS02,GHS07", sections: { "02": ["인화성 액체 : 구분2", "H225 고인화성 액체 및 증기"], "08": ["TWA 50ppm", "STEL 150ppm"] } } }} />);
    expect(screen.getAllByRole("img")).toHaveLength(2);
    expect(screen.getByRole("img", { name: "GHS02" })).toHaveAttribute("src", "/ghs/GHS02.svg");
    expect(screen.getByText(/H225/)).toBeInTheDocument();
    expect(screen.getByText(/TWA 50ppm/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd frontend && npx vitest run src/components/evidence`
Expected: FAIL (모듈 없음)

- [ ] **Step 3: 구현**

`types/sse.ts`에:
```ts
  | "ai.evidence"
```
를 `SseEventType`에 추가하고,
```ts
import type { Evidence } from "@/types/evidence";
/** 턴 종료 시 이번 턴에 새로 등록된 근거. 카드는 백엔드가 만든다 */
export interface EvidencePayload { items: Evidence[] }
```

`evidenceMeta.ts`:
```ts
import dayjs from "dayjs";
import type { Evidence } from "@/types/evidence";
import type { Tone } from "@/utils/statusColors";

export function originLabel(e: Evidence): string {
  if (e.origin === "LIVE") return `실시간 조회 ${dayjs(e.fetchedAt).format("HH:mm")}`;
  if (e.origin === "CACHE") return `캐시 ${dayjs(e.fetchedAt).format("MM-DD")}`;
  return "키워드 검색";
}
export function originTone(e: Evidence): Tone {
  return e.origin === "LIVE" ? "progress" : e.origin === "KEYWORD_FALLBACK" ? "pending" : "neutral";
}
/** 유사도는 사례·지침에만 의미가 있다. 조문·MSDS는 null */
export function scoreLabel(e: Evidence): string | null {
  if (e.kind === "LAW" || e.kind === "MSDS") return null;
  return `유사도 ${Math.round(e.score * 100)}%`;
}
```

`EvidenceCard.tsx`:
```tsx
import { BookOpen, FileText, Image as ImageIcon, Scale } from "lucide-react";
import { useState } from "react";

import LawArticleCard from "@/components/evidence/LawArticleCard";
import MsdsCard from "@/components/evidence/MsdsCard";
import { originLabel, originTone, scoreLabel } from "@/components/evidence/evidenceMeta";
import { StatusBadge } from "@/components/ui/Badge";
import cn from "@/lib/cn";
import { EVIDENCE_KIND_LABEL, type Evidence } from "@/types/evidence";

interface Props { e: Evidence; onOpenPhoto?: (e: Evidence) => void; className?: string }

/** 근거 카드. 사례·지침은 공통 레이아웃, 조문·MSDS는 전용 카드 */
export default function EvidenceCard({ e, onOpenPhoto, className }: Props) {
  if (e.kind === "LAW") return <LawArticleCard e={e} />;
  if (e.kind === "MSDS") return <MsdsCard e={e} />;
  return <GenericCard e={e} onOpenPhoto={onOpenPhoto} className={className} />;
}

function GenericCard({ e, onOpenPhoto, className }: Props) {
  const [imgFailed, setImgFailed] = useState(false);
  const showImg = !!e.thumbnailUrl && !imgFailed;
  const Icon = e.kind === "GUIDE" ? BookOpen : e.kind.startsWith("CASE") ? ImageIcon : FileText;
  const score = scoreLabel(e);
  return (
    <article id={`evidence-${e.no}`} className={cn("flex gap-3 rounded-lg border border-slate-200 bg-white p-3 shadow-card", className)} aria-label={`근거 #${e.no}`}>
      <div className="w-24 shrink-0">
        {showImg ? (
          <button type="button" aria-label="사진 크게 보기" className="block w-full" onClick={() => onOpenPhoto?.(e)}>
            <img src={e.thumbnailUrl ?? undefined} alt={e.title} className="h-16 w-24 rounded object-cover" onError={() => setImgFailed(true)} />
          </button>
        ) : (
          <div className="flex h-16 w-24 items-center justify-center rounded bg-panel text-slate-400"><Icon size={20} aria-hidden /></div>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-xs text-slate-500">#{e.no}</span>
          <span className="text-xs text-slate-500">{EVIDENCE_KIND_LABEL[e.kind]}</span>
          <StatusBadge tone={originTone(e)}>{originLabel(e)}</StatusBadge>
        </div>
        <p className="mt-1 text-stage font-semibold leading-snug">{e.title}</p>
        {e.snippet && <p className="mt-1 line-clamp-2 text-sm text-slate-600">{e.snippet}</p>}
        <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-slate-500">
          {score && <span>{score}</span>}
          {(e.sourceUrl ?? e.mediaUrl) && (
            <a href={e.sourceUrl ?? e.mediaUrl ?? "#"} target="_blank" rel="noreferrer" className="underline">원문 보기</a>
          )}
        </div>
      </div>
    </article>
  );
}
```
(`line-clamp-2`는 Tailwind 3.3+ 내장. 프로젝트 tailwind 버전이 3.3 미만이면 `@tailwindcss/line-clamp` 대신 `overflow-hidden`으로 대체하고 발췌를 120자로 자른다.)

`LawArticleCard.tsx`:
```tsx
import { Scale } from "lucide-react";
import { useState } from "react";

import { originLabel, originTone } from "@/components/evidence/evidenceMeta";
import { StatusBadge } from "@/components/ui/Badge";
import type { Evidence } from "@/types/evidence";

/** 조문은 사진 대신 항 원문을 크게 보여준다. 시행일과 법제처 링크가 출처다 */
export default function LawArticleCard({ e }: { e: Evidence }) {
  const [open, setOpen] = useState(false);
  const full = typeof e.meta.fullText === "string" ? e.meta.fullText : e.snippet;
  const eff = typeof e.meta.effectiveOn === "string" ? e.meta.effectiveOn : null;
  return (
    <article id={`evidence-${e.no}`} className="rounded-lg border border-slate-200 bg-white p-3 shadow-card" aria-label={`근거 #${e.no}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-slate-500">#{e.no}</span>
        <Scale size={14} className="text-slate-400" aria-hidden />
        <span className="text-xs text-slate-500">법 조문{eff ? ` · 시행 ${eff}` : ""}</span>
        <StatusBadge tone={originTone(e)}>{originLabel(e)}</StatusBadge>
      </div>
      <p className="mt-1 text-stage font-semibold">{e.title}</p>
      <p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{open ? full : e.snippet}</p>
      <div className="mt-2 flex items-center gap-3 text-xs">
        {full !== e.snippet && <button type="button" className="underline text-slate-500" onClick={() => setOpen((v) => !v)}>{open ? "접기" : "전문 보기"}</button>}
        {e.sourceUrl && <a href={e.sourceUrl} target="_blank" rel="noreferrer" className="underline text-slate-500">법제처 원문</a>}
      </div>
    </article>
  );
}
```

`MsdsCard.tsx`:
```tsx
import { originLabel, originTone } from "@/components/evidence/evidenceMeta";
import { StatusBadge } from "@/components/ui/Badge";
import type { Evidence } from "@/types/evidence";

const SECTION_NAME: Record<string, string> = { "02": "유해성·위험성", "05": "폭발·화재시 대처", "07": "취급·저장", "08": "노출방지·보호구" };

/** GHS 픽토그램이 글자보다 먼저 읽힌다. 아이콘은 리포 동봉 SVG(/ghs/GHSxx.svg) */
export default function MsdsCard({ e }: { e: Evidence }) {
  const codes = typeof e.meta.pictograms === "string" ? e.meta.pictograms.split(",").filter(Boolean) : [];
  const sections = (e.meta.sections ?? {}) as Record<string, string[]>;
  return (
    <article id={`evidence-${e.no}`} className="rounded-lg border border-slate-200 bg-white p-3 shadow-card" aria-label={`근거 #${e.no}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs text-slate-500">#{e.no}</span>
        <span className="text-xs text-slate-500">MSDS</span>
        <StatusBadge tone={originTone(e)}>{originLabel(e)}</StatusBadge>
      </div>
      <p className="mt-1 text-stage font-semibold">{e.title}</p>
      {codes.length > 0 && (
        <div className="mt-2 flex gap-2">
          {codes.map((c) => <img key={c} src={`/ghs/${c}.svg`} alt={c} title={c} className="h-10 w-10" />)}
        </div>
      )}
      <dl className="mt-2 grid gap-1 text-sm">
        {Object.entries(sections).map(([code, lines]) => (
          <div key={code}>
            <dt className="text-xs font-semibold text-slate-500">{SECTION_NAME[code] ?? `항목 ${code}`}</dt>
            <dd className="text-slate-700">{lines.slice(0, 4).join(" · ")}</dd>
          </div>
        ))}
      </dl>
      {e.sourceUrl && <a href={e.sourceUrl} target="_blank" rel="noreferrer" className="mt-2 inline-block text-xs underline text-slate-500">공단 MSDS 원문</a>}
    </article>
  );
}
```

`EvidenceGrid.tsx`:
```tsx
import { useState } from "react";

import EvidenceCard from "@/components/evidence/EvidenceCard";
import PhotoLightbox from "@/components/evidence/PhotoLightbox";
import cn from "@/lib/cn";
import type { Evidence } from "@/types/evidence";

interface Props { items: Evidence[]; title?: string; collapsedByDefault?: boolean; className?: string }

/** 카드 목록. 3장 이하 세로, 4장 이상 2열. 사진 클릭은 라이트박스 */
export default function EvidenceGrid({ items, title = "근거", collapsedByDefault = false, className }: Props) {
  const [open, setOpen] = useState(!collapsedByDefault);
  const [photo, setPhoto] = useState<Evidence | null>(null);
  if (items.length === 0) return null;
  return (
    <section className={cn("mt-2", className)} aria-label={title}>
      <button type="button" className="text-xs font-semibold text-slate-500 underline" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        {title} {items.length}건 {open ? "접기" : "펼치기"}
      </button>
      {open && (
        <div className={cn("mt-2 grid gap-2", items.length >= 4 ? "md:grid-cols-2" : "grid-cols-1")}>
          {items.map((e) => <EvidenceCard key={`${e.kind}-${e.refKey}-${e.no}`} e={e} onOpenPhoto={setPhoto} />)}
        </div>
      )}
      {photo && <PhotoLightbox e={photo} onClose={() => setPhoto(null)} />}
    </section>
  );
}
```

`PhotoLightbox.tsx`(기존 `components/ui/Modal` 사용):
```tsx
import Modal from "@/components/ui/Modal";
import type { Evidence } from "@/types/evidence";

export default function PhotoLightbox({ e, onClose }: { e: Evidence; onClose: () => void }) {
  return (
    <Modal open onClose={onClose} title={e.title}>
      <img src={e.mediaUrl ?? undefined} alt={e.title} className="max-h-[70vh] w-full rounded object-contain" />
      <p className="mt-2 text-xs text-slate-500">출처: 한국산업안전보건공단 사고사망 게시판 · 근거 #{e.no}</p>
    </Modal>
  );
}
```
(`Modal`의 실제 props 이름(`open`/`isOpen`, `title`)은 `components/ui/Modal.tsx`를 열어 맞춘다.)

GHS SVG 9종: UN GHS 픽토그램은 저작권이 없는 표준 도형이다. `frontend/public/ghs/GHS01.svg`~`GHS09.svg`로 저장한다(각 파일 상단 주석에 출처 "UNECE GHS pictograms, public domain"). 위키미디어 커먼즈의 `GHS-pictogram-*.svg`를 받아 파일명만 코드로 바꾼다. 별지2 출처 목록에 한 줄 추가.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd frontend && npx vitest run src/components/evidence && npm run lint`
Expected: PASS, 경고 0

```bash
git add frontend/src/types frontend/src/components/evidence frontend/public/ghs
git commit -m "feat(frontend): 근거 카드 컴포넌트 — 사례·지침·조문·MSDS 카드, 그리드, 라이트박스, GHS 픽토그램"
```

---

### Task 2: `CitationChip` + `AgentMessage` 인용 렌더링

**Files:**
- Create: `frontend/src/components/evidence/CitationChip.tsx`
- Modify: `frontend/src/components/common/AgentMessage.tsx` (`renderInline`에 `[#n]` 처리, `knownNos` prop)
- Test: `frontend/src/components/evidence/__tests__/CitationChip.test.tsx`, `AgentMessage.test.tsx` 갱신

**Interfaces:**
- Produces: `CitationChip({ no, known })` — known이면 버튼(클릭 시 `document.getElementById("evidence-" + no)`로 `scrollIntoView` + `ring` 강조 1.5초), 아니면 평문 `[#n]`. `AgentMessage({ text, knownNos?: Set<number> })`.

- [ ] **Step 1: 실패하는 테스트**

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AgentMessage from "@/components/common/AgentMessage";

describe("AgentMessage 인용 칩", () => {
  it("[#n]을 칩으로, 사이 텍스트는 유지", () => {
    render(<AgentMessage text="사례 [#1]와 [#2][#3] 참고. 없는 [#9]." knownNos={new Set([1, 2, 3])} />);
    expect(screen.getAllByRole("button", { name: /근거 #/ })).toHaveLength(3);
    expect(screen.getByText(/사례/)).toBeInTheDocument();
    expect(screen.getByText(/참고\./)).toBeInTheDocument();
    expect(screen.getByText("[#9]")).toBeInTheDocument();   // 모르는 번호는 평문
  });

  it("knownNos가 없으면 전부 평문", () => {
    render(<AgentMessage text="a [#1] b" />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText("[#1]")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`CitationChip.tsx`:
```tsx
import cn from "@/lib/cn";

interface Props { no: number; known: boolean }

/** 본문의 [#n]. 원장에 있는 번호만 칩이 되고, 클릭하면 카드로 스크롤·강조한다 */
export default function CitationChip({ no, known }: Props) {
  if (!known) return <>{`[#${no}]`}</>;
  const jump = () => {
    const el = document.getElementById(`evidence-${no}`);
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    el.classList.add("ring-2", "ring-progress-border");
    window.setTimeout(() => el.classList.remove("ring-2", "ring-progress-border"), 1500);
  };
  return (
    <button type="button" onClick={jump} aria-label={`근거 #${no}`}
      className={cn("mx-0.5 inline-flex items-center rounded border border-progress-border bg-progress-bg px-1 font-mono text-[11px] text-progress-text align-baseline")}>
      #{no}
    </button>
  );
}
```

`AgentMessage.tsx`: 시그니처를 `({ text, knownNos }: { text: string; knownNos?: Set<number> })`로, `renderInline(text)`를 `renderInline(text, knownNos)`로 바꾸고 굵게 처리 뒤 각 평문 조각을 다시 `/(\[#\d{1,4}\])/g`로 나눠 `CitationChip`을 끼운다:
```tsx
function renderCitations(part: string, key: number, knownNos?: Set<number>): ReactNode {
  const pieces = part.split(/(\[#\d{1,4}\])/g);
  return pieces.map((p, i) => {
    const m = p.match(/^\[#(\d{1,4})\]$/);
    if (!m) return <Fragment key={`${key}-${i}`}>{p}</Fragment>;
    const no = Number(m[1]);
    return <CitationChip key={`${key}-${i}`} no={no} known={!!knownNos?.has(no)} />;
  });
}
```
그리고 `renderInline`의 평문 분기에서 `return <Fragment key={i}>{renderCitations(part, i, knownNos)}</Fragment>;`.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd frontend && npx vitest run src/components && npm run lint`
Expected: PASS

```bash
git add frontend/src/components/evidence/CitationChip.tsx frontend/src/components/common/AgentMessage.tsx frontend/src/components/**/__tests__
git commit -m "feat(frontend): [#n] 인용 칩 — 카드로 스크롤·강조, 모르는 번호는 평문"
```

---

### Task 3: `useAgentStream` — `ai.evidence` 누적·복원, `ChatThread`·`WorkPlanPage` 연결

**Files:**
- Modify: `frontend/src/pages/WorkPlan/hooks/useAgentStream.ts`
- Modify: `frontend/src/pages/WorkPlan/components/ChatThread.tsx`
- Modify: `frontend/src/pages/WorkPlan/WorkPlanPage.tsx` (오른쪽 열 요약 한 줄)
- Modify: `frontend/src/pages/WorkPlan/components/WorkPlanDetailModal.tsx` (참고 자료 그리드)
- Modify: `frontend/src/types/workPlan.ts` (`WorkPlanDetail.evidence: Evidence[]`)
- Test: `frontend/src/pages/WorkPlan/hooks/__tests__/useAgentStream.test.ts` (케이스 추가)

**Interfaces:**
- Produces: `Turn { role; text; evidence: Evidence[] }`, 훅 반환에 `evidenceCount: { total; photos; guides; laws; msds }`, `knownNos: Set<number>`.
- Consumes: `/transcript` 응답 `{ role, text, evidence }[]`.

- [ ] **Step 1: 실패하는 테스트 (기존 테스트 파일의 헬퍼 `emit`/`mockStream` 패턴을 그대로 쓴다)**

```ts
it("턴별 근거 누적 — ai.evidence는 그 턴의 assistant에만 붙는다", async () => {
  const { result } = renderHook(() => useAgentStream());
  await act(() => result.current.send("첫 질문"));
  emit({ type: "ai.evidence", seq: 1, correlationId: "c", payload: { items: [ev(1)] } });
  emit({ type: "ai.token", seq: 2, correlationId: "c", payload: "답 1 [#1]" });
  emit({ type: "ai.done", seq: 3, correlationId: "c", payload: null });
  await act(() => result.current.send("둘째 질문"));
  emit({ type: "ai.evidence", seq: 1, correlationId: "c", payload: { items: [ev(2)] } });
  emit({ type: "ai.token", seq: 2, correlationId: "c", payload: "답 2 [#2]" });
  const asst = result.current.turns.filter((t) => t.role === "assistant");
  expect(asst[0].evidence.map((e) => e.no)).toEqual([1]);
  expect(asst[1].evidence.map((e) => e.no)).toEqual([2]);
  expect([...result.current.knownNos]).toEqual([1, 2]);
  expect(result.current.evidenceCount.total).toBe(2);
});

it("복원 근거 — transcript의 evidence가 턴에 붙는다", async () => {
  sessionStorage.setItem("saife.conversationId", "old");
  mockGet.mockResolvedValueOnce({ data: [{ role: "user", text: "q", evidence: [] }, { role: "assistant", text: "a [#1]", evidence: [ev(1)] }] });
  const { result } = renderHook(() => useAgentStream());
  await waitFor(() => expect(result.current.restoring).toBe(false));
  expect(result.current.turns[1].evidence).toHaveLength(1);
  expect(result.current.knownNos.has(1)).toBe(true);
});
```
(`ev(n)`은 테스트 파일 상단에 `Evidence` 픽스처 팩토리로 추가.)

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`useAgentStream.ts` 변경점:
```ts
export interface Turn { role: "user" | "assistant"; text: string; evidence: Evidence[] }
interface AgentEventMap { /* 기존 */ "ai.evidence": EvidencePayload; }
```
- 상태 `pendingEvidenceRef = useRef<Evidence[]>([])`. `"ai.evidence"` 핸들러: `if (!accept(env)) return; pendingEvidenceRef.current = p.items;`
- `"ai.token"` 핸들러에서 새 assistant 턴을 만들 때 `evidence: pendingEvidenceRef.current`를 넣고 `pendingEvidenceRef.current = []`. 이미 assistant 턴이 있으면(이어 붙이기) 기존 evidence 유지(백엔드는 `ai.evidence`를 `ai.token` 앞에 한 번만 보낸다).
- `"ai.done"` 핸들러: 토큰 없이 근거만 온 경우를 위해 `pendingEvidenceRef.current.length > 0`이면 빈 텍스트 assistant 턴을 추가하고 비운다.
- `send()`에서 user 턴 추가 시 `evidence: []`, `pendingEvidenceRef.current = []`.
- 복원: `res.data.map(l => ({ role, text, evidence: l.evidence ?? [] }))`.
- 파생값: `const knownNos = useMemo(() => new Set(turns.flatMap(t => t.evidence.map(e => e.no))), [turns]);` `evidenceCount`는 kind별 개수(`CASE_*`→photos는 `mediaUrl` 있는 것만).
- 반환에 `knownNos, evidenceCount` 추가. `reset()`에서 `pendingEvidenceRef.current = []`.

`ChatThread.tsx`: assistant 턴 렌더를
```tsx
<div key={...} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm">
  <AgentMessage text={turn.text} knownNos={knownNos} />
  <EvidenceGrid items={turn.evidence} title="근거" />
</div>
```
로. props에 `knownNos: Set<number>` 추가.

`WorkPlanPage.tsx`: `ToolTracePanel` 아래에
```tsx
{agent.evidenceCount.total > 0 && (
  <p className="mt-2 text-xs text-slate-500">이 대화의 근거 {agent.evidenceCount.total}건 · 사진 {agent.evidenceCount.photos} · 지침 {agent.evidenceCount.guides} · 조문 {agent.evidenceCount.laws} · MSDS {agent.evidenceCount.msds}</p>
)}
```

`WorkPlanDetailModal.tsx`: 브리핑 섹션 아래 `<EvidenceGrid items={detail.evidence} title="참고 자료" collapsedByDefault />`. `types/workPlan.ts`의 `WorkPlanDetail`에 `evidence: Evidence[]`.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd frontend && npx vitest run src/pages/WorkPlan && npm run lint && npm run build`
Expected: PASS

```bash
git add frontend/src/pages/WorkPlan frontend/src/types/workPlan.ts
git commit -m "feat(frontend): 대화 턴별 근거 카드 — ai.evidence 누적·복원, 인용 칩, 계획서 참고 자료"
```

---

### Task 4: UC2·UC1 화면 + 전역 상태 줄

**Files:**
- Modify: `frontend/src/types/incident.ts` (`RecallView.similarCases`, `IncidentRegisterResponse.evidence`), `frontend/src/types/vision.ts` (`VisionCandidate.evidence`)
- Modify: `frontend/src/pages/Incident/components/IncidentResult.tsx`
- Modify: `frontend/src/pages/Vision/components/CandidateCard.tsx`
- Create: `frontend/src/types/system.ts`, `frontend/src/api/systemApi.ts`, `frontend/src/components/layout/SystemStatusLine.tsx`
- Modify: `frontend/src/components/layout/GlobalSidebar.tsx` (하단에 상태 줄)
- Test: `IncidentResult.test.tsx`(케이스 추가), `CandidateCard.test.tsx`, `SystemStatusLine.test.tsx`

**Interfaces:**
- `types/system.ts`: `SystemStatus { demoMode: boolean; embeddingAvailable: boolean; evidenceChunkCount: number; evidenceByKind: Record<string, number>; circuitOpenHosts: string[]; lastCrawlAt: string | null }`
- `systemApi.status(signal) → SystemStatus` (`GET SYSTEM_STATUS`)

- [ ] **Step 1: 실패하는 테스트**

`SystemStatusLine.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import SystemStatusLine from "@/components/layout/SystemStatusLine";

describe("SystemStatusLine", () => {
  it("실시간·벡터", () => {
    render(<SystemStatusLine status={{ demoMode: false, embeddingAvailable: true, evidenceChunkCount: 17000, evidenceByKind: {}, circuitOpenHosts: [], lastCrawlAt: null }} />);
    expect(screen.getByText(/외부 API 실시간 · 벡터 검색/)).toBeInTheDocument();
  });
  it("캐시 모드·키워드, 회로 열림 표시", () => {
    render(<SystemStatusLine status={{ demoMode: true, embeddingAvailable: false, evidenceChunkCount: 0, evidenceByKind: {}, circuitOpenHosts: ["law.go.kr"], lastCrawlAt: null }} />);
    expect(screen.getByText(/캐시 모드 · 키워드 검색/)).toBeInTheDocument();
    expect(screen.getByText(/law.go.kr 일시 차단/)).toBeInTheDocument();
  });
  it("status가 없으면 아무것도 안 그린다", () => {
    const { container } = render(<SystemStatusLine status={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
```

`IncidentResult.test.tsx`에 추가:
```tsx
it("동종 유사 사고 카드와 조사표 인용 칩", () => {
  render(<IncidentResult r={{ ...fixture, recall: { ...fixture.recall, similarCases: [ev(1)] }, evidence: [ev(1), ev(2)], draft: { ...fixture.draft, prevention: "덮개 설치 [#2]" } }} />);
  expect(screen.getByText(/동종 유사 사고/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
});
```

`CandidateCard.test.tsx`:
```tsx
it("근거 3건이 접혀 있다가 펼쳐진다", () => {
  render(<CandidateCard c={{ ...candidate, evidence: [ev(1), ev(2), ev(3)] }} busy={false} onDecide={() => {}} />);
  const toggle = screen.getByRole("button", { name: /근거 3건 펼치기/ });
  expect(screen.queryByLabelText("근거 #1")).toBeNull();
  fireEvent.click(toggle);
  expect(screen.getByLabelText("근거 #1")).toBeInTheDocument();
});
```

- [ ] **Step 2: 실패 확인 → Step 3: 구현**

`SystemStatusLine.tsx`:
```tsx
import type { SystemStatus } from "@/types/system";

/** 사이드바 하단 한 줄. 출처 상태는 세 화면 공통이라 전역에서 보인다 */
export default function SystemStatusLine({ status }: { status: SystemStatus | null }) {
  if (!status) return null;
  const mode = !status.demoMode && status.embeddingAvailable ? "외부 API 실시간 · 벡터 검색" : "캐시 모드 · 키워드 검색";
  return (
    <div className="px-3 py-2 text-[11px] leading-4 text-slate-400" aria-live="polite">
      <p>{mode}</p>
      {status.circuitOpenHosts.length > 0 && <p>{status.circuitOpenHosts.join(", ")} 일시 차단</p>}
    </div>
  );
}
```
`GlobalSidebar.tsx`에서 `useApiData(systemApi.status)`로 1회 조회(폴링 없음) → `<SystemStatusLine status={data} />`를 메뉴 아래에 둔다.

`IncidentResult.tsx`: "사고 전 이 설비에 기록돼 있던 사항" 카드 안 맨 아래에
```tsx
{r.recall.similarCases.length > 0 && (
  <>
    <h3 className="mt-3 text-xs font-semibold text-slate-500">동종 유사 사고 (공단 사례)</h3>
    <EvidenceGrid items={r.recall.similarCases} title="유사 사례" />
  </>
)}
```
조사표 카드의 `cause`·`prevention`을 `<AgentMessage text={r.draft.prevention} knownNos={new Set(r.evidence.map(e => e.no))} />`로 바꾸고, 카드 하단에 `<EvidenceGrid items={r.evidence.filter(e => e.kind === "LAW")} title="법적 근거" collapsedByDefault />`.

`CandidateCard.tsx`: `ruleTrace` 아래에 `<EvidenceGrid items={c.evidence ?? []} title="근거" collapsedByDefault />`. 타입 `VisionCandidate.evidence: Evidence[]`.

- [ ] **Step 4: 통과 확인 + 커밋**

Run: `cd frontend && npx vitest run && npm run lint && npm run build`
Expected: PASS 전부

```bash
git add frontend/src
git commit -m "feat(frontend): 사고 등록 유사 사례·법적 근거 카드, 사진 판독 후보 근거, 전역 출처 상태 줄"
```

---

### Task 5: 스모크 스크립트 `evidence_smoke.py`

**Files:**
- Create: `docs/experiments/evidence_smoke.py`
- Modify: `docs/experiments/README.md`

- [ ] **Step 1: 스크립트 작성** (기존 `uc3_smoke.py`의 SSE 파서를 import해 쓴다)

```python
"""근거 계층 스모크: UC3 3턴 → ai.evidence 수신, 카드 URL 200, [#n] 정합, UC2 유사 사례, UC1 후보 근거.
실행: python docs/experiments/evidence_smoke.py [--base http://localhost:8080]
"""
import argparse, json, re, sys, urllib.request
from uc3_smoke import chat_turn   # (message, conversation_id) -> (events, conversation_id)

def get(url):
    with urllib.request.urlopen(url, timeout=30) as r: return r.status, r.read()

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--base", default="http://localhost:8080"); a = ap.parse_args()
    checks = []
    # UC3
    turns = ["내일 공장동 후면 차양부에서 사다리 놓고 천장 페인트 칠할 건데요", "높이는 3미터쯤이고 안전대 부착설비는 아직 없어요", "제품은 유성페인트예요"]
    cid = None; known = set(); evidence_turns = 0; all_items = []
    for t in turns:
        events, cid = chat_turn(a.base, t, cid)
        ev = [e for e in events if e["type"] == "ai.evidence"]
        tok = "".join(e["payload"] for e in events if e["type"] == "ai.token")
        if ev:
            evidence_turns += 1
            for it in ev[0]["payload"]["items"]: known.add(it["no"]); all_items.append(it)
        cited = {int(n) for n in re.findall(r"\[#(\d+)\]", tok)}
        checks.append(("M12 인용 정합 (턴)", cited <= known, f"cited={sorted(cited)} known={sorted(known)}"))
    checks.append(("M9 근거 첨부 턴 ≥ 1", evidence_turns >= 1, f"{evidence_turns}/3"))
    # M10 링크 유효율 (카드 URL)
    bad = []
    for it in all_items:
        for key in ("thumbnailUrl", "mediaUrl"):
            u = it.get(key)
            if u and u.startswith("/api/"):
                try:
                    s, _ = get(a.base + u)
                    if s != 200: bad.append((key, u, s))
                except Exception as e: bad.append((key, u, str(e)))
    checks.append(("M10 카드 미디어 URL 200", not bad, f"{len(all_items)}장 중 실패 {bad[:3]}"))
    photos = sum(1 for it in all_items if it["kind"].startswith("CASE") and it.get("mediaUrl"))
    cases = sum(1 for it in all_items if it["kind"].startswith("CASE"))
    checks.append(("M11 사진 첨부율", cases == 0 or photos / cases >= 0.6, f"{photos}/{cases}"))
    # UC2
    body = json.dumps({"equipmentId": 1, "occurredAt": "2026-09-28T10:00:00+09:00", "severity": "LOST_TIME", "leaveDays": 5, "accidentType": "FALL", "description": "사다리에서 천장 도장 중 추락"}).encode()
    req = urllib.request.Request(a.base + "/api/incident", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r: inc = json.loads(r.read())
    checks.append(("UC2 유사 사례 ≥ 1", len(inc["recall"]["similarCases"]) >= 1, str(len(inc["recall"]["similarCases"]))))
    checks.append(("UC2 근거 번호 유일", len({e["no"] for e in inc["evidence"]}) == len(inc["evidence"]), ""))
    # 시스템 상태
    s, b = get(a.base + "/api/system/status"); st = json.loads(b)
    checks.append(("근거 청크 > 15000", st["evidenceChunkCount"] > 15000, str(st["evidenceChunkCount"])))
    ok = all(c[1] for c in checks)
    for name, passed, note in checks: print(("PASS" if passed else "FAIL"), name, note)
    sys.exit(0 if ok else 1)

if __name__ == "__main__": main()
```
(`uc3_smoke.chat_turn`이 없으면 그 파일의 SSE 루프를 함수로 추출한다. UC2 요청 필드명은 `types/incident.ts`의 `RegisterIncidentRequest`와 같다.)

- [ ] **Step 2: 실행**

Run: `python docs/experiments/evidence_smoke.py`
Expected: 전부 PASS (라이브 키 있을 때 M12 정합, 키 없을 때는 KEYWORD_FALLBACK 카드로 M9·M10 PASS)

- [ ] **Step 3: 커밋**

```bash
git add docs/experiments/evidence_smoke.py docs/experiments/README.md
git commit -m "test(smoke): 근거 계층 스모크 — M9~M12, UC2 유사 사례, 시스템 상태"
```

---

## Self-Review

- 스펙 §8.1(컴포넌트 6종 — `EvidenceGrid`가 `PhotoLightbox`를 품는다), §8.2(UC3 말풍선 카드·오른쪽 요약·상세 모달 참고 자료, UC2 유사 사례·법적 근거·칩, UC1 접힌 근거, 전역 상태 줄), §8.3(타입) 커버. 서식 "참고 자료"는 B1 Task 4.
- 타입 일관성: `Evidence` 필드명이 B1 `Evidence` 레코드와 동일. `Turn.evidence`·`knownNos`는 `ChatThread`가 받는다.
- Review Focus 5건 모두 테스트 존재(Task 1·2·3).
