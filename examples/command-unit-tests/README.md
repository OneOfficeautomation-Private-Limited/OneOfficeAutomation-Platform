# Command unit tests

One agent per command group (usually one toolkit) that uses every command of the group and checks its results. They
live in One CommandCenter under **Agents > Commands unit testing folder**, so Agent ToolKits shows each command's usage,
and any of them can be run to see which commands work.

## Layout

| Path | What |
|---|---|
| `tools/flowlib.py` | builds agent files (steps, Step groups, Try/Catch, If) |
| `tools/ut.py` | the unit-test pattern: `UnitAgent`, `case`, `check`, `check_eq`, `condition_case`, `expect_error`, `finish`, and `add_sample` (turns a command sample into a case) |
| `tools/groups.py` + `tools/groups_<helper>.py` | which agents exist and which toolkits each covers |
| `tools/cases/<group>.py` | hand-written cases (`def add(ua)`) for commands the samples miss |
| `tools/gen.py` | builds agents: `python -I gen.py <group> ...` or `--all`; prints commands still missing |
| `tools/run.py` | runs agents locally: `python -I run.py "UT String"`; prints PASS/FAIL lines |
| `standin/standin.py` + `standin/plugins/*.py` | the local stand-in for outside services on http://127.0.0.1:8791 |
| `standin/seed-base.json` | made-up records the stand-in starts with |
| `agents/*.flow.json` | the generated agents (checked in to One CommandCenter) |

Test data: `C:/Users/virup/OOA Unit tests` (input `ut_dir`), runs in its `runs` folder. Vault values for local runs:
`secrets.env` there (OOA_SECRET_... lines, never committed). SQL Server test database `ooa_unit_test` (login `ooa_ut`,
password in secrets.env as unit_tests/sqlserver/password): tables Customers, Products, Orders; procedures
CustomerOrders(@CustomerId) and RaisePrice(@Sku, @Percent, @NewPrice OUTPUT).

## Rules for every case

1. One `ua.case(command, title, why)` per case; `why` says in plain English what the command does and what is checked.
   Commands use their full id as steps do (`ooa.excel/read_range`). Conditions: `ua.condition_case(...)`.
2. Check outputs with `ua.check_eq(var, value)` or `ua.check(op, "$var$", value, what=...)`; an expected failure with
   `ua.expect_error(code, lambda f: ...)`.
3. Every case prepares its own data (files under `${ut_dir}` or relative paths in the run folder) and does not depend
   on another case having run, except where a toolkit needs a session opened first (open it in an early case).
4. Outside services: the stand-in only (`$standin_url$`), never a real account. Credentials: made-up values, read
   from the vault (`{"$credential": "unit_tests/<name>/<attribute>"}`) so the agents show the right usage.
5. Commands that drive the screen (mouse, keyboard, windows, desktop apps): build the cases, validate the file, run only
   cases that do not take over the screen; leave the rest for the lead to run at an agreed time.
6. Commands that need One CommandCenter (work queues, shared values, vault, run_bot of checked-in agents, ...): build and
   validate; the lead runs them in One CommandCenter.
7. Plain-English titles and comments in our own words. Never mention Automation Anywhere in agent text.
