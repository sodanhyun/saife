import { Fragment, type ReactNode } from "react";

import CitationChip from "@/components/evidence/CitationChip";

/**
 * 모델 출력 렌더러.
 *
 * <b>모델은 마크다운을 쓴다.</b> 프롬프트로 막아도 새어 나오고, 그대로 두면
 * 화면에 별표가 그대로 찍힌다 — QA에서 "**정확한 작업 장소**가 어디인지"로 나왔다.
 * 프로젝터에 띄우는 화면이라 그냥 둘 수 없다.
 *
 * <p>마크다운 라이브러리를 넣지 않는 이유는 둘이다. 의존성 하나가 별지2(사용
 * 오픈소스 신고)에 줄 하나를 더하고, 무엇보다 <b>HTML을 만들지 않아야 한다</b> —
 * 모델 출력을 innerHTML로 넣는 순간 주입 경로가 생긴다. 여기서는 React 요소만
 * 만들므로 문자열이 HTML로 해석될 일이 없다.
 *
 * <p>지원하는 것은 모델이 실제로 쓰는 것만이다: 굵게, 불릿, 구분선, 제목, 인용.
 * 표나 링크는 나오지 않았고, 나오면 그때 추가한다.
 *
 * <p>인용 <code>[#n]</code>도 인라인에서 함께 처리한다. <code>knownNos</code>에 있는
 * 번호만 클릭 가능한 {@link CitationChip}이 되고, 없는 번호(모델 환각 등)는 평문으로
 * 남는다 — 근거 원장에 없는 카드로 스크롤을 시도하지 않기 위해서다.
 *
 * <p><code>scope</code>는 그대로 {@link CitationChip}에 넘어가 어느 근거 그리드의
 * id·reveal 이벤트를 찾을지 정한다(F18) — 같은 페이지에 번호 공간이 다른 그리드가
 * 여럿 있을 수 있어서다.
 */
export default function AgentMessage({ text, knownNos, scope }: { text: string; knownNos?: Set<number>; scope: string }) {
  if (!text) return null;

  const blocks: ReactNode[] = [];
  let bullets: string[] = [];

  const flushBullets = (key: string) => {
    if (bullets.length === 0) return;
    blocks.push(
      <ul key={key} className="my-1 list-disc space-y-0.5 pl-5">
        {bullets.map((b, i) => (
          <li key={i}>{renderInline(b, knownNos, scope)}</li>
        ))}
      </ul>,
    );
    bullets = [];
  };

  text.split("\n").forEach((rawLine, index) => {
    const line = rawLine.trimEnd();
    const key = `l${index}`;

    // 불릿: "- " 또는 "* "
    const bullet = line.match(/^\s*[-*]\s+(.*)$/);
    if (bullet && !/^\s*[-*]{3,}\s*$/.test(line)) {
      bullets.push(bullet[1]);
      return;
    }
    flushBullets(`u${index}`);

    // 인용: "> 텍스트" — 모델이 경고 블록에 쓴다
    const quote = line.match(/^\s*>\s?(.*)$/);
    if (quote) {
      blocks.push(
        <p key={key} className="border-l-2 border-slate-300 pl-2 text-slate-700">
          {renderInline(quote[1], knownNos, scope)}
        </p>,
      );
      return;
    }

    // 구분선: --- 또는 ***
    if (/^\s*([-*_])\1{2,}\s*$/.test(line)) {
      blocks.push(<hr key={key} className="my-3 border-slate-200" />);
      return;
    }

    // 제목: ### 텍스트
    const heading = line.match(/^\s*(#{1,6})\s+(.*)$/);
    if (heading) {
      blocks.push(
        <p key={key} className="mt-3 font-semibold">
          {renderInline(heading[2], knownNos, scope)}
        </p>,
      );
      return;
    }

    if (line.trim() === "") {
      blocks.push(<div key={key} className="h-2" />);
      return;
    }

    blocks.push(
      <p key={key} className="my-0.5">
        {renderInline(line, knownNos, scope)}
      </p>,
    );
  });

  flushBullets("u-last");

  return <div className="leading-relaxed">{blocks}</div>;
}

/** 인라인 굵게(**x**, __x__)와 인용([#n])을 처리한다. 나머지는 글자 그대로 둔다 */
function renderInline(text: string, knownNos: Set<number> | undefined, scope: string): ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*|__[^_]+__)/g);
  return parts.map((part, i) => {
    const bold = part.match(/^(?:\*\*([^*]+)\*\*|__([^_]+)__)$/);
    if (bold) {
      return <strong key={i}>{bold[1] ?? bold[2]}</strong>;
    }
    return <Fragment key={i}>{renderCitations(part, i, knownNos, scope)}</Fragment>;
  });
}

/** 평문 조각을 [#n] 기준으로 다시 쪼개 인용 칩을 끼운다. 나머지 텍스트는 그대로 보존한다 */
function renderCitations(part: string, key: number, knownNos: Set<number> | undefined, scope: string): ReactNode {
  const pieces = part.split(/(\[#\d{1,4}\])/g);
  return pieces.map((p, i) => {
    const m = p.match(/^\[#(\d{1,4})\]$/);
    if (!m) return <Fragment key={`${key}-${i}`}>{p}</Fragment>;
    const no = Number(m[1]);
    return <CitationChip key={`${key}-${i}`} no={no} known={!!knownNos?.has(no)} scope={scope} />;
  });
}
