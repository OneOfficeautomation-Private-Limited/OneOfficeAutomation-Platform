# 04 Schedules and triggers

## What this covers

How agents are started without a person: schedules (every N minutes, daily, weekly) with time zones and holiday
calendars, and triggers that react to events on a runner, on remote systems polled by the Command Center, or through
webhooks. It specifies who a scheduled or triggered run belongs to, which values the agent receives, how failures are
reported, and what happens with overlapping runs and with occurrences missed while the Command Center was down.
Triggers that live inside an agent (trigger loops, interrupt rules) are in [document 01](01-agents-and-automation-items.md).

## Contents

1. Schedules
2. Holiday calendars
3. How a schedule fires
4. Overlaps and missed runs
5. Triggers: common rules
6. Trigger kinds
7. Runner triggers
8. Command Center triggers (polled)
9. Webhook triggers
10. Values the agent receives
11. Trigger monitoring and states
12. Studio's local triggers
13. Permissions and audit

## 1. Schedules

A schedule (Operations > Schedules) starts the live production version of one agent at set times.

| Field | Rule |
| --- | --- |
| Name | 1-200 characters (default "Schedule") |
| Agent | Must exist and have a live production version; the creator needs folder access **run** |
| Kind | `every` (every N minutes, N ≥ 1), `daily` (at HH:mm) or `weekly` (at HH:mm on chosen days `MON`…`SUN`) |
| Time zone | An IANA name such as `Asia/Kolkata`, `Europe/London` (default `UTC`); an unknown zone is refused (`CR.BAD_SCHEDULE`) |
| Where | A runner, or a runner pool, or neither when a run-as user with a default runner is given |
| Run-as user | Optional; must be enabled; without a runner or pool they need a default runner (`CR.NO_DEFAULT_RUNNER`) |
| Inputs | Fixed input values given to every run |
| Holiday calendar | Optional (section 2) |
| Owner | The person who made it; can be handed to another enabled user |
| Enabled | New schedules are enabled |

Changes after creation: rename, change the holiday calendar, hand to another owner, set or clear the run-as user (a
schedule with no runner of its own must keep a run-as user), enable or disable. Several schedules can be ticked and
enabled, disabled or handed to another owner at once. A schedule's **Run now** starts it at once as the person who
asked, with the schedule's runner, pool, inputs and run-as user (source `schedule:ID`).

The list shows each schedule's agent, where it runs ("default runner of NAME" for run-as schedules), when ("… (time
zone), not on CALENDAR"), owner and run-as user, and next and last run.

## 2. Holiday calendars

- A calendar has a name (letters, digits, spaces, `.`, `_`, `-`, up to 80) and a list of dates (`YYYY-MM-DD`); dates
  are kept sorted and unique.
- On a listed date, in the schedule's own time zone, the schedule does not start its agent. The skip is recorded in the
  audit trail (`schedule.skip_holiday`) and the next run is computed as usual.
- Managing calendars needs `schedules.manage`; seeing them needs `schedules.view`.

## 3. How a schedule fires

The leading Command Center node checks schedules on its clock. For every enabled schedule whose next run time has come:

