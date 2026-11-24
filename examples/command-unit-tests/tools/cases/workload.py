"""Work queue cases the samples miss, plus tidying: every queue is named ut_..., emptied first and at the end."""
from helpers_platform import (prefix_names, fix_sample_checks, declare, names_used, move_last_case_to, renumber)

Q = "ooa.workload/"


def _empty(f, queues, desc):
    for q in queues:
        f.act(Q + "delete_items", desc=desc + " " + q, queue=q, status="any", force=True)


def add(ua):
    prefix_names(ua, Q)
    fix_sample_checks(ua)
    declare(ua, "it", "Dictionary")
    for v, t in (("wq_id", "String"), ("wq_item", "Dictionary"), ("wq_text", "String"), ("wq_n", "Number"),
                 ("wq_found", "Boolean"), ("wq_counts", "Dictionary"), ("wq_table", "Table"), ("wq_tags", "List"),
                 ("wq_status", "String")):
        declare(ua, v, t)
    f = ua.f

    with ua.case(Q + "complete", "mark an item completed with result fields",
                 "Complete work item ends an item that was taken with Get next item and keeps result fields with it. "
                 "The check reads the item back: status completed and the result field paid.") :
        f.act(Q + "add_item", queue="ut_complete", data={"invoice": "INV-100"})
        f.act(Q + "get_next", queue="ut_complete", out={"item_id": "wq_id"})
        f.act(Q + "complete", queue="ut_complete", item_id="$wq_id$", result={"paid": True})
        f.act(Q + "get_item", queue="ut_complete", item_id="$wq_id$", out={"item": "wq_item"})
        f.expr("wq_status", "$wq_item.status")
        ua.check_eq("wq_status", "completed")
        f.expr("wq_found", "$wq_item.result.paid")
        ua.check_eq("wq_found", True)

    with ua.case(Q + "counts", "count items by status",
                 "Queue counts gives the number of items per status. Three items are added and one is completed: "
                 "two new, one completed, three in all."):
        f.act(Q + "add_items", queue="ut_counts", table={"columns": ["n"], "rows": [[1], [2], [3]]})
        f.act(Q + "get_next", queue="ut_counts", out={"item_id": "wq_id"})
        f.act(Q + "complete", queue="ut_counts", item_id="$wq_id$")
        f.act(Q + "counts", queue="ut_counts", out={"counts": "wq_counts"})
        f.expr("wq_n", "$wq_counts.new")
        ua.check("eq", "$wq_n$", 2, what="new items")
        f.expr("wq_n", "$wq_counts.completed")
        ua.check("eq", "$wq_n$", 1, what="completed items")
        f.expr("wq_n", "$wq_counts.total")
        ua.check("eq", "$wq_n$", 3, what="all items")

    with ua.case(Q + "defer", "put an item aside until a later date",
                 "Defer work item puts a taken item aside until a date (UTC). Until then Get next item does not "
                 "offer it, and the item shows as deferred."):
        f.act(Q + "add_item", queue="ut_defer", data={"supplier": "Contoso"})
        f.act(Q + "get_next", queue="ut_defer", out={"item_id": "wq_id"})
        f.act(Q + "defer", queue="ut_defer", item_id="$wq_id$", until="2099-01-01T00:00:00")
        f.act(Q + "get_next", queue="ut_defer", out={"found": "wq_found"})
        ua.check_eq("wq_found", False)
        f.act(Q + "get_item", queue="ut_defer", item_id="$wq_id$", out={"item": "wq_item"})
        f.expr("wq_status", "$wq_item.status")
        ua.check_eq("wq_status", "deferred")

    with ua.case(Q + "get_item", "read fields, reference, tags and status",
                 "Get work item reads one item. An item added with a reference and a tag comes back new, with no "
                 "attempts yet, its fields, reference and tag."):
        f.act(Q + "add_item", queue="ut_get", data={"amount": 42.5}, reference="PO-42", tags=["eu"],
              out={"item_id": "wq_id"})
        f.act(Q + "get_item", queue="ut_get", item_id="$wq_id$", out={"item": "wq_item"})
        f.expr("wq_status", "$wq_item.status")
        ua.check_eq("wq_status", "new")
        f.expr("wq_text", "$wq_item.reference")
        ua.check_eq("wq_text", "PO-42")
        f.expr("wq_n", "$wq_item.data.amount")
        ua.check("eq", "$wq_n$", 42.5, what="the amount field")
        f.expr("wq_tags", "$wq_item.tags")
        ua.check("contains", "$wq_tags$", "eu", what="the tags")

    with ua.case(Q + "list_items", "only the completed items, as a table",
                 "List work items gives a queue's items as a table, here only the completed ones: one of two."):
        f.act(Q + "add_items", queue="ut_list", table={"columns": ["n"], "rows": [[1], [2]]})
        f.act(Q + "get_next", queue="ut_list", out={"item_id": "wq_id"})
        f.act(Q + "complete", queue="ut_list", item_id="$wq_id$")
        f.act(Q + "list_items", queue="ut_list", status="completed", out={"table": "wq_table"})
        f.expr("wq_n", "len($wq_table)")
        ua.check("eq", "$wq_n$", 1, what="rows in the table")

    with ua.case(Q + "untag_item", "remove a tag, ignoring case",
                 "Remove tags takes tags off an item (ignoring case) and gives back the tags left."):
        f.act(Q + "add_item", queue="ut_untag", data={"n": 1}, tags=["urgent", "eu"], out={"item_id": "wq_id"})
        f.act(Q + "untag_item", queue="ut_untag", item_id="$wq_id$", tags=["URGENT"], out={"tags": "wq_tags"})
        ua.check_eq("wq_tags", ["eu"])

    with ua.case(Q + "update_item", "change fields and put the item on hold",
                 "Update work item merges new fields into an item and here puts it on hold indefinitely; the new "
                 "status is deferred and both the old and the new field are there."):
        f.act(Q + "add_item", queue="ut_update", data={"amount": 10}, out={"item_id": "wq_id"})
        f.act(Q + "update_item", queue="ut_update", item_id="$wq_id$", data={"note": "checked"}, status="on_hold",
              out={"status": "wq_status"})
        ua.check_eq("wq_status", "deferred")
        f.act(Q + "get_item", queue="ut_update", item_id="$wq_id$", out={"item": "wq_item"})
        f.expr("wq_text", "$wq_item.data.note")
        ua.check_eq("wq_text", "checked")
        f.expr("wq_n", "$wq_item.data.amount")
        ua.check("eq", "$wq_n$", 10, what="the kept field")

    queues = names_used(ua, Q, "queue")
    with ua.case(Q + "delete_items", "empty every ut_ queue before the cases",
                 "Delete work items with status any and force on empties a queue. It runs first, so items left by an "
                 "earlier run do not change the results; the check counts what is left in the last queue."):
        _empty(f, queues, "Empty the queue")
        f.act(Q + "counts", queue=queues[-1], out={"counts": "wq_counts"})
        f.expr("wq_n", "$wq_counts.total")
        ua.check("eq", "$wq_n$", 0, what="items left")
    move_last_case_to(ua, 1)

    with ua.case(Q + "delete_items", "tidy up: empty every ut_ queue",
                 "At the end every queue the agent used is emptied again (force on, any status)."):
        _empty(f, queues, "Empty the queue")
    renumber(ua)
