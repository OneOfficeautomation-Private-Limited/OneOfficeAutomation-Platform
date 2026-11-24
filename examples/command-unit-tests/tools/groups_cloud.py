"""Cloud, AI and integration command groups: one unit-test agent per toolkit, all against the stand-in."""

_ABOUT = ("Unit tests of every {t} command: each case shows the command in use against the local stand-in "
          "(made-up keys from the vault, never a real account) and checks its result.")


def _g(gid, toolkit, name, needs="standin", about=None):
    return {"id": "ut_" + gid, "name": "UT " + name, "toolkits": [toolkit], "needs": needs,
            "about": about or _ABOUT.format(t=name)}


GROUPS = {
    "aws_ec2": _g("aws_ec2", "ooa.aws_ec2", "AWS EC2"),
    "azure_vm": _g("azure_vm", "ooa.azure_vm", "Azure VMs"),
    "aws_ai": _g("aws_ai", "ooa.aws_ai", "AWS AI services"),
    "azure_ai": _g("azure_ai", "ooa.azure_ai", "Azure AI services"),
    "google_ai": _g("google_ai", "ooa.google_ai", "Google Cloud AI"),
    "databricks": _g("databricks", "ooa.databricks", "Databricks"),
    "snowflake": _g("snowflake", "ooa.snowflake", "Snowflake"),
    "kafka": _g("kafka", "ooa.kafka", "Kafka"),
    "rest": _g("rest", "ooa.rest", "REST Web Service"),
    "soap": _g("soap", "ooa.soap", "SOAP Web Service"),
    "sap_bapi": _g("sap_bapi", "ooa.sap_bapi", "SAP BAPI", needs="sap",
                   about="Unit tests of every SAP BAPI command. They need the SAP Java Connector (JCo) library and an SAP "
                         "test system, so this agent is built and validated only; each case shows the command in use and "
                         "checks its result."),
    "genai": _g("genai", "ooa.genai", "Generative AI",
                about="Unit tests of every Generative AI command: each case uses the stand-in's scripted model "
                      "(an OpenAI-compatible provider on the stand-in, made-up key from the vault) and checks the result."),
    "knowledge": _g("knowledge", "ooa.knowledge", "Knowledge Q and A"),   # no & in repository names
    "mcp": _g("mcp", "ooa.mcp", "MCP tools"),
    "connector": _g("connector", "ooa.connector", "Custom Connectors"),
    "comms": _g("comms", "ooa.comms", "Communications mining", needs="local"),
    "mining": _g("mining", "ooa.mining", "Process mining", needs="local"),
}
