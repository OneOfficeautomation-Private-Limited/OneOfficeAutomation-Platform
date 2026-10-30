import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The new Studio (docs/architecture/12-ui-redesign.md). It is built into the CLI's resources and served by Studio at
// <base>/ (the root, or /studio/ under ooa server), so asset paths are relative and nothing is inlined: Studio's security policy allows only its own files.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../cli/build/generated/ui/studio-next",
    emptyOutDir: true,
    assetsInlineLimit: 0,
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
