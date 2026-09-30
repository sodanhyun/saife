import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import SystemStatusLine from "@/components/layout/SystemStatusLine";

describe("SystemStatusLine", () => {
  it("실시간·벡터", () => {
    render(
      <SystemStatusLine
        status={{ demoMode: false, embeddingAvailable: true, evidenceChunkCount: 17000, evidenceByKind: {}, circuitOpenHosts: [], lastCrawlAt: null }}
      />,
    );
    expect(screen.getByText(/외부 API 실시간 · 벡터 검색/)).toBeInTheDocument();
    expect(screen.getByText("근거 17,000건")).toBeInTheDocument();
  });

  it("캐시 모드·키워드, 회로 열림 표시", () => {
    render(
      <SystemStatusLine
        status={{ demoMode: true, embeddingAvailable: false, evidenceChunkCount: 0, evidenceByKind: {}, circuitOpenHosts: ["law.go.kr"], lastCrawlAt: null }}
      />,
    );
    expect(screen.getByText(/캐시 모드 · 키워드 검색/)).toBeInTheDocument();
    expect(screen.getByText(/law.go.kr 일시 차단/)).toBeInTheDocument();
  });

  it("status가 없으면 아무것도 안 그린다", () => {
    const { container } = render(<SystemStatusLine status={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("마지막 수집 시각이 있으면 표시한다", () => {
    render(
      <SystemStatusLine
        status={{ demoMode: false, embeddingAvailable: true, evidenceChunkCount: 3, evidenceByKind: {}, circuitOpenHosts: [], lastCrawlAt: "2026-09-28T00:00:00+09:00" }}
      />,
    );
    expect(screen.getByText(/최근 수집 2026-09-28/)).toBeInTheDocument();
  });

  it("evidenceByKind가 있으면 종류별 개수를 0건 제외하고 보여준다", () => {
    render(
      <SystemStatusLine
        status={{
          demoMode: true,
          embeddingAvailable: false,
          evidenceChunkCount: 41281,
          evidenceByKind: { CASE_DISASTER: 6372, CASE_FATALITY: 2946, GUIDE: 29097, LAW: 2866, MSDS: 0 },
          circuitOpenHosts: [],
          lastCrawlAt: null,
        }}
      />,
    );
    expect(screen.getByText("사고사망 사례 2,946 · 재해 사례 6,372 · KOSHA GUIDE 29,097 · 법 조문 2,866")).toBeInTheDocument();
  });
});
