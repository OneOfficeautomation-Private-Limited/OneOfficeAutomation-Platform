import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Studio and the rebuilt One CommandCenter console (docs/architecture/12-ui-redesign.md), one build with two pages that
// share their files: index.html is Studio, served at <base>/ (the root, or /studio/ under ooa server); console.html is
// the console, served by One CommandCenter at /next/. Asset paths are relative and nothing is inlined: the security
// policy allows only the servers' own files.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../cli/build/generated/ui/studio-next",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    sourcemap: false,
    // Studio, the console, and Document Automation (AI > Document Automation, its own page) built from the same components
    rollupOptions: { input: { studio: "index.html", console: "console.html", docflow: "docflow.html" } },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    // a test that renders the whole Studio takes 1 to 3 seconds here and much longer on CI's Windows machines, where the
    // Java build runs at the same time (2 Nov 2026: the console's first sign-in test passed in 0.7 s here, timed out at 15 s there)
    testTimeout: 30000,
    setupFiles: ["src/testSetup.ts"],
  },
});
