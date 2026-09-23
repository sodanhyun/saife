import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    host: true,
    port: 5173,
    proxy: {
      // SSE를 쓰므로 프록시가 버퍼링하지 않도록 주의한다
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      // 법정 서식은 백엔드가 그린 HTML을 새 탭에서 연다
      "/form": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: false,
    exclude: ["**/node_modules/**", "**/dist/**"],
  },
});
