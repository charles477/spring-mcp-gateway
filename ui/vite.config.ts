import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies API + auth calls to the gateway and Keycloak so the
// browser sees a single origin and no CORS config is needed in dev.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/admin": "http://localhost:8080",
      "/mcp": "http://localhost:8080",
      "/api": "http://localhost:8080",
      "/realms": "http://localhost:8081",
    },
  },
});
