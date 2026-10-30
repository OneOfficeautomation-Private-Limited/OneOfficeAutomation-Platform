// Peer check: npm installs no peers by itself here (.npmrc legacy-peer-deps), so this fails when a package's required
// peer is missing or the wrong version. Optional peers that are not installed are fine.
import { spawnSync } from "node:child_process";

const npm = process.platform === "win32" ? "npm.cmd" : "npm";
const r = spawnSync(npm, ["ls", "--all", "--json"], { encoding: "utf8", shell: process.platform === "win32" });
const problems = (JSON.parse(r.stdout || "{}").problems ?? []).filter((p) => !/^missing: .*, required by .*optional/i.test(p));
if (problems.length) {
  console.error("Package problems:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log("Peers: every required peer is installed at a matching version.");
