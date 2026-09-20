import { useEffect, useRef, useState } from "react";
import { equipmentApi, formUrl, visionApi } from "@/api/saifeApi";
import { useVisionStream } from "@/hooks/useVisionStream";
import { RISK_CLASS, RISK_LABEL } from "@/types/domain";
import type { EquipmentItem } from "@/types/equipment";
import type { AdoptionRate } from "@/types/vision";

/**
 * UC1 — 현장 사진 → 빠진 안전조치 탐지.
 *
 * 화면이 지켜야 하는 두 가지:
 * 1. <b>등급이 모델에서 나온 것처럼 보이면 안 된다.</b> 각 후보의 등급 옆에
 *    판정 근거("사진 판독 기준 잠정 등급: …")를 항상 같이 띄운다.
 * 2. <b>채택 버튼이 사람의 자리다.</b> 후보는 기본이 "판단 전"이고,
 *    사람이 누르기 전에는 채택도 반려도 아니다.
 */
export function VisionPage() {
  const [equipment, setEquipment] = useState<EquipmentItem[]>([]);
  const [equipmentId, setEquipmentId] = useState<number | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [rate, setRate] = useState<AdoptionRate | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const { result, progress, error, analyzing, analyze, applyDecision } = useVisionStream();

  useEffect(() => {
    void equipmentApi.list().then((list) => {
      setEquipment(list);
      setEquipmentId((prev) => prev ?? list[0]?.id ?? null);
    });
    void visionApi.adoptionRate().then(setRate);
  }, []);

  // 판독이 끝나면 채택률을 다시 읽는다. 서버 값에서만 파생한다
  useEffect(() => {
    if (!analyzing) void visionApi.adoptionRate().then(setRate);
  }, [analyzing, result]);

  const onPick = (file: File | undefined) => {
    if (!file) return;
    setPreview(URL.createObjectURL(file));
    void analyze(file, equipmentId);
  };

  const decide = async (hazardId: number, adopt: boolean) => {
    setBusy(hazardId);
    try {
      const updated = adopt ? await visionApi.adopt(hazardId) : await visionApi.reject(hazardId);
      applyDecision(hazardId, updated.adopted);
      setRate(await visionApi.adoptionRate());
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="h-full overflow-auto p-6">
      <header>
        <h1 className="text-xl font-semibold">현장 사진 판독</h1>
        <p className="text-sm text-slate-500">
          사진에서 <strong>빠진 안전조치</strong>를 찾습니다. 사고유형을 분류하는 것이 아니라
          물리적으로 있거나 없는 것만 판정합니다.
        </p>
      </header>

      <section className="mt-4 flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="block text-slate-600">대상 설비</span>
          <select
            className="mt-1 rounded border px-3 py-2"
            value={equipmentId ?? ""}
            onChange={(e) => setEquipmentId(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">(설비 지정 없음)</option>
            {equipment.map((e) => (
              <option key={e.id} value={e.id}>
                {e.name} — {e.locationTag ?? "-"}
              </option>
            ))}
          </select>
        </label>

        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          className="hidden"
          onChange={(e) => onPick(e.target.files?.[0])}
        />
        <button
          className="rounded bg-slate-900 px-5 py-2 text-white disabled:opacity-40"
          disabled={analyzing}
          onClick={() => fileRef.current?.click()}
        >
          {analyzing ? (progress ?? "판독 중…") : "사진 선택"}
        </button>

        {rate && (
          <div className="ml-auto rounded border bg-white px-4 py-2 text-sm">
            <span className="text-slate-500">
              후보 채택률
              {rate.axes && rate.axes.length > 0 && (
                <span className="ml-1 text-xs">({rate.axes.join("·")} 축)</span>
              )}
            </span>{" "}
            <strong className="tabular-nums">
              {rate.adopted}/{rate.suggested}
            </strong>{" "}
            {rate.rate !== null && (
              <span className="tabular-nums">({Math.round(rate.rate * 100)}%)</span>
            )}
          </div>
        )}
      </section>

      {error && (
        <p className="mt-3 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </p>
      )}

      <div className="mt-5 grid gap-5 lg:grid-cols-[380px_1fr]">
        <div>
          {preview ? (
            <img
              src={preview}
              alt="판독 대상"
              className="w-full rounded border bg-white object-contain"
            />
          ) : (
            <div className="flex h-56 items-center justify-center rounded border border-dashed bg-white text-slate-400">
              사진을 선택하세요
            </div>
          )}
        </div>

        <div>
          {result?.demoMode && (
            <p className="mb-3 rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
              데모 모드입니다. API 키가 없어 모델 응답 대신 고정 픽스처를 표시하고 있습니다.
            </p>
          )}

          {result && result.candidates.length === 0 && (
            <p className="rounded border bg-white p-4 text-sm text-slate-600">
              빠진 안전조치를 찾지 못했습니다. 이 사진에 대해서는 판정 대상 6축 중 해당하는
              항목이 없다는 뜻입니다.
            </p>
          )}

          <ul className="space-y-3">
            {result?.candidates.map((c) => (
              <li key={c.hazardId} className="rounded border bg-white p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium">
                    {c.accidentLabel}
                  </span>
                  <span className="font-medium">{c.missingControl}</span>
                  <span
                    className={`rounded border px-2 py-0.5 text-xs font-semibold ${RISK_CLASS[c.riskLevel]}`}
                  >
                    위험성 {RISK_LABEL[c.riskLevel]}
                  </span>
                  {c.alreadyKnown && (
                    <span className="rounded bg-slate-200 px-2 py-0.5 text-xs text-slate-700">
                      기존 위험요인 재확인
                    </span>
                  )}
                  {c.gateStatus === "CHECKLIST" && (
                    <span className="rounded border border-slate-400 px-2 py-0.5 text-xs text-slate-600">
                      참고 — 현장 확인 필요
                    </span>
                  )}
                </div>

                {/* 검증되지 않은 축임을 화면에서 밝힌다. 지표에도 안 들어간다 */}
                {c.gateNote && (
                  <p className="mt-1 text-xs text-slate-500">{c.gateNote}</p>
                )}

                {c.evidence && (
                  <p className="mt-2 text-sm text-slate-700">
                    <span className="text-slate-500">근거: </span>
                    {c.evidence}
                  </p>
                )}

                {/* 등급이 모델에서 나온 것처럼 보이면 안 된다. 근거를 항상 같이 띄운다 */}
                <p className="mt-1 text-xs text-slate-500">{c.ruleTrace}</p>

                <div className="mt-3 flex items-center gap-2">
                  {c.adopted === null ? (
                    <>
                      <button
                        className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40"
                        disabled={busy === c.hazardId}
                        onClick={() => void decide(c.hazardId, true)}
                      >
                        채택
                      </button>
                      <button
                        className="rounded border px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-40"
                        disabled={busy === c.hazardId}
                        onClick={() => void decide(c.hazardId, false)}
                      >
                        반려
                      </button>
                      <span className="text-xs text-slate-400">
                        확정은 사람이 합니다
                      </span>
                    </>
                  ) : (
                    <>
                      <span
                        className={
                          c.adopted
                            ? "text-sm font-medium text-emerald-700"
                            : "text-sm font-medium text-slate-500"
                        }
                      >
                        {c.adopted ? "채택됨" : "반려됨"}
                      </span>
                      <button
                        className="rounded border px-2 py-1 text-xs hover:bg-slate-50"
                        onClick={() => void decide(c.hazardId, !c.adopted)}
                      >
                        되돌리기
                      </button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>

          {result && (
            <a
              className="mt-4 inline-block rounded border px-3 py-1.5 text-sm hover:bg-slate-50"
              href={formUrl.assessment(result.assessmentId)}
              target="_blank"
              rel="noreferrer"
            >
              위험성평가표 (평가 #{result.assessmentId})
            </a>
          )}
        </div>
      </div>
    </div>
  );
}
