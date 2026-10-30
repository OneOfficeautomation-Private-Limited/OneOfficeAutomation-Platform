# Interrupt handlers

Toolkit `ooa.interrupts`, version 1.0.0. Rules that deal with unexpected windows and pop-ups during UI automation, checked before each UI action.

[All toolkits](README.md)

## Contents

- **Interrupts**: Add interrupt rule, Check interrupt rules now, Clear interrupt rules, List interrupt rules, Remove interrupt rule

## Interrupts

### Add interrupt rule

`ooa.interrupts/add_rule` (action)

Add (or replace by name) a rule for this run: when a window or element appears, click, press keys, close the window or run a child agent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Rule name; adding a rule with the same name replaces it. Example: `Session expired` |
| When window | `when_window` | Text | No |  | Main | Window title pattern: text the title contains, a wildcard pattern with * and ?, or re: followed by a regular expression. Example: `*Session expired*` |
| When element | `when_element` | Text | No |  | Main | Element selector that marks the pop-up, looked for in the browser session below. Example: `#cookie-banner` |
| Then | `then` | Text | Yes |  | Main | Response: click the target, press the target keys, close the matched window, run the target agent, or only log it. One of: `click`, `press_keys`, `close_window`, `run_agent`, `ignore` |
| Target | `target` | Text | No |  | Main | For click: a selector in the browser session or x,y screen coordinates; for press_keys: keys such as ENTER or ALT+F4; for run_agent: the agent id. Shown when: `{"then": ["click", "press_keys", "run_agent"]}` |
| Inputs | `inputs` | Dictionary | No |  | Main | Inputs for the child agent (run_agent). Shown when: `{"then": ["run_agent"]}` |
| Browser session | `browser_session` | Text | No | `browser` | More options | Browser session for element rules. |
| Maximum times | `max_times` | Number | No | `0` | More options | Respond at most this many times in the run; 0 means no limit. Range: 0 to  |

*Time limit 60 s.*

### Check interrupt rules now

`ooa.interrupts/check_now` (action)

Check the rules immediately, for example after a step that often raises a pop-up, and respond to any that match.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `handled` | List | Names of the rules that responded. |
| `count` | Number | How many rules responded. |

*Time limit 60 s; needs: desktop.*

### Clear interrupt rules

`ooa.interrupts/clear_rules` (action)

Remove every interrupt rule from this run.

No settings.

*Time limit 60 s.*

### List interrupt rules

`ooa.interrupts/list_rules` (action)

Return the active rules and how often each has responded.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rules` | List | One dictionary per rule. |

*Time limit 60 s; safe to repeat.*

### Remove interrupt rule

`ooa.interrupts/remove_rule` (action)

Remove one interrupt rule by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Name | `name` | Text | Yes |  | Main | Rule name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `removed` | Yes/No | True when a rule with that name existed. |

*Time limit 60 s.*
