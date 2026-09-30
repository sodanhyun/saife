import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import MsdsCard from "@/components/evidence/MsdsCard";

const BASE = {
  no: 1,
  kind: "MSDS" as const,
  refId: null,
  refKey: "MSDS:001032",
  title: "톨루엔 MSDS (공단)",
  snippet: "",
  sourceUrl: "https://msds",
  mediaUrl: null,
  thumbnailUrl: null,
  origin: "LIVE" as const,
  score: 1,
  fetchedAt: "2026-09-28T14:02:00+09:00",
};

describe("MsdsCard", () => {
  it("픽토그램·H코드·노출기준을 그린다", () => {
    render(<MsdsCard
      scope="chat"
      e={{ ...BASE,
      meta: { pictograms: "GHS02,GHS07", sections: { "02": ["인화성 액체 : 구분2", "H225 고인화성 액체 및 증기"], "08": ["TWA 50ppm", "STEL 150ppm"] } } }} />);
    expect(screen.getAllByRole("img")).toHaveLength(2);
    expect(screen.getByRole("img", { name: "GHS02" })).toHaveAttribute("src", "/ghs/GHS02.svg");
    expect(screen.getByText(/H225/)).toBeInTheDocument();
    expect(screen.getByText(/TWA 50ppm/)).toBeInTheDocument();
  });

  it("sections 값이 손상돼 있어도(문자열·null 등) 예외 없이 그린다 (R35)", () => {
    render(
      <MsdsCard
        scope="chat"
        e={{
          ...BASE,
          meta: {
            pictograms: "GHS02",
            sections: { "02": "문자열로 잘못 온 값", "05": null, "08": ["TWA 50ppm"] },
          },
        }}
      />,
    );
    expect(screen.getByText("톨루엔 MSDS (공단)")).toBeInTheDocument();
    expect(screen.getByText(/TWA 50ppm/)).toBeInTheDocument();
  });

  it("GHS 이미지 로드 실패 시 해당 아이콘만 감춘다", () => {
    render(<MsdsCard scope="chat" e={{ ...BASE, meta: { pictograms: "GHS02,GHS07", sections: {} } }} />);
    expect(screen.getAllByRole("img")).toHaveLength(2);
    fireEvent.error(screen.getByRole("img", { name: "GHS02" }));
    expect(screen.getAllByRole("img")).toHaveLength(1);
    expect(screen.getByRole("img", { name: "GHS07" })).toBeInTheDocument();
  });
});
