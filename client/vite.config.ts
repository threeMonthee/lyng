import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// 开发时 Vite 代理 /api 到本机的 Java 服务；生产式运行时由 Java 服务同源托管 dist/。
export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://localhost:8080" } },
  test: { environment: "node" },
});
