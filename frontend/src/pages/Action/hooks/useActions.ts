import { useCallback, useState } from "react";

import { useSearchParams } from "react-router-dom";

import { actionApi } from "@/api/actionApi";
import { useApiData } from "@/hooks/useApiData";
import { useDebounce } from "@/hooks/useDebounce";
import { parseFilter } from "@/pages/Action/utils/actionRow";
import { useToastStore } from "@/stores/useToastStore";
import { CURRENT_USER } from "@/components/layout/menu";
import type { ActionListFilter, ActionListItem, PhotoCheck } from "@/types/action";
import type { RiskLevel } from "@/types/domain";
import { getServerMessage } from "@/utils/errorMessage";

/**
 * 이행 확인 입력. 증빙은 올린 뒤 서버가 돌려준 값(사진 경로, 대조 결과)이다
 * @property version 같은 경로에 사진을 다시 올렸을 때 이미지를 새로 받게 하는 값
 */
export interface VerifyDraft {
  evidence: { evidenceUrl: string | null; photoCheck: PhotoCheck | null; version: number } | null;
  resultNote: string;
  verifiedBy: string;
  residualLevel: RiskLevel | null;
}

const emptyDraft = (): VerifyDraft => ({
  evidence: null,
  resultNote: "",
  verifiedBy: `${CURRENT_USER.role} ${CURRENT_USER.name}`,
  residualLevel: null,
});

/** 한 번에 보이는 줄 수. "더 보기"마다 이만큼 늘린다 */
export const PAGE_SIZE = 20;

/**
 * 개선대책 목록(탭, 검색, 더 보기)과 이행 확인(증빙 사진, 대조, 확인자, 개선 후 위험성).
 * 탭은 URL ?status=가 원천이다(홈에서 ?status=OVERDUE로 들어온다). 목록은 서버 데이터에서만 파생한다.
 */
export function useActions() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = parseFilter(searchParams.get("status"));
  const [keyword, setKeywordState] = useState("");
  const [size, setSize] = useState(PAGE_SIZE);
  const debounced = useDebounce(keyword, 300);

  const list = useApiData({
    fetchFn: (signal) => actionApi.search({ status: filter, keyword: debounced, page: 0, size }, signal),
    deps: [filter, debounced, size],
    errorMessage: "개선대책을 불러오지 못했습니다",
    skipFirstSkeleton: true,
  });
  const counts = useApiData({ fetchFn: (signal) => actionApi.counts(signal), deps: [], skipFirstSkeleton: true });

  const [target, setTarget] = useState<ActionListItem | null>(null);
  const [draft, setDraft] = useState<VerifyDraft>(emptyDraft);
  const [uploading, setUploading] = useState(false);
  const [completing, setCompleting] = useState(false);
  const toastSuccess = useToastStore((s) => s.success);
  const toastError = useToastStore((s) => s.error);

  const refetchList = list.refetch;
  const refetchCounts = counts.refetch;

  const attachPhoto = useCallback(async (file: File | undefined) => {
    if (!target || !file) return;
    setUploading(true);
    try {
      const v = await actionApi.attachEvidence(target.id, file);
      setDraft((d) => ({ ...d, evidence: { evidenceUrl: v.evidenceUrl, photoCheck: v.photoCheck, version: Date.now() } }));
    } catch (e) {
      toastError(getServerMessage(e) ?? "증빙 사진을 올리지 못했습니다");
    } finally {
      setUploading(false);
    }
  }, [target, toastError]);

  const confirmVerify = useCallback(async () => {
    if (!target || !draft.residualLevel) return;
    setCompleting(true);
    try {
      await actionApi.verify(target.id, {
        resultNote: draft.resultNote.trim() || null,
        verifiedBy: draft.verifiedBy.trim(),
        residualLevel: draft.residualLevel,
      });
      toastSuccess("이행 확인을 기록함");
      setTarget(null);
      refetchList();
      refetchCounts();
    } catch (e) {
      toastError(getServerMessage(e) ?? "이행 확인을 기록하지 못했습니다");
    } finally {
      setCompleting(false);
    }
  }, [target, draft, toastSuccess, toastError, refetchList, refetchCounts]);

  const setFilter = (next: ActionListFilter) => {
    setSize(PAGE_SIZE);
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next === "OPEN") p.delete("status");
        else p.set("status", next);
        return p;
      },
      { replace: true },
    );
  };

  const actions = list.data?.content ?? [];
  const total = list.data?.totalElements ?? 0;

  return {
    filter,
    setFilter,
    keyword,
    setKeyword: (v: string) => {
      setKeywordState(v);
      setSize(PAGE_SIZE);
    },
    searching: debounced.trim() !== "",
    actions,
    total,
    hasMore: actions.length < total,
    loadMore: () => setSize((s) => s + PAGE_SIZE),
    counts: counts.data,
    loading: list.loading && !list.initialLoaded,
    loadError: list.error,
    refetch: () => {
      refetchList();
      refetchCounts();
    },
    target,
    draft,
    uploading,
    requestComplete: (row: ActionListItem) => {
      setDraft(emptyDraft());
      setTarget(row);
    },
    cancelComplete: () => {
      if (!completing && !uploading) setTarget(null);
    },
    attachPhoto: (file: File | undefined) => void attachPhoto(file),
    updateDraft: (patch: Partial<VerifyDraft>) => setDraft((d) => ({ ...d, ...patch })),
    confirmComplete: () => void confirmVerify(),
    completing,
  };
}