1. The next run time is computed first and stored (only one node can claim an occurrence).
2. On a holiday the occurrence is skipped (section 2).
3. When the owner is disabled or deleted, nothing starts; the audit trail records `schedule.fire` as failed ("owner …
   is disabled or deleted").
4. Otherwise a run is started **as requested by the owner**, with the owner's role and permissions, on the schedule's
   runner or pool or the run-as user's default runner. Credentials are checked against the run-as user when there is
   one, otherwise against the owner.
5. If the start is refused (no runner of the pool online, licence, no live version, …), the occurrence is lost and the
   reason is recorded as a failed `schedule.fire`.

Next run times:

| Kind | Next run |
| --- | --- |
| every | The moment the occurrence was handled, plus N minutes (so the interval is measured from when it fired) |
| daily | The next HH:mm in the schedule's time zone after now |
| weekly | The next HH:mm on one of the chosen days, in the time zone |

Daily and weekly times follow the time zone's clock changes (summer time). Enabling a schedule computes its next run
from that moment; disabling clears it.

## 4. Overlaps and missed runs

| Situation | Behaviour |
| --- | --- |
| The previous run of the same schedule is still queued or running | By default (**When the previous run is still going: Start another run**, `overlap` empty or `allow`) a new run is queued anyway. On a single runner it waits its turn (a runner does one run at a time, by priority then age). With a pool it may start on another idle runner and run alongside. With **Skip this time** (`overlap` = `skip`) no run is started and `schedule.skip_overlap` is recorded; the next time is computed as usual. |
| The runner is offline at the scheduled time | The run is queued on that runner and starts when the runner comes back. |
| A pool has no online runner | The start is refused (`CR.NO_RUNNER`), recorded as a failed `schedule.fire`, and not retried. |
| The Command Center was stopped over one or more scheduled times | On restart, a schedule whose time has passed fires **once**; the occurrences in between are not caught up, and the next time is computed from the restart. With **Times missed while the Command Center was down: Skip them** (`missed_runs` = `skip`) a time more than 15 minutes in the past is not run at all (`schedule.skip_missed`); the default is **Run once for them** (`missed_runs` empty or `once`). |
| Several Command Center nodes | Only the leading node fires schedules; another node takes over within about 30 seconds if it stops. |

## 5. Triggers: common rules

A trigger (Operations > Triggers) starts the live production version of an agent when an event happens.

| Field | Rule |
| --- | --- |
| Name | 1-200 characters |
| Kind | One of the kinds in section 6 |
| Agent | Must exist with a live production version; the creator needs folder access **run** |
| Runner | Required for every kind. Runner kinds watch that machine; for every kind, the runs go to that runner. |
| Run-as user | Optional, enabled user |
| Owner | The creator; the trigger fires with the owner's permissions. Can be handed to another enabled user. |
| Settings | Per kind (sections 7-9). Secrets are always vault references, never values. |

A trigger can be renamed, handed over, given a run-as user, paused, resumed and deleted. Resuming clears the last
error and polls a Command Center trigger again soon. Over the API: `POST /api/triggers/{id}/pause` and `/resume`;
`GET /api/triggers` lists triggers with their fire count, last fired time and last error.

## 6. Trigger kinds

| Kind (stored id) | Label | Evaluated by | Windows only |
| --- | --- | --- | --- |
| `file` | File arrives | Runner | No |
| `file_changed` | File changed | Runner | No |
| `file_deleted` | File deleted | Runner | No |
| `folder_created` | Folder created | Runner | No |
| `folder_deleted` | Folder deleted | Runner | No |
| `window_opened` | Window opened | Runner | Yes |
| `window_closed` | Window closed | Runner | Yes |
| `process_started` | Process started | Runner | No |
| `process_stopped` | Process stopped | Runner | No |
| `service_started` | Service started | Runner | Yes |
| `service_stopped` | Service stopped | Runner | Yes |
| `hotkey` | Hotkey pressed | Runner | Yes (needs the Window toolkit) |
| `email` | New email (IMAP) | Command Center | No |
| `outlook` | New email (Microsoft 365 Outlook) | Command Center | No |
| `servicenow` | ServiceNow record | Command Center | No |
| `http_poll` | Universal poller (HTTP) | Command Center | No |
| `webhook` | Webhook | Command Center, on call | No |

## 7. Runner triggers

The runner receives its triggers with each heartbeat and looks at its machine every 2 seconds. The **first look only
records what is there**; later looks fire for differences, so files present when a trigger starts do not fire.

| Kind | Settings | Fires when |
| --- | --- | --- |
| File arrives | Folder (required), file pattern (default `*`), sub-folders (up to 10 levels) | A new matching file has stopped changing over one more look (an empty file after three looks); a file that disappears before it settles does not fire |
| File changed | As above | A matching file's size or modification time changed and then settled |
| File deleted, Folder deleted | As above | A matching file or folder that was there is gone |
| Folder created | As above | A new matching folder appears |
| Window opened, closed | Title pattern (wildcards, or `re:` and a regular expression) | A matching window appears or disappears |
| Process started, stopped | Process name only, such as `notepad.exe` (no path) | A process of that name starts or stops |
| Service started, stopped | Windows service name as `sc query` shows it | The service reaches running or stopped |
| Hotkey | Up to five keys joined with `+`, such as `CTRL+ALT+F9` | The global hotkey is pressed |

When the runner cannot watch a trigger (folder missing, hotkey taken, kind not supported on that system) it reports the
reason, which shows as the trigger's last error; when it can again, the error clears.

## 8. Command Center triggers (polled)

| Setting | Rule |
| --- | --- |
| Interval | `interval_seconds`, at least 15, default 60, at most 86,400 |
| One at a time | A trigger is never polled twice at once |
| Per poll | At most 50 events fire per poll; the rest fire at the next poll |
| First poll | Only records where things stand (the newest message, record or item), unless the setting `fire_existing` is on, in which case existing items fire too |
| Secrets | Read from the vault as the trigger's owner, who must be a consumer of the vault folder (`VAULT.DENIED` otherwise); every read is audited |
| Network | ServiceNow and universal poller addresses must pass the Command Center's outbound address check |
| Poll now | `POST /api/triggers/{id}/poll` checks the trigger at once |

| Kind | Settings |
| --- | --- |
| New email (IMAP) | Host, SSL (default on), port (default 993 with SSL, 143 without), user name, password (vault reference), mail folder (default `INBOX`), optional "from contains" and "subject contains" filters |
| New email (Microsoft 365 Outlook) | Either a Microsoft Graph token (vault reference), or app sign-in with tenant id, client id and client secret (vault reference) plus the mailbox to read; mail folder (default `inbox`); optional Graph and sign-in addresses; the same filters |
| ServiceNow record | Instance URL, table (such as `incident`), optional encoded query; user name and password reference, or a token reference. Fires for new or updated records |
| Universal poller | URL to call, JSON path to the items (default `$`), the id field of each item, optional authorisation (vault reference) and headers (the `Authorization` header is not allowed as a plain header). Fires once per new id; up to 300 recent ids are remembered |

## 9. Webhook triggers

- Creating a webhook trigger returns its address `/api/hooks/{id}` and a secret, shown once and stored as a hash.
- A call with the right secret starts the agent with the request body as `payload`. A wrong secret or unknown trigger is
  refused (`CR.FORBIDDEN`, the attempt is audited as denied); a paused webhook is refused with `CR.TRIGGER_DISABLED`.

## 10. Values the agent receives

The agent receives only the values it declares as inputs:

| Input | Contents |
| --- | --- |
| `trigger_event` | Everything about the event, plus `kind`, `trigger_id`, `trigger_name` and `time` |
| `trigger_file` | The file or folder path (file and folder kinds) |
| `trigger_window` | The window title |
| `trigger_process` | The process name |
| `trigger_service` | The service name |
| `trigger_hotkey` | The key combination |
| `trigger_email` | The message (email and Outlook kinds) |
| `trigger_record` | The ServiceNow record |
| `trigger_item` | The polled item |
| `payload` | The webhook request body |

## 11. Trigger monitoring and states

| State | Meaning |
| --- | --- |
| active | Enabled, no error |
| error | Enabled, but the last poll, watch or start failed; the last error is shown |
| paused | Switched off |

The list also shows where each kind is evaluated, whether it is Windows only, the fire count and the time it last
fired. A successful start increases the count and clears the error; a refused start (no live version, licence,
disabled runner …) keeps the error ("Could not start the agent: …").

## 12. Studio's local triggers

One Agent Studio has its own Triggers panel for development, separate from the Command Center's: file arrives in a
folder (with a pattern), schedule (every N minutes or daily at HH:mm), and local webhook (a secret shown once). They
start agents of the workspace only while Studio is running, can be switched on and off, test-run, and show their
recent activity.

## 13. Permissions and audit

| Action | Permission |
| --- | --- |
| See schedules and calendars | `schedules.view` |
| Create, change, enable, disable, delete schedules and calendars; hand over | `schedules.manage` |
| See triggers | `triggers.view` |
| Create, change, pause, resume, delete triggers | `triggers.manage` |

Audited actions include `schedule.create`, `schedule.update`, `schedule.enable`, `schedule.disable`,
`schedule.bulk_*`, `schedule.delete`, `schedule.fire`, `schedule.skip_holiday`, `calendar.save`, `calendar.delete`,
`trigger.create`, `trigger.update`, `trigger.enable`, `trigger.disable`, `trigger.delete`, `trigger.fire` (denied
webhooks), and every credential read by a trigger. A runner used by a schedule or trigger cannot be removed, and an
agent used by one cannot be deleted.

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [01 Agents and automation items, section 9](01-agents-and-automation-items.md)
- [07 Vault, credentials and connections](07-vault-credentials-and-connections.md)
- [Command Center and runner, section 5](../user-guide/00-README.md)
