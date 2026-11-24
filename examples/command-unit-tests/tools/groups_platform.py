"""Platform toolkits (work queues, locks, jobs, shared values, vault, documents, forms, AI) and the item types of
OneAgentStudio's Create list (one sample item and one "UT Item ..." agent each)."""

GROUPS = {
    # ---------------- Part A: platform toolkits ----------------
    "workload": {"id": "ut_workload", "name": "UT Work queues", "toolkits": ["ooa.workload"], "needs": "local",
                 "about": "Unit tests of every Work queue command on queues named ut_...: add, take, finish, fail, defer, "
                          "tag, copy, move and delete items, the Queue has items condition and the Each work item loop. "
                          "The queues are queue files in the workspace; every queue is emptied at the end."},
    "locks": {"id": "ut_locks", "name": "UT Environment locks", "toolkits": ["ooa.locks"], "needs": "local",
              "about": "Unit tests of every Environment lock command on locks named ut_...: take, check and give back a "
                       "lock, and the Lock is held condition. Every lock is given back at the end."},
    "jobs": {"id": "ut_jobs", "name": "UT Jobs and alerts", "toolkits": ["ooa.jobs"], "needs": "commandcenter",
             "about": "Unit tests of every Jobs and alerts command: list runs, read this run, stop a run that has "
                      "already ended, and raise a warning alert. Needs One CommandCenter."},
    "longrunning": {"id": "ut_longrunning", "name": "UT Long-running agents", "toolkits": ["ooa.longrunning"],
                    "needs": "commandcenter",
                    "about": "Unit tests of every Long-running agent command: callback addresses, Task inbox tasks, "
                             "suspending for a short time and waiting for a job, a work item, a task or a callback "
                             "(with short time-outs that carry on). Needs One CommandCenter."},
    "insights": {"id": "ut_insights", "name": "UT Insights", "toolkits": ["ooa.insights"], "needs": "local",
                 "about": "Unit tests of every Insights command: record values for a category ut_..., open and close "
                          "transactions and read the values back."},
    "data": {"id": "ut_data", "name": "UT Data store", "toolkits": ["ooa.data"], "needs": "local",
             "about": "Unit tests of every Data store command: records of the entity ut_customers and files in the "
                      "bucket ut_files, each removed at the end. Locally they are files in the workspace; in One CommandCenter the "
                      "entity and the bucket must exist."},
    "global_values": {"id": "ut_global_values", "name": "UT Shared values", "toolkits": ["ooa.global_values"],
                      "needs": "local",
                      "about": "Unit tests of every Shared value command on values named ut_...: read one, read all, "
                               "change one and the Shared value is defined condition. Locally the values are the "
                               "workspace file global-values.json; in One CommandCenter the shared values listed in "
                               "the report must exist."},
    "vault": {"id": "ut_vault", "name": "UT Credential vault", "toolkits": ["ooa.vault"], "needs": "commandcenter",
              "about": "Unit tests of every Credential vault command on the credential unit_tests/ut_vault: save a new "
                       "value with an expiry date, read the expiry and the Credential has expired condition. Needs "
                       "One CommandCenter (locally the vault is read-only)."},
    "credential_manager": {"id": "ut_credential_manager", "name": "UT Credential Manager",
                           "toolkits": ["ooa.credential_manager"], "needs": "local",
                           "about": "Unit tests of every Windows Credential Manager command on the made-up target "
                                    "ooa-unit-test: write, read, list, the Credential exists condition and delete. "
                                    "The target is deleted at the end."},
    "documents": {"id": "ut_documents", "name": "UT Document Automation", "toolkits": ["ooa.documents"],
                  "needs": "commandcenter",
                  "about": "Unit tests of every Document Automation command: send a made-up invoice to the live "
                           "process ut_invoices, wait for it, read, correct, list, mark collected and download the "
                           "results. Needs One CommandCenter."},
    "forms": {"id": "ut_forms", "name": "UT Interactive forms", "toolkits": ["ooa.forms"], "needs": "desktop",
              "about": "Unit tests of every Interactive form command: the form UT Item Form.form.json is shown without "
                       "waiting, every change is made by the agent itself and read back, then the form is closed. It "
                       "opens a window on the screen."},
    "coded": {"id": "ut_coded", "name": "UT Coded agents", "toolkits": ["ooa.coded"], "needs": "local",
              "about": "Unit tests of Run Python agent: main(inputs, ooa) of a small module calls toolkit commands "
                       "through ooa.call() and returns outputs. Needs Python on the runner."},
    "ai_skills": {"id": "ut_ai_skills", "name": "UT Prompt Skills", "toolkits": ["ooa.ai_skills"], "needs": "standin",
                  "about": "Unit tests of every Prompt Skill command against the stand-in model: list the skills and "
                           "run a text skill and a JSON skill."},
    "ai_agent": {"id": "ut_ai_agent", "name": "UT AI Agents", "toolkits": ["ooa.ai_agent"], "needs": "commandcenter",
                 "about": "Unit tests of the AI agent command against the stand-in model: the model calls the agent "
                          "UT Item API agent as its tool and answers. Needs the agent repository (Studio or One "
                          "CommandCenter); ooa run alone has none."},

    # ---------------- Part B: item types ----------------
    "item_ai_agent": {"id": "ut_item_ai_agent_test", "name": "UT Item AI agent", "toolkits": [], "needs": "commandcenter",
                      "about": "Runs the sample AI agent UT AI agent (agents/items/ai-agent) as a child agent and checks "
                               "its answer, status and output fields (stand-in model)."},
    "item_workflow": {"id": "ut_item_workflow_test", "name": "UT Item Workflow", "toolkits": [], "needs": "commandcenter",
                      "about": "Starts the sample workflow UT Workflow.process.json through One CommandCenter's "
                               "workflow API and reads its run back."},
    "item_form": {"id": "ut_item_form_test", "name": "UT Item Form", "toolkits": [], "needs": "desktop",
                  "about": "Shows the sample form UT Form.form.json with values filled in, checks its answers and "
                           "closes it (opens a window)."},
    "item_document_type": {"id": "ut_item_document_type_test", "name": "UT Item Document type", "toolkits": [],
                           "needs": "standin",
                           "about": "Reads a made-up invoice with the fields of the sample document type "
                                    "UT Invoice.doc.json, the way the extraction agent Studio makes from it does "
                                    "(stand-in model), and checks each field."},
    "item_object_repository": {"id": "ut_item_object_repository_test", "name": "UT Item Object repository",
                               "toolkits": [], "needs": "desktop",
                               "about": "Opens the stand-in's test page in a browser and fills it in through the "
                                        "objects of the sample repository UT Shop.objects.json."},
    "item_prompt_skill": {"id": "ut_item_prompt_skill_test", "name": "UT Item Prompt Skill", "toolkits": [],
                          "needs": "standin",
                          "about": "Runs the sample Prompt Skill ut_classify_ticket (ai-skills) against the stand-in "
                                   "model and checks its JSON answer."},
    "item_api_agent": {"id": "ut_item_api_agent_test", "name": "UT Item API agent", "toolkits": [], "needs": "local",
                       "about": "Runs the sample API agent UT API agent (no desktop steps) as a child agent and checks "
                                "its outputs; locally it is also run on its own."},
    "item_python": {"id": "ut_item_python_test", "name": "UT Item Python script", "toolkits": [], "needs": "local",
                    "about": "Calls the sample script UT invoice tools.py as a script (arguments and standard input) "
                             "and as functions, and checks the JSON it gives back."},
    "item_powershell": {"id": "ut_item_powershell_test", "name": "UT Item PowerShell script", "toolkits": [],
                        "needs": "local",
                        "about": "Calls the sample script UT invoice tools.ps1 with parameters and standard input and "
                                 "checks the JSON it gives back."},
}
