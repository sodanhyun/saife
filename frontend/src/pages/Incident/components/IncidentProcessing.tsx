// IncidentProcessing.tsx — 등록 요청이 도는 동안의 자리. 소환·수시평가·기한은 결정론이라 금방 끝나고,
// 조사표 문안은 모델이 근거를 인용해 쓰느라 시간이 걸린다는 사실을 그대로 말한다(가짜 진행률을 만들지 않는다).
import { useEffect, useState } from "react";

const STAGES = ["설비 이력 소환", "수시평가 생성", "조사표 기한 계산", "작업계획서 경고", "조사표 문안 작성"];

export default function IncidentProcessing() {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);

  return (
    <section
      role="status"
      aria-live="polite"
      className="rounded-xl border border-brand-line bg-brand-soft px-6 py-5 animate-fade-in"
    >
      <div className="flex items-center gap-3">
        <span className="relative flex h-2.5 w-2.5">
          <span className="absolute inset-0 rounded-full bg-brand animate-ping-soft" />
          <span className="relative h-2.5 w-2.5 rounded-full bg-brand" />
        </span>
        <p className="text-stage font-semibold text-brand-ink">사고를 기록하고 이 설비의 이력을 잇는 중입니다</p>
        <span className="ml-auto text-sm tabular-nums text-slate-500">{seconds}초</span>
      </div>
      <p className="mt-2 flex flex-wrap gap-x-2 text-sm text-slate-600">
        {STAGES.map((s, i) => (
          <span key={s}>
            {s}
            {i < STAGES.length - 1 && <span className="ml-2 text-slate-400">/</span>}
          </span>
        ))}
      </p>
    </section>
  );
}
