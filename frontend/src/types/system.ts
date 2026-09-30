/** 전역 시스템 상태 — 백엔드 GET /api/system/status와 1:1. 사이드바 하단 한 줄이 이걸로 그려진다. */

/**
 * @property demoMode          true면 외부 API·모델이 픽스처로 대체된 데모 모드
 * @property embeddingAvailable true면 벡터 검색(임베딩) 가능. false면 키워드 검색으로 낮춰 동작
 * @property evidenceChunkCount 현재 적재된 근거(RAG) 청크 총 개수
 * @property evidenceByKind    근거 종류별(EvidenceKind) 개수
 * @property circuitOpenHosts  회로가 열려(일시 차단) 호출을 쉬고 있는 외부 호스트 목록. 비어 있으면 정상
 * @property lastCrawlAt       가장 최근 공공 API 캐싱 크롤 시각. 없으면 null
 */
export interface SystemStatus {
  demoMode: boolean;
  embeddingAvailable: boolean;
  evidenceChunkCount: number;
  evidenceByKind: Record<string, number>;
  circuitOpenHosts: string[];
  lastCrawlAt: string | null;
}
