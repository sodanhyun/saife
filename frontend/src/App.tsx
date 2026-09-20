import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { WorkPlanChatPage } from "@/pages/WorkPlanChatPage";
import { VisionPage } from "@/pages/VisionPage";
import { IncidentPage } from "@/pages/IncidentPage";
import { TimelinePage } from "@/pages/TimelinePage";

/**
 * 화면 구성은 시연 순서를 그대로 따른다.
 *
 *   사진 판독(UC1) → 작업계획서(UC3) → 사고 등록(UC2) → 설비 타임라인(UC4)
 *
 * 마지막이 타임라인인 이유는, 앞의 세 화면에서 만든 기록이 하나의 설비 ID 위에
 * 쌓여 있는 것을 마지막에 보여주기 위해서다. 영상의 마지막 컷이다.
 */
const TABS = [
  { to: "/vision", label: "사진 판독", hint: "UC1" },
  { to: "/work-plan", label: "작업계획서", hint: "UC3" },
  { to: "/incident", label: "사고 등록", hint: "UC2" },
  { to: "/timeline", label: "설비 타임라인", hint: "UC4" },
];

export default function App() {
  return (
    <div className="flex h-screen flex-col bg-slate-50">
      <header className="flex items-center gap-6 border-b bg-white px-6 py-3">
        <div className="flex items-baseline gap-2">
          <span className="text-lg font-bold tracking-tight">SAIFE</span>
          <span className="text-xs text-slate-500">
            현장의 안전 문서들은 서로를 기억하지 못합니다. SAIFE는 기억합니다.
          </span>
        </div>

        <nav className="ml-auto flex gap-1">
          {TABS.map((t) => (
            <NavLink
              key={t.to}
              to={t.to}
              className={({ isActive }) =>
                `rounded px-3 py-1.5 text-sm ${
                  isActive ? "bg-slate-900 text-white" : "text-slate-700 hover:bg-slate-100"
                }`
              }
            >
              {t.label}
              <span className="ml-1.5 text-[10px] opacity-60">{t.hint}</span>
            </NavLink>
          ))}
        </nav>
      </header>

      <main className="flex-1 overflow-hidden">
        <Routes>
          <Route path="/" element={<Navigate to="/work-plan" replace />} />
          <Route path="/vision" element={<VisionPage />} />
          <Route path="/work-plan" element={<WorkPlanChatPage />} />
          <Route path="/incident" element={<IncidentPage />} />
          <Route path="/timeline" element={<TimelinePage />} />
          <Route path="*" element={<Navigate to="/work-plan" replace />} />
        </Routes>
      </main>
    </div>
  );
}
