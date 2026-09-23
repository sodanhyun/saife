/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // 중립 표면
        page: "#f8fafc",
        panel: "#f1f5f9",
        // ── 의미 팔레트(4층: DEFAULT/bg/border/text) ─────────────────────
        // 색은 편차에만 쓴다. 정상 상태·일반 정보는 무채색(slate).
        // high/medium/pending/progress 값은 MetalFlow OKLCH 팔레트(채도 2/3)와 동일하다.
        risk: {
          high:   { DEFAULT: "#a74541", bg: "#fff0ee", border: "#eaaba5", text: "#83312e" }, // 등급 상 · 사고
          medium: { DEFAULT: "#a1762c", bg: "#fdf5e6", border: "#e3c68d", text: "#7a5511" }, // 등급 중
          low:    { DEFAULT: "#3f7a5c", bg: "#eef7f2", border: "#a9d3bd", text: "#2a5c43" }, // 등급 하
        },
        // 되묻기 슬롯 · 승인 대기 — 합불 축이 아니다. 눈에 걸려야 하는 것.
        pending:  { DEFAULT: "#cd9b3c", bg: "#fff6e7", border: "#ebca93", text: "#8e5400" },
        // 도구 실행 중 · 활성 네비 · 연결 강조 — "지금 여기".
        progress: { DEFAULT: "#356697", bg: "#eef6fe", border: "#a0c0e1", text: "#24527f" },
      },
      fontFamily: {
        // 앞의 가변 폰트가 번들 자체 호스팅분(src/styles/index.css @import). 뒤는 OS 폴백.
        sans: ['"Pretendard Variable"', "Pretendard", "system-ui", '"Apple SD Gothic Neo"', '"Noto Sans KR"', "sans-serif"],
        mono: ["ui-monospace", "Menlo", "monospace"],
      },
      fontSize: {
        // 무대 밀도 — 프로젝터에서 읽혀야 하는 트레이스 패널·타임라인 본문 전용.
        stage: ["15px", { lineHeight: "1.5" }],
      },
      borderRadius: { sm: "6px", md: "8px", lg: "10px", xl: "12px" },
      boxShadow: {
        card: "0 1px 3px rgba(15,23,42,0.06)",
        modal: "0 20px 50px rgba(0,0,0,0.25)",
        toast: "0 8px 20px rgba(0,0,0,0.2)",
      },
      keyframes: {
        "cursor-blink": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0" } },
      },
      animation: {
        "cursor-blink": "cursor-blink 0.8s step-end infinite",
      },
    },
  },
  plugins: [],
};
