import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AgentMessage from "@/components/common/AgentMessage";

describe("AgentMessage 인용 칩", () => {
  it("문장 중간의 [#n]을 칩으로, 주변 텍스트는 그대로 유지한다", () => {
    render(<AgentMessage scope="chat" text="추락 방지 조치가 필요합니다 [#2] 확인 바랍니다." knownNos={new Set([2])} />);
    expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
    expect(screen.getByText(/추락 방지 조치가 필요합니다/)).toBeInTheDocument();
    expect(screen.getByText(/확인 바랍니다\./)).toBeInTheDocument();
  });

  it("문장 끝의 [#n]도 칩이 되고 앞 텍스트는 유지된다", () => {
    render(<AgentMessage scope="chat" text="근거는 다음과 같습니다 [#3]" knownNos={new Set([3])} />);
    expect(screen.getByRole("button", { name: "근거 #3" })).toBeInTheDocument();
    expect(screen.getByText(/근거는 다음과 같습니다/)).toBeInTheDocument();
  });

  it("붙어 있는 [#1][#2]도 각각 칩이 되고 텍스트가 사라지지 않는다", () => {
    render(<AgentMessage scope="chat" text="사례 [#1][#2] 참고." knownNos={new Set([1, 2])} />);
    expect(screen.getAllByRole("button", { name: /근거 #/ })).toHaveLength(2);
    expect(screen.getByRole("button", { name: "근거 #1" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "근거 #2" })).toBeInTheDocument();
    expect(screen.getByText(/사례/)).toBeInTheDocument();
    expect(screen.getByText(/참고\./)).toBeInTheDocument();
  });

  it("원장에 없는 번호는 평문으로 남는다", () => {
    render(<AgentMessage scope="chat" text="없는 근거 [#7] 입니다." knownNos={new Set([1, 2, 3])} />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText(/\[#7\]/)).toBeInTheDocument();
  });

  it("사이 텍스트와 모르는 번호가 섞여도 전부 보존된다", () => {
    render(<AgentMessage scope="chat" text="사례 [#1]와 [#2][#3] 참고. 없는 [#9]." knownNos={new Set([1, 2, 3])} />);
    expect(screen.getAllByRole("button", { name: /근거 #/ })).toHaveLength(3);
    expect(screen.getByText(/사례/)).toBeInTheDocument();
    expect(screen.getByText(/참고\./)).toBeInTheDocument();
    expect(screen.getByText(/\[#9\]/)).toBeInTheDocument();
  });

  it("knownNos가 없으면(기존 호출부) 전부 평문 — 기존 동작 불변", () => {
    render(<AgentMessage scope="chat" text="a [#1] b" />);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByText(/\[#1\]/)).toBeInTheDocument();
  });
});
