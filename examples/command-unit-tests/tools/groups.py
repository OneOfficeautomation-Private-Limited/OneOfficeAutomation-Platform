"""The command groups: one unit-test agent each.

Each helper owns a file groups_<name>.py with GROUPS = {group id: {...}}; they are merged here. A group:
  id       agent id (ut_...)              name     agent name ("UT String"), also its file name
  toolkits toolkit ids                    about    what the agent tests (its first comment)
  only     optional command-id prefixes to keep (to split a big toolkit), skip  optional prefixes to leave out
  needs    optional: "local" (default), "office", "desktop" (drives the screen), "standin", "database", "commandcenter"
"""
import glob
import importlib.util
import os

GROUPS = {
    "string": {"id": "ut_string", "name": "UT String", "toolkits": ["ooa.string"], "needs": "local",
               "about": "Unit tests of every String command: each case shows the command in use and checks its result."},
}

_here = os.path.dirname(os.path.abspath(__file__))
for _path in sorted(glob.glob(os.path.join(_here, "groups_*.py"))):
    _spec = importlib.util.spec_from_file_location(os.path.basename(_path)[:-3], _path)
    _mod = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_mod)
    for _k, _v in _mod.GROUPS.items():
        if _k in GROUPS:
            raise ValueError(f"group {_k} is defined twice ({_path})")
        GROUPS[_k] = _v
