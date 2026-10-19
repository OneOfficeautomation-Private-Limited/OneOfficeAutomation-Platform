"""Package registry: signature verification (FR-PKG-003), semantic versions with
tenant default and pinned ranges (FR-PKG-004), publisher allow-list (FR-PKG-007)
and a usage index (FR-PKG-006).

Signing model: the signed payload is the canonical manifest plus a SHA-256 of
the package source. Ed25519 stands in for Sigstore/cosign in the reference SDK;
the Java SDK signs JARs with cosign + jarsigner as described in architecture s5."""
from __future__ import annotations

import base64
import hashlib
import inspect
import json
import re
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives import serialization

from .errors import PackageError
from .package import ActionSpec, build_manifest


def canonical(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), default=str).encode()


def package_digest(cls) -> str:
    manifest = build_manifest(cls)
    try:
        src = inspect.getsource(inspect.getmodule(cls)).encode()
    except (OSError, TypeError):
        src = b""
    return hashlib.sha256(canonical(manifest) + hashlib.sha256(src).digest()).hexdigest()


@dataclass
class PublisherKey:
    publisher: str
    private_key: Ed25519PrivateKey | None
    public_key: Ed25519PublicKey

    @classmethod
    def generate(cls, publisher: str) -> "PublisherKey":
        k = Ed25519PrivateKey.generate()
        return cls(publisher, k, k.public_key())

    def public_pem(self) -> str:
        return self.public_key.public_bytes(serialization.Encoding.PEM,
                                            serialization.PublicFormat.SubjectPublicKeyInfo).decode()


def sign_package(cls, key: PublisherKey) -> dict:
    if key.private_key is None:
        raise PackageError("Cannot sign without a private key")
    digest = package_digest(cls)
    sig = key.private_key.sign(digest.encode())
    return {"publisher": key.publisher, "digest": digest, "signature": base64.b64encode(sig).decode()}


# ---------- semantic versions ----------
def _v(s: str) -> tuple[int, int, int]:
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", s.strip())
    if not m:
        raise PackageError(f"Invalid version '{s}'")
    return tuple(int(x) for x in m.groups())  # type: ignore[return-value]


def satisfies(version: str, rng: str) -> bool:
    v = _v(version)
    rng = rng.strip()
    if rng in ("*", "", "latest"):
        return True
    if rng.startswith("^"):
        b = _v(rng[1:])
        upper = (b[0] + 1, 0, 0) if b[0] > 0 else (0, b[1] + 1, 0)
        return b <= v < upper
    if rng.startswith("~"):
        b = _v(rng[1:])
        return b <= v < (b[0], b[1] + 1, 0)
    if rng.startswith(">="):
        return v >= _v(rng[2:])
    return v == _v(rng)


@dataclass
class LoadedPackage:
    cls: type
    manifest: dict
    instance: object
    signature: dict

    @property
    def id(self) -> str:
        return self.manifest["id"]

    @property
    def version(self) -> str:
        return self.manifest["version"]

    def spec(self, kind: str, sid: str) -> ActionSpec | None:
        return self.cls.__ooa_package__["specs"][kind].get(sid)


@dataclass
class PackageRegistry:
    trusted_keys: dict[str, Ed25519PublicKey] = field(default_factory=dict)
    allowed_publishers: set[str] | None = None  # None = any trusted publisher
    packages: dict[str, dict[str, LoadedPackage]] = field(default_factory=dict)
    defaults: dict[str, str] = field(default_factory=dict)
    usage: dict[tuple[str, str], set[str]] = field(default_factory=dict)

    def trust(self, key: PublisherKey) -> None:
        self.trusted_keys[key.publisher] = key.public_key

    def install(self, cls, signature: dict | None) -> LoadedPackage:
        manifest = build_manifest(cls)
        pid, ver = manifest["id"], manifest["version"]
        if not signature:
            raise PackageError(f"Package {pid}@{ver} is unsigned", code="PKG.UNSIGNED",
                               suggested_fix="Build the package with the OOA SDK so it is signed.")
        publisher = signature.get("publisher")
        if self.allowed_publishers is not None and publisher not in self.allowed_publishers:
            raise PackageError(f"Publisher '{publisher}' is not on this tenant's allow-list",
                               code="PKG.PUBLISHER_NOT_ALLOWED",
                               suggested_fix="Ask a tenant admin to approve the publisher.")
        pub = self.trusted_keys.get(publisher)
        if pub is None:
            raise PackageError(f"No trusted key for publisher '{publisher}'", code="PKG.UNTRUSTED")
        if package_digest(cls) != signature.get("digest"):
            raise PackageError(f"Package {pid}@{ver} was modified after signing", code="PKG.TAMPERED")
        try:
            pub.verify(base64.b64decode(signature["signature"]), signature["digest"].encode())
        except (InvalidSignature, KeyError, ValueError):
            raise PackageError(f"Signature check failed for {pid}@{ver}", code="PKG.BAD_SIGNATURE") from None
        lp = LoadedPackage(cls, manifest, cls(), signature)
        self.packages.setdefault(pid, {})[ver] = lp
        if pid not in self.defaults or _v(ver) > _v(self.defaults[pid]):
            self.defaults[pid] = ver
        return lp

    def set_default(self, pid: str, version: str) -> None:
        if version not in self.packages.get(pid, {}):
            raise PackageError(f"{pid}@{version} is not installed")
        self.defaults[pid] = version

    def resolve(self, pid: str, rng: str | None = None) -> LoadedPackage:
        versions = self.packages.get(pid)
        if not versions:
            raise PackageError(f"Package '{pid}' is not installed", code="PKG.NOT_FOUND",
                               suggested_fix=f"Install {pid} in Resources > Packages.")
        if rng is None:
            return versions[self.defaults[pid]]
        default = self.defaults.get(pid)
        if default and satisfies(default, rng):
            return versions[default]
        ok = sorted((v for v in versions if satisfies(v, rng)), key=_v)
        if not ok:
            raise PackageError(f"No installed version of '{pid}' satisfies '{rng}'", code="PKG.VERSION_MISMATCH",
                               suggested_fix=f"Installed: {', '.join(sorted(versions))}.")
        return versions[ok[-1]]

    def record_usage(self, pid: str, version: str, automation_id: str) -> None:
        self.usage.setdefault((pid, version), set()).add(automation_id)

    def all_manifests(self) -> list[dict]:
        return [self.packages[p][self.defaults[p]].manifest for p in sorted(self.packages)]
