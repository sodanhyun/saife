import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import SystemStatusLine from "@/components/layout/SystemStatusLine";

describe("SystemStatusLine", () => {
  it("라이브 모델과 벡터 검색, 근거 색인 건수를 보인다", () => {
    render(
      <SystemStatusLine
        status={{ demoMode: false, embeddingAvailable: true, evidenceChunkCount: 17000, evidenceByKind: {}, circuitOpenHosts: [], lastCrawlAt: null }}
      />,
    );
    expect(screen.getByText("Gemini 라이브")).toBeInTheDocument();
    expect(screen.getByText("벡터 검색")).toBeInTheDocument();
    expect(screen.getByText("17,000건")).toBeInTheDocument();
  });

  it("데모 모드와 키워드 검색, 외부 API 차단을 표시한다", () => {
    render(
      <SystemStatusLine
        status={{ demoMode: true, embeddingAvailable: false, evidenceChunkCount: 0, evidenceByKind: {}, circuitOpenHosts: ["law.go.kr"], lastCrawlAt: null }}
      />,
    );
    expect(screen.getByText("데모 모드(고정 응답)")).toBeInTheDocument();
    expect(screen.getByText("키워드 검색")).toBeInTheDocument();
    expect(screen.getByText("외부 API 일시 차단")).toBeInTheDocument();
  });

  it("status가 없으면 아무것도 안 그린다", () => {
    const { container } = render(<SystemStatusLine status={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});
