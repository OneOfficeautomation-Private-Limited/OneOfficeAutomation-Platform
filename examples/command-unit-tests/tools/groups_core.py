"""Command groups for the core and data toolkits (Core, Boolean, Number, List, Dictionary, Data Table, Date and time,
JSON, XML, CSV, Convert, Currency, Hash and encrypt, Fuzzy match, Business rules, Run context, Log to file, PGP, Scripts,
Database). Merged into GROUPS by groups.py."""


def _g(gid, name, toolkit, about, needs="local"):
    return {"id": "ut_" + gid, "name": name, "toolkits": [toolkit], "needs": needs,
            "about": about + " Each case shows the command in use and checks its result."}


GROUPS = {
    "core": _g("core", "UT Core", "core",
               "Unit tests of the Core commands: control flow (If, Loop, Switch, Try, Go to, State machine, Parallel), "
               "variables, delays, waits, logs, errors and running other agents."),
    "boolean": _g("boolean", "UT Boolean", "ooa.boolean", "Unit tests of every Boolean command."),
    "number": _g("number", "UT Number", "ooa.number", "Unit tests of every Number command."),
    "list": _g("list", "UT List", "ooa.list", "Unit tests of every List command."),
    "dictionary": _g("dictionary", "UT Dictionary", "ooa.dictionary", "Unit tests of every Dictionary command."),
    "datatable": _g("datatable", "UT Data Table", "ooa.datatable", "Unit tests of every Data Table command."),
    "datetime": _g("datetime", "UT Date and time", "ooa.datetime", "Unit tests of every Date and time command."),
    "json": _g("json", "UT JSON", "ooa.json", "Unit tests of every JSON command."),
    "xml": _g("xml", "UT XML", "ooa.xml", "Unit tests of every XML command."),
    "csv": _g("csv", "UT CSV TXT", "ooa.csv", "Unit tests of every CSV/TXT command."),
    "convert": _g("convert", "UT Convert", "ooa.convert", "Unit tests of the Convert command."),
    "currency": _g("currency", "UT Currency", "ooa.currency", "Unit tests of every Currency command."),
    "crypto": _g("crypto", "UT Hash and encrypt", "ooa.crypto", "Unit tests of every Hash and encrypt command."),
    "fuzzy": _g("fuzzy", "UT Fuzzy Match", "ooa.fuzzy", "Unit tests of every Fuzzy Match command."),
    "rules": _g("rules", "UT Business rules", "ooa.rules", "Unit tests of the Business rules command."),
    "run_context": _g("run_context", "UT Run context", "ooa.run_context", "Unit tests of every Run context command."),
    "log": _g("log", "UT Log to file", "ooa.log", "Unit tests of every Log to file command."),
    "pgp": _g("pgp", "UT PGP", "ooa.pgp", "Unit tests of every PGP command."),
    "script": _g("script", "UT Scripts", "ooa.script",
                 "Unit tests of every Scripts command: Python, PowerShell, JavaScript and VBScript, one-off and in sessions."),
    "database": _g("database", "UT Database", "ooa.database",
                   "Unit tests of every Database command against the SQL Server test database ooa_unit_test: queries, "
                   "changes in transactions that are rolled back or cleaned up, and stored procedures.", needs="database"),
}
