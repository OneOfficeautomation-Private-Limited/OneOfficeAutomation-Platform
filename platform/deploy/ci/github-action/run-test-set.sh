#!/usr/bin/env bash
# Runs a One CommandCenter test set and waits for its cases (the GitHub Action in this folder calls it).
# Reads OOA_URL, OOA_TOKEN, OOA_SET, OOA_RUNNER, OOA_POOL and OOA_TIMEOUT (minutes); writes test_run, passed and total
# to $GITHUB_OUTPUT; exits 1 when a case fails or the run does not finish in time.
set -euo pipefail
: "${GITHUB_OUTPUT:=/dev/null}"
base="${OOA_URL%/}/api"
auth=(-H "Authorization: Bearer $OOA_TOKEN" -H "Content-Type: application/json")
body=$(jq -nc --arg d "$OOA_RUNNER" --arg p "$OOA_POOL" '{} + (if $d != "" then {device_id: $d} else {} end) + (if $p != "" then {pool_id: $p} else {} end)')
started=$(curl -fsS -X POST "${auth[@]}" -d "$body" "$base/test-sets/$OOA_SET/run")
tr=$(echo "$started" | jq -r .test_run_id)
echo "test_run=$tr" >> "$GITHUB_OUTPUT"
echo "Started test run $tr ($(echo "$started" | jq -r .started) of $(echo "$started" | jq -r .cases) cases)"
deadline=$(( $(date +%s) + OOA_TIMEOUT * 60 ))
while :; do
  r=$(curl -fsS "${auth[@]}" "$base/test-sets/runs/$tr")
  status=$(echo "$r" | jq -r .status)
  if [ "$status" != "running" ]; then break; fi
  if [ "$(date +%s)" -gt "$deadline" ]; then echo "::error::The test run did not finish in $OOA_TIMEOUT minutes"; exit 1; fi
  sleep 10
done
echo "passed=$(echo "$r" | jq -r .passed)" >> "$GITHUB_OUTPUT"
echo "total=$(echo "$r" | jq -r .total)" >> "$GITHUB_OUTPUT"
echo "$r" | jq -r '.results[] | "\(if .passed then "PASS" else "FAIL" end)  \(.case_name)  \(.detail // "")"'
if [ "$status" != "passed" ]; then echo "::error::Test run $tr: $(echo "$r" | jq -r .passed) of $(echo "$r" | jq -r .total) cases passed"; exit 1; fi
echo "All $(echo "$r" | jq -r .total) cases passed."
