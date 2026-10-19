import json
from pathlib import Path

import pytest

from ooa.packages import builtin_registry
from ooa.vault import InMemoryVault

ROOT = Path(__file__).resolve().parents[2]
REFUND = ROOT / "examples" / "refund"


@pytest.fixture(scope="session")
def registry():
    return builtin_registry()


@pytest.fixture
def vault():
    v = InMemoryVault()
    v.put("test", "app", "password", "s3cr3t")
    return v


def load(path: Path) -> dict:
    return json.loads(Path(path).read_text())
