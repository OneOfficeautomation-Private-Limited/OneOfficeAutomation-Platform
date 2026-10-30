# 12 Agent tests

## What this covers

Agent tests (Build > Agent tests) check that an agent still gives the expected results: a test set lists cases with
inputs and expectations, a test run starts one agent run per case, each run is judged when it ends, and the test run is
scored once every case has an answer. This document specifies test sets and cases, the expectation rules and
comparison operators (suited to exact values and to AI answers), test run states, scoring, history, limits and
permissions.

## Contents

1. Test sets
2. Cases
3. Expectations
4. Output comparisons
5. Running a test set
6. Judging and scoring
7. Test run states and results
8. History
9. Limits
10. Permissions, API and audit

## 1. Test sets

| Property | Rule |
| --- | --- |
| Name | 1-120 characters |
| Agent | One agent (`.flow.json`) the set is for |
| Cases | 1 to 500 |
| Size | The whole set at most 100 KB |

A set is seen by people who may view the agent's folder. Changing and deleting sets needs `repo.checkin`.

## 2. Cases

Each case has:

| Field | Rule |
| --- | --- |
| `name` | Required and unique within the set |
| `inputs` | A dictionary of input values for the agent (optional) |
| `expect` | A dictionary of expectations (section 3) |

## 3. Expectations

| Key | Meaning | Default |
| --- | --- | --- |
| `status` | `success` or `failed` | `success` |
| `error_code` | The run's error code, exact or a family with `.*` (for example `EMAIL.*`) | not checked |
| `outputs` | A dictionary: output name → expected value or comparison rule | not checked |

Order of judging: the status first (a wrong status fails the case at once, with the error message if any); then the
error code; then each listed output. Outputs not listed are not checked.

## 4. Output comparisons

An output's rule is either a plain value (equals) or `{"op": …, "value": …}`:

| Operator | Passes when | Notes |
| --- | --- | --- |
| equals (plain value) | The output equals the value | Numbers compare by value (120.5 equals 120.50); lists and dictionaries compare as JSON |
| `contains` | The output's text contains the value | Ignores upper and lower case |
| `not_contains` | The output's text does not contain the value | Ignores case |
| `matches` | A regular expression finds a match in the output's text | Checked when the set is saved |
| `approx` | A number within `tolerance` of the value | The value must be a number |
| `one_of` | The output equals one of a list of values | |
| `not_empty` | The output has a non-blank value | |

`contains`, `not_contains`, `matches`, `approx`, `one_of` and `not_empty` suit AI answers and Prompt Skills, which word
things differently each time. An unknown operator is refused when the set is saved.

## 5. Running a test set

- **Run** starts one run per case, with the case's inputs, on a chosen runner or runner pool, against the agent's
  **live production version** (the version is recorded with the test run). Runs have the source `test:ID` and are
  requested by the person who started the test.
- Every run passes the usual checks ([document 03](03-runs-runners-and-devices.md)). A case whose run cannot start fails at
  once with "Did not start: …".
- The runs are ordinary runs: they appear in Agent Runs, use credentials as the person who started the test, and count
  for licences.

## 6. Judging and scoring

- When a case's run ends, it is judged against the case's expectations. The verdict is passed or failed, with the
  reasons joined (for example `label: got "low", expected "urgent"`), up to 4,000 characters.
- When no case is still running, the test run is scored: passed cases out of the total. The test run is `passed` when
  every case passed, otherwise `failed`.

## 7. Test run states and results

| State | Meaning |
| --- | --- |
| `running` | At least one case has no answer yet |
| `passed` | Every case passed |
| `failed` | At least one case failed or did not start |

Each case result has the case name, its run id (linking to the run), the run's status, passed or not, the reasons, and
when it finished.

## 8. History

**History** lists a set's last 50 test runs, newest first: version tested, who started it, when it started and finished,
passed and total, and the state. It shows how scores change across versions of the agent.

## 9. Limits

| Limit | Value |
| --- | --- |
| Cases per set | 500 |
| Set size | 100 KB |
| Reason text per case | 4,000 characters |
| History shown | 50 test runs |

## 10. Permissions, API and audit

| Action | Permission |
| --- | --- |
| See sets, test runs and history | `repo.view` and view access to the agent's folder |
| Create, change, delete sets | `repo.checkin` |
| Run a set | `runs.start` and run access to the agent's folder |

API: `/api/test-sets` (GET, POST, PUT, DELETE), `POST /api/test-sets/{id}/run` with `device_id` or `pool_id`,
`GET /api/test-sets/runs/{run}`, `GET /api/test-sets/{id}/history`.

Audited: set changes and deletes (`testset.delete`), `testset.run` (cases and how many started), `testset.scored`
(passed and total; result ok or failed).

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [09 AI features](09-ai-features.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [Command Center and runner, section 5a](../user-guide/00-README.md)
