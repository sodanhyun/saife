import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "./styles/index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {/* v7 동작을 지금 켠다. 안 켜면 콘솔이 미래 플래그 경고로 채워져
        진짜 에러가 묻힌다 — 시연 중 콘솔을 열어 보일 수도 있다 */}
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
