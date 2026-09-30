import { lazy, Suspense, useState } from "react";

import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import RouteErrorBoundary from "@/components/common/RouteErrorBoundary";
import GlobalSidebar from "@/components/layout/GlobalSidebar";
import { LANDING_PATH } from "@/components/layout/menu";
import MobileHeader from "@/components/layout/MobileHeader";
import PageSkeleton from "@/components/ui/PageSkeleton";
import ToastContainer from "@/components/ui/ToastContainer";

const EquipmentHomePage = lazy(() => import("@/pages/EquipmentHome"));
const EquipmentDetailPage = lazy(() => import("@/pages/Equipment"));
const WorkPlanPage = lazy(() => import("@/pages/WorkPlan"));
const VisionPage = lazy(() => import("@/pages/Vision"));
const IncidentPage = lazy(() => import("@/pages/Incident"));
const TimelinePage = lazy(() => import("@/pages/Timeline"));

export default function App() {
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex min-h-screen flex-col bg-page">
      <ToastContainer />
      <MobileHeader onOpenSidebar={() => setSidebarOpen(true)} />
      <div className="flex flex-1">
        <GlobalSidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
        {/* isolate — 콘텐츠의 z-index가 사이드바 경계 토글 위로 새지 않게 스태킹 컨텍스트를 만든다 */}
        <main className="isolate flex-1 min-w-0 flex flex-col">
          <RouteErrorBoundary resetKey={location.pathname}>
            <Suspense fallback={<PageSkeleton />}>
              <Routes>
                <Route path="/" element={<EquipmentHomePage />} />
                <Route path="/equipment/:equipmentId" element={<EquipmentDetailPage />} />
                <Route path="/work-plan" element={<WorkPlanPage />} />
                <Route path="/vision" element={<VisionPage />} />
                <Route path="/incident" element={<IncidentPage />} />
                <Route path="/timeline" element={<TimelinePage />} />
                <Route path="*" element={<Navigate to={LANDING_PATH} replace />} />
              </Routes>
            </Suspense>
          </RouteErrorBoundary>
        </main>
      </div>
    </div>
  );
}
