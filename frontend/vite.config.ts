import { defineConfig } from "vite";
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
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
