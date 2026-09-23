import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import FormField from "@/components/ui/FormField";
import Select from "@/components/ui/Select";

describe("FormField", () => {
  it("자식 입력 하나를 label htmlFor로 묶어 라벨로 찾을 수 있다", () => {
    render(
      <FormField label="사고 설비">
        <Select defaultValue="">
          <option value="">(설비 미상)</option>
        </Select>
      </FormField>,
    );
    expect(screen.getByLabelText("사고 설비").tagName).toBe("SELECT");
  });

  it("자식이 명시한 id는 그대로 두고 라벨이 그 id를 가리킨다", () => {
    render(
      <FormField label="재해자">
        <input id="victim-name" />
      </FormField>,
    );
    const input = screen.getByLabelText("재해자");
    expect(input.id).toBe("victim-name");
    expect(screen.getByText("재해자").getAttribute("for")).toBe("victim-name");
  });

  it("자식이 여럿이면 htmlFor 없이 기존처럼 라벨만 그린다", () => {
    render(
      <FormField label="기간">
        <input aria-label="시작" />
        <input aria-label="끝" />
      </FormField>,
    );
    expect(screen.getByText("기간").hasAttribute("for")).toBe(false);
  });
});
