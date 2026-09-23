import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import LinkButton from "@/components/ui/LinkButton";

describe("LinkButton", () => {
  it("<a>로 렌더하고 Button secondary·sm과 같은 외형 클래스를 쓴다", () => {
    render(<LinkButton href="/forms/1">법정 서식</LinkButton>);
    const link = screen.getByRole("link", { name: "법정 서식" });
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("/forms/1");
    expect(link.className).toContain("border-slate-300");
    expect(link.className).toContain("px-3 py-1.5 text-xs");
    expect(link.getAttribute("target")).toBeNull();
  });

  it("external이면 새 탭 + noreferrer", () => {
    render(<LinkButton href="/forms/2" external>위험성평가표</LinkButton>);
    const link = screen.getByRole("link", { name: "위험성평가표" });
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noreferrer");
  });
});
