// ParticipantsInput.tsx — 참여 근로자 이름 칩. Enter나 쉼표로 더하고, 칩의 X로 뺀다.
import { useState } from "react";

import { X } from "lucide-react";

import cn from "@/lib/cn";

interface Props {
  id?: string;
  value: string[];
  onChange: (names: string[]) => void;
  disabled?: boolean;
  invalid?: boolean;
}

export default function ParticipantsInput({ id, value, onChange, disabled = false, invalid = false }: Props) {
  const [text, setText] = useState("");

  const add = (raw: string) => {
    const names = raw
      .split(/[,\n]/)
      .map((n) => n.trim())
      .filter((n) => n && !value.includes(n));
    if (names.length > 0) onChange([...value, ...names]);
    setText("");
  };

  return (
    <div
      className={cn(
        "flex min-h-10 flex-wrap items-center gap-1.5 rounded-lg border bg-white px-2 py-1.5 focus-within:ring-1",
        invalid
          ? "border-risk-high-border focus-within:border-risk-high focus-within:ring-risk-high"
          : "border-slate-300 focus-within:border-slate-500 focus-within:ring-slate-500",
        disabled && "bg-slate-50",
      )}
    >
      {value.map((name) => (
        <span key={name} className="inline-flex items-center gap-1 rounded border border-slate-200 bg-panel px-2 py-0.5 text-sm text-slate-800">
          {name}
          {!disabled && (
            <button
              type="button"
              aria-label={`${name} 빼기`}
              className="text-slate-400 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-500"
              onClick={() => onChange(value.filter((n) => n !== name))}
            >
              <X size={14} aria-hidden />
            </button>
          )}
        </span>
      ))}
      {!disabled && (
        <input
          id={id}
          value={text}
          aria-label="참여 근로자 추가"
          aria-invalid={invalid || undefined}
          placeholder={value.length === 0 ? "참여 근로자 추가" : ""}
          className="min-w-28 flex-1 border-0 bg-transparent px-1 py-0.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-0"
          onChange={(e) => {
            const v = e.target.value;
            if (v.includes(",")) add(v);
            else setText(v);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.nativeEvent.isComposing) {
              e.preventDefault();
              add(text);
            } else if (e.key === "Backspace" && text === "" && value.length > 0) {
              onChange(value.slice(0, -1));
            }
          }}
          onBlur={() => text.trim() && add(text)}
        />
      )}
    </div>
  );
}
