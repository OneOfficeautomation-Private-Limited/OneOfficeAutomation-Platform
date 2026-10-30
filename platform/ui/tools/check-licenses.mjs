// Licence check over the lock file (docs/architecture/12-ui-redesign.md, section 7). Packages that ship to customers
// must have a permissive licence. Build tools may also be MPL-2.0 (lightningcss, Vite's CSS minifier): their code
// does not ship, and the licence puts no terms on the files they produce.
import { readFileSync } from "node:fs";

const PERMISSIVE = ["MIT", "ISC", "BSD-2-Clause", "BSD-3-Clause", "Apache-2.0", "0BSD", "BlueOak-1.0.0", "CC0-1.0", "Python-2.0", "MIT-0"];
const BUILD_ONLY = [...PERMISSIVE, "MPL-2.0"];
const lock = JSON.parse(readFileSync(new URL("../package-lock.json", import.meta.url), "utf8"));

const bad = [];
let shipped = 0, tools = 0;
for (const [path, pkg] of Object.entries(lock.packages)) {
  if (path === "") continue;
  const allowed = pkg.dev ? BUILD_ONLY : PERMISSIVE;
  pkg.dev ? tools++ : shipped++;
  const licence = String(pkg.license ?? "UNKNOWN");
  // an SPDX expression such as "(MIT OR Apache-2.0)" passes when one choice is allowed
  const choices = licence.replace(/[()]/g, "").split(/\s+OR\s+/);
  if (!choices.some((c) => allowed.includes(c.trim()))) bad.push(`${path.replace(/^.*node_modules\//, "")} ${pkg.version}: ${licence}${pkg.dev ? " (build tool)" : " (ships)"}`);
}
if (bad.length) {
  console.error("Packages with a licence that is not allowed:\n  " + bad.join("\n  "));
  process.exit(1);
}
console.log(`Licences allowed: ${shipped} shipped packages, ${tools} build tools.`);
