# Process mining

Toolkit `ooa.mining`, version 1.0.0. Discover a process from an event log (cases, activities, times): variants, flows, waiting times and a diagram; check conformance.

[All toolkits](README.md)

## Contents

- **Process mining**: Check conformance, Discover process

## Process mining

### Check conformance

`ooa.mining/check_conformance` (action)

Check every case of an event log against the expected flow: which activity may follow which, where cases may start and end.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Event log = A CSV file | A CSV event log with a header row. Shown when: `{"table": [""]}` |
| Table | `table` | Table | No |  | Main; choice Event log = A table | The event log as a table. Shown when: `{"file": [""]}` |
| Allowed flows | `allowed_flows` | Dictionary | Yes |  | Main | Activity to the activities that may come right after it. Example: `{"Create order": ["Approve", "Cancel"], "Approve": ["Ship"]}` |
| Start activities | `start_activities` | List | No |  | Main | Activities a case may start with; any when empty. |
| End activities | `end_activities` | List | No |  | Main | Activities a case may end with; any when empty. |
| Case column | `case_column` | Text | No | `case` | More options | The case id column. |
| Activity column | `activity_column` | Text | No | `activity` | More options | The activity column. |
| Time column | `time_column` | Text | No | `time` | More options | The time column. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `conforming` | Number | Cases that follow the expected flow. |
| `deviating` | Number | Cases that do not. |
| `fitness` | Number | Share of conforming cases, 0 to 1. |
| `deviations` | Table | case, problem: each deviation found. |

*Time limit 600 s; safe to repeat; needs: filesystem.*

### Discover process

`ooa.mining/discover_process` (action)

From an event log (case, activity, time per row): the activities, the variants (paths cases take), the flows between activities with waiting times, case durations and a diagram.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Event log = A CSV file | A CSV event log with a header row. Example: `logs/orders.csv`; Shown when: `{"table": [""]}` |
| Table | `table` | Table | No |  | Main; choice Event log = A table | The event log as a table. Shown when: `{"file": [""]}` |
| Case column | `case_column` | Text | No | `case` | Main | The column with the case id (order number, ticket...). |
| Activity column | `activity_column` | Text | No | `activity` | Main | The column with what happened. |
| Time column | `time_column` | Text | No | `time` | Main | The column with when it happened. |
| Top variants | `top_variants` | Number | No | `20` | More options | How many variants to list. Range: 1 to 1000 |
| Minimum edge count | `min_edge_count` | Number | No | `1` | More options | Leave rarer flows out of the diagram. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `cases` | Number | Cases in the log. |
| `events` | Number | Events in the log. |
| `activities` | Table | activity, events, cases. |
| `variants` | Table | variant (activities in order), cases, share (%), avg_duration_minutes; most frequent first. |
| `flows` | Table | from, to, count, avg_wait_minutes: one activity followed directly by the next. |
| `start_activities` | Dictionary | Activity to how many cases start with it. |
| `end_activities` | Dictionary | Activity to how many cases end with it. |
| `duration` | Dictionary | Case duration in minutes: average, median, p90, max. |
| `diagram` | Text | The process as a Mermaid flowchart (paste it into any Mermaid viewer, or a Markdown file). |

*Time limit 600 s; safe to repeat; needs: filesystem.*
