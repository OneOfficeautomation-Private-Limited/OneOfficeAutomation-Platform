"""Business-application toolkits: one unit-test agent each, all against the local stand-in."""

_T = [("salesforce", "Salesforce"), ("servicenow", "ServiceNow"), ("jira", "Jira"), ("workday", "Workday"),
      ("okta", "Okta"), ("asana", "Asana"), ("box", "Box"), ("docusign", "DocuSign"), ("zendesk", "Zendesk"),
      ("slack", "Slack"), ("github", "GitHub"), ("azure_devops", "Azure DevOps")]

GROUPS = {
    gid: {"id": "ut_" + gid, "name": "UT " + name, "toolkits": ["ooa." + gid], "needs": "standin",
          "about": f"Unit tests of every {name} command against the local stand-in (made-up records, made-up token "
                   f"from the vault): each case shows the command in use and checks its result."}
    for gid, name in _T
}
