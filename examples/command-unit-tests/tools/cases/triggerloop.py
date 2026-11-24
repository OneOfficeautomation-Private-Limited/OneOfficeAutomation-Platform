"""Hand-written cases for the Trigger Loop types that watch files, processes, services, timers and lists.

Every loop is bounded (max_events and max_seconds), so a case never waits for ever; where an event has to happen
while the loop waits, a Run in parallel step makes it happen in a second branch.
"""


def add(ua):
    ua.var("ev", "Dictionary")
    ua.var("ut_n", "Number", 0)
    ua.var("ut_last", "String", "")
    ua.var("ut_running", "Boolean", False)

    ua.condition_case("ooa.triggerloop/process_running", "this computer's Windows shell",
                      "Program is running is a condition: true while a program with that executable name runs (explorer is "
                      "always running on a signed-in desktop).",
                      {"process_name": "explorer.exe"}, True)
    ua.condition_case("ooa.triggerloop/process_running", "no such program",
                      "The same condition is false for a program that is not running.",
                      {"process_name": "ut-no-such-program-9f3a.exe"}, False)

    with ua.case("ooa.triggerloop/file_arrives", "files already in the folder",
                 "Each new or changed file in folder runs the steps for each file; with include_existing the files already "
                 "there count too. Two invoices are written, and the loop sees both and stops after two events.") as f:
        f.act("ooa.folder/delete", desc="Start with an empty folder", path="tl_inbox", recursive=True, cont=True)
        f.act("ooa.folder/create", desc="Create the watched folder", path="tl_inbox")
        f.act("ooa.file/write_text", desc="First invoice", path="tl_inbox/inv-1.txt", text="1")
        f.act("ooa.file/write_text", desc="Second invoice", path="tl_inbox/inv-2.txt", text="2")
        f.set("ut_n", 0)
        with f.loop(desc="Each invoice file", iterator="ooa.triggerloop/file_arrives", **{"as": "ev"},
                    iterator_args={"folder": "tl_inbox", "pattern": "inv-*.txt", "include_existing": True,
                                   "max_events": 2, "max_seconds": 20, "poll_seconds": 1}):
            f.expr("ut_n", "$ut_n + 1", desc="Count the file")
            f.set("ut_last", "$ev.name$", desc="Keep its name")
        ua.check_eq("ut_n", 2)
        ua.check("startsWith", "$ut_last$", "inv-", what="file name")

    with ua.case("ooa.triggerloop/file_arrives", "a file written while the loop waits",
                 "Without include_existing only new files count: a parallel branch writes a file two seconds after the "
                 "loop starts, and the loop gets it.") as f:
        f.act("ooa.folder/delete", desc="Start with an empty folder", path="tl_new", recursive=True, cont=True)
        f.act("ooa.folder/create", desc="Create the watched folder", path="tl_new")
        f.set("ut_n", 0)
        f.set("ut_last", "")
        with f.parallel(2, desc="Watch the folder while a file arrives"):
            with f.step("Watch"):
                with f.loop(desc="Each new file", iterator="ooa.triggerloop/file_arrives", **{"as": "ev"},
                            iterator_args={"folder": "tl_new", "max_events": 1, "max_seconds": 20, "poll_seconds": 1}):
                    f.expr("ut_n", "$ut_n + 1", desc="Count the file")
                    f.set("ut_last", "$ev.name$", desc="Keep its name")
            with f.step("Write a file"):
                f.act("core/delay", desc="Let the loop start", seconds=2)
                f.act("ooa.file/write_text", desc="The new file", path="tl_new/arrived.csv", text="a,b")
        ua.check_eq("ut_n", 1)
        ua.check_eq("ut_last", "arrived.csv")

    with ua.case("ooa.triggerloop/timer_tick", "three ticks a second apart",
                 "Every interval runs the steps on a fixed interval and gives the tick number; with run_immediately the "
                 "first tick comes at once.") as f:
        f.set("ut_n", 0)
        with f.loop(desc="Every second", iterator="ooa.triggerloop/timer_tick", **{"as": "ev"},
                    iterator_args={"interval_seconds": 1, "run_immediately": True, "max_events": 3, "max_seconds": 20}):
            f.set("ut_n", "$ev.tick$", desc="Keep the tick number")
        ua.check_eq("ut_n", 3)

    with ua.case("ooa.triggerloop/process_starts", "a program started while the loop waits",
                 "Each time a program starts runs the steps for each new process of that name: a parallel branch runs "
                 "ping two seconds after the loop starts.") as f:
        f.set("ut_n", 0)
        f.set("ut_last", "")
        with f.parallel(2, desc="Watch for ping while it is started"):
            with f.step("Watch"):
                with f.loop(desc="Each time ping starts", iterator="ooa.triggerloop/process_starts", **{"as": "ev"},
                            iterator_args={"process_name": "PING.EXE", "max_events": 1, "max_seconds": 25, "poll_seconds": 0.5}):
                    f.expr("ut_n", "$ut_n + 1", desc="Count it")
                    f.set("ut_last", "$ev.change$", desc="Keep the change")
            with f.step("Start ping"):
                f.act("core/delay", desc="Let the loop start", seconds=2)
                f.act("ooa.application/run_program", desc="Run ping for about three seconds", program="ping.exe",
                      arguments=["-n", "4", "127.0.0.1"])
        ua.check_eq("ut_n", 1)
        ua.check_eq("ut_last", "started")

    with ua.case("ooa.triggerloop/service_changes", "the current state of the Task Scheduler",
                 "Each time a service changes state; with include_current the service's state now comes first as an event "
                 "(the Task Scheduler always runs).") as f:
        f.set("ut_last", "")
        with f.loop(desc="The Task Scheduler's state", iterator="ooa.triggerloop/service_changes", **{"as": "ev"},
                    iterator_args={"service": "Schedule", "include_current": True, "max_events": 1, "max_seconds": 15}):
            f.set("ut_last", "$ev.state$", desc="Keep the state")
        ua.check("matches", "$ut_last$", "(?i)running", what="state")

    with ua.case("ooa.triggerloop/new_items", "items of a command's list",
                 "Each new e-mail, record or item reads a source again and again and runs the steps for each item not seen "
                 "before; here the source is the Split text command, and include_existing hands over its three parts.") as f:
        f.set("ut_n", 0)
        f.set("ut_last", "")
        with f.loop(desc="Each part", iterator="ooa.triggerloop/new_items", **{"as": "ev"},
                    iterator_args={"source": "ooa.string/split", "source_inputs": {"text": "INV-1,INV-2,INV-3", "delimiter": ","},
                                   "list_output": "items", "include_existing": True, "poll_seconds": 1, "max_events": 3,
                                   "max_seconds": 20}):
            f.expr("ut_n", "$ut_n + 1", desc="Count the item")
            f.set("ut_last", "${ut_last}$ev.item$;", desc="Collect the items")
        ua.check_eq("ut_n", 3)
        ua.check_eq("ut_last", "INV-1;INV-2;INV-3;")
