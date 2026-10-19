"""Credential vault adapter (architecture s8). Bots never hold plain secrets in
Flow JSON: Credential inputs are references such as
{"$credential": "finance-locker/erp-bot/password"}, resolved just-in-time on
the agent into SecureString values. OpenBao, CyberArk, HashiCorp, Azure Key Vault
and AWS Secrets Manager are further adapters behind the same interface."""
from __future__ import annotations

import os
from typing import Protocol

from .sdk.errors import OoaError, PolicyDenied
from .sdk.types import SecureString


class VaultAdapter(Protocol):
    def get_secret(self, ref: str, *, principal: str | None = None) -> SecureString: ...


class InMemoryVault:
    """Lockers -> credentials -> attributes, with consumer lists per locker."""

    def __init__(self):
        self._data: dict[str, dict[str, dict[str, str]]] = {}
        self._consumers: dict[str, set[str]] = {}

    def put(self, locker: str, credential: str, attribute: str, value: str, consumers: set[str] | None = None):
        self._data.setdefault(locker, {}).setdefault(credential, {})[attribute] = value
        if consumers is not None:
            self._consumers[locker] = set(consumers)

    def get_secret(self, ref: str, *, principal: str | None = None) -> SecureString:
        try:
            locker, cred, attr = ref.split("/")
        except ValueError:
            raise OoaError(f"Bad credential reference '{ref}'", code="VAULT.BAD_REF",
                           suggested_fix="Use locker/credential/attribute.") from None
        allowed = self._consumers.get(locker)
        if allowed is not None and principal not in allowed:
            raise PolicyDenied(f"'{principal}' is not a consumer of locker '{locker}'", code="VAULT.DENIED",
                               suggested_fix="Ask the locker owner to add you or your role as a consumer.")
        try:
            return SecureString(self._data[locker][cred][attr], ref=ref)
        except KeyError:
            raise OoaError(f"Credential '{ref}' not found", code="VAULT.NOT_FOUND") from None


class EnvVault:
    """Development adapter: locker/cred/attr -> env var OOA_SECRET_LOCKER_CRED_ATTR."""

    def get_secret(self, ref: str, *, principal: str | None = None) -> SecureString:
        key = "OOA_SECRET_" + ref.replace("/", "_").replace("-", "_").upper()
        if key not in os.environ:
            raise OoaError(f"Credential '{ref}' not found (expected env var {key})", code="VAULT.NOT_FOUND")
        return SecureString(os.environ[key], ref=ref)
