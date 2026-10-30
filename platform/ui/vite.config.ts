import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Studio and the rebuilt Command Center console (docs/architecture/12-ui-redesign.md), one build with two pages that
// share their files: index.html is Studio, served at <base>/ (the root, or /studio/ under ooa server); console.html is
// the console, served by the Command Center at /next/. Asset paths are relative and nothing is inlined: the security
// policy allows only the servers' own files.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../cli/build/generated/ui/studio-next",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    sourcemap: false,
    rollupOptions: { input: { studio: "index.html", console: "console.html" } },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    // a test that renders the whole Studio takes 1 to 3 seconds here and longer on CI's Windows machines
    testTimeout: 15000,
  },
});
