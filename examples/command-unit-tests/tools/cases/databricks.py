"""Hand-written cases for the Databricks commands (stand-in workspace at $standin_url$/cloud-dbx, made-up token)."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.databricks/"
WS = "$standin_url$/cloud-dbx"


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("db_table", "Table"), ("db_rows", "Number"), ("db_total", "Number"), ("db_id", "String"), ("db_status", "String"),
                 ("db_run", "String"), ("db_life", "String"), ("db_result", "String"), ("db_info", "Dictionary"),
                 ("db_count", "Number"), ("db_state", "String"), ("db_path", "String"), ("db_size", "Number"),
                 ("db_text", "String"), ("db_closed", "Boolean"), ("db_logs", "String")):
        ua.var(n, t)

    with ua.case(T + "connect", "workspace and token kept for later steps",
                 "Connect keeps the workspace address and the access token (from the vault) under a session, so later "
                 "steps need neither. Checked: a statement runs without its own address or token.") as f:
        f.act(T + "connect", desc="Connect to the stand-in workspace", workspace_url=WS, access_token=cred("databricks/token"))
        f.act(T + "execute_sql", desc="A first statement", warehouse_id="UT warehouse", sql="SELECT true AS ok",
              out={"status": "db_status"})
        ua.check_eq("db_status", "SUCCEEDED")

    with ua.case(T + "execute_sql", "rows with a parameter",
                 "Run SQL statement runs SQL on a warehouse (by name or id) with :name parameters and gives the rows as "
                 "a table, following the result chunks. Checked: two invoices of at least 100, typed columns.") as f:
        f.act(T + "execute_sql", desc="Invoices of at least 100", warehouse_id="UT warehouse", catalog="main", schema="finance",
              sql="SELECT id, customer, amount FROM ut_invoices WHERE amount >= :min_amount", parameters={"min_amount": 100},
              wait_seconds=30, max_rows=100, out={"table": "db_table", "row_count": "db_rows", "statement_id": "db_id"})
        ua.check_eq("db_rows", 2)
        value(ua, "$db_table.rows[1][1]", "eq", "Initech", "the second customer")
        value(ua, "$db_table.rows[1][2]", "eq", 310.25, "its amount as a number")

    with ua.case(T + "fetch_results", "all rows of a finished statement",
                 "Get statement results reads the rows of a finished statement, chunk by chunk. Checked: all three rows "
                 "and the total.") as f:
        f.act(T + "execute_sql", desc="Run the statement", warehouse_id="0123456789abcdef", sql="SELECT * FROM ut_invoices",
              out={"statement_id": "db_id"})
        f.act(T + "fetch_results", desc="Read its rows", statement_id="$db_id$", max_rows=1000,
              out={"table": "db_table", "row_count": "db_rows", "total_rows": "db_total"})
        ua.check_eq("db_rows", 3)
        ua.check_eq("db_total", 3)
        value(ua, "$db_table.rows[2][0]", "eq", 3, "the last id")

    with ua.case(T + "get_statement_status", "a statement left running",
                 "Get statement status tells whether a statement that was left running has finished. A slow statement "
                 "is started without waiting. Checked: RUNNING.") as f:
        f.act(T + "execute_sql", desc="Start a slow statement, do not wait", warehouse_id="UT warehouse",
              sql="SELECT SLOW count(*) FROM ut_events", wait_seconds=0, out={"statement_id": "db_id", "status": "db_status"})
        ua.check_eq("db_status", "RUNNING")
        f.act(T + "get_statement_status", desc="Ask how it is going", statement_id="$db_id$", out={"status": "db_status"})
        ua.check_eq("db_status", "RUNNING")

    with ua.case(T + "cancel_statement", "stop a slow statement",
                 "Cancel statement stops a statement that is still running. Checked: its status is then CANCELED.") as f:
        f.act(T + "execute_sql", desc="Start a slow statement", warehouse_id="UT warehouse", sql="SELECT SLOW * FROM ut_events",
              wait_seconds=0, out={"statement_id": "db_id"})
        f.act(T + "cancel_statement", desc="Cancel it", statement_id="$db_id$")
        f.act(T + "get_statement_status", desc="Read its status", statement_id="$db_id$", out={"status": "db_status"})
        ua.check_eq("db_status", "CANCELED")

    with ua.case(T + "list_jobs", "jobs by name",
                 "List jobs lists the workspace's jobs, optionally only those with a name. Checked: the nightly load job.") as f:
        f.act(T + "list_jobs", desc="Find the nightly load job", name="UT nightly load", max_results=50,
              out={"table": "db_table", "count": "db_count"})
        ua.check_eq("db_count", 1)
        value(ua, "$db_table.rows[0][0]", "eq", "101", "the job id")

    with ua.case(T + "run_job", "run by name with a parameter and wait",
                 "Run job now starts a job (by name or id) with parameters, and can wait for it to end. Checked: "
                 "TERMINATED and SUCCESS.") as f:
        f.act(T + "run_job", desc="Run the nightly load", job_id="UT nightly load", job_parameters={"run_date": "2026-11-20"},
              wait=True, timeout_seconds=60, out={"run_id": "db_run", "life_cycle_state": "db_life", "result_state": "db_result"})
        ua.check_eq("db_life", "TERMINATED")
        ua.check_eq("db_result", "SUCCESS")

    with ua.case(T + "get_run", "progress of a long run",
                 "Get job run reads a run's progress and outcome. A long job is started without waiting. Checked: "
                 "RUNNING and the run's details.") as f:
        f.act(T + "run_job", desc="Start the long job", job_id="102", wait=False, out={"run_id": "db_run"})
        f.act(T + "get_run", desc="Read the run", run_id="$db_run$", out={"life_cycle_state": "db_life", "run": "db_info"})
        ua.check_eq("db_life", "RUNNING")
        value(ua, "$db_info.run_id", "eq", "$db_run$", "the run id in the details")
        f.act(T + "cancel_run", desc="Tidy up", run_id="$db_run$")

    with ua.case(T + "get_run_output", "notebook result and logs",
                 "Get job run output reads what a finished run produced: the notebook's exit value and the logs. "
                 "Checked: the result names the run date.") as f:
        f.act(T + "run_job", desc="Run the nightly load", job_id="101", job_parameters={"run_date": "2026-11-21"}, wait=True,
              out={"run_id": "db_run"})
        f.act(T + "get_run_output", desc="Read its output", run_id="$db_run$", out={"result": "db_text", "logs": "db_logs"})
        ua.check_eq("db_text", "loaded 42 rows for 2026-11-21")
        ua.check("contains", "$db_logs$", "UT load done", what="the logs")

    with ua.case(T + "cancel_run", "stop a long run",
                 "Cancel job run stops a run that is queued or running. Checked: the run then ends CANCELED.") as f:
        f.act(T + "run_job", desc="Start the long job", job_id="UT long job", out={"run_id": "db_run"})
        f.act(T + "cancel_run", desc="Cancel it", run_id="$db_run$")
        f.act(T + "get_run", desc="Read the run", run_id="$db_run$", out={"result_state": "db_result"})
        ua.check_eq("db_result", "CANCELED")

    with ua.case(T + "list_clusters", "the workspace's clusters",
                 "List clusters gives the clusters and their state. Checked: the shared cluster is listed.") as f:
        f.act(T + "list_clusters", desc="List the clusters", out={"table": "db_table", "count": "db_count"})
        ua.check("ge", "$db_count$", 1, what="cluster count")
        value(ua, "$db_table.rows[0][1]", "eq", "UT shared cluster", "the cluster name")

    with ua.case(T + "start_cluster", "start by name and wait",
                 "Start cluster starts a stopped cluster (by name or id) and can wait until it runs. Checked: RUNNING.") as f:
        f.act(T + "terminate_cluster", desc="Make sure it is stopped", cluster_id="1123-090000-ut1")
        f.act(T + "start_cluster", desc="Start the shared cluster", cluster_id="UT shared cluster", wait=True, timeout_seconds=60,
              out={"state": "db_state"})
        ua.check_eq("db_state", "RUNNING")

    with ua.case(T + "terminate_cluster", "stop and wait",
                 "Stop cluster stops a running cluster (its settings are kept) and can wait until it has stopped. "
                 "Checked: TERMINATED.") as f:
        f.act(T + "start_cluster", desc="Make sure it runs", cluster_id="1123-090000-ut1")
        f.act(T + "terminate_cluster", desc="Stop it and wait", cluster_id="UT shared cluster", wait=True, timeout_seconds=60,
              out={"state": "db_state"})
        ua.check_eq("db_state", "TERMINATED")

    with ua.case(T + "upload_file", "a CSV into a volume",
                 "Upload file copies a local file into a Unity Catalog volume, the workspace or DBFS. Checked: the "
                 "remote path it reports.") as f:
        f.act("ooa.file/write_text", desc="A file to upload", path="dbx/report.csv", text="id,amount\n1,120.50\n")
        f.act(T + "upload_file", desc="Upload it to a volume", local_path="dbx/report.csv",
              remote_path="/Volumes/main/finance/inbox/report.csv", overwrite=True, out={"remote_path": "db_path"})
        ua.check_eq("db_path", "/Volumes/main/finance/inbox/report.csv")

    with ua.case(T + "download_file", "a file back from DBFS",
                 "Download file saves a file from a volume, the workspace or DBFS on this machine. A file is uploaded "
                 "to DBFS first. Checked: size and content.") as f:
        f.act("ooa.file/write_text", desc="A file to upload", path="dbx/notes.txt", text="UT notes")
        f.act(T + "upload_file", desc="Upload it to DBFS", local_path="dbx/notes.txt", remote_path="dbfs:/FileStore/ut/notes.txt")
        f.act(T + "download_file", desc="Download it again", remote_path="dbfs:/FileStore/ut/notes.txt", save_as="dbx/back/notes.txt",
              overwrite=True, out={"size": "db_size"})
        ua.check_eq("db_size", 8)
        f.act("ooa.file/read_text", desc="Read the copy", path="dbx/back/notes.txt", out={"text": "db_text"})
        ua.check_eq("db_text", "UT notes")

    with ua.case(T + "delete_file", "a file in a volume",
                 "Delete file deletes a file from a volume, the workspace or DBFS. Checked: downloading it afterwards "
                 "fails as not found.") as f:
        f.act("ooa.file/write_text", desc="A file to upload", path="dbx/old.csv", text="old")
        f.act(T + "upload_file", desc="Upload it", local_path="dbx/old.csv", remote_path="/Volumes/main/finance/inbox/old.csv")
        f.act(T + "delete_file", desc="Delete it", remote_path="/Volumes/main/finance/inbox/old.csv")
        ua.expect_error("DATABRICKS.NOT_FOUND", lambda ff: ff.act(T + "download_file", desc="It is gone",
                                                                  remote_path="/Volumes/main/finance/inbox/old.csv", save_as="dbx/gone.csv"))

    with ua.case(T + "disconnect", "close the session",
                 "Disconnect forgets the open session; later steps then need their own address and token. Checked: it "
                 "reports the session closed.") as f:
        f.act(T + "disconnect", desc="Close the session", out={"closed": "db_closed"})
        ua.check_eq("db_closed", True)
