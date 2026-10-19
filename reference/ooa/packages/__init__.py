"""First-party packages, signed with the first-party publisher key at build time."""
from __future__ import annotations

from ..runner.core_package import CorePackage
from ..sdk.registry import PackageRegistry, PublisherKey, sign_package
from .ai_pkgs import AgentsPackage, GenAIPackage
from .data import DataTablePackage, JsonPackage, StringPackage
from .office import ExcelPackage, FilePackage, RestPackage

FIRST_PARTY = [CorePackage, StringPackage, JsonPackage, DataTablePackage, FilePackage, ExcelPackage, RestPackage,
               GenAIPackage, AgentsPackage]


def builtin_registry(allowed_publishers: set[str] | None = None) -> PackageRegistry:
    key = PublisherKey.generate("oneofficeautomation")
    reg = PackageRegistry(allowed_publishers=allowed_publishers)
    reg.trust(key)
    for cls in FIRST_PARTY:
        reg.install(cls, sign_package(cls, key))
    return reg
