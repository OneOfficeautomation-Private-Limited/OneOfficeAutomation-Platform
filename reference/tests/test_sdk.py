import pytest

from ooa.packages import FIRST_PARTY
from ooa.sdk import Input, PackageError, PublisherKey, action, build_manifest, package, sign_package
from ooa.sdk.registry import PackageRegistry, satisfies


@package("acme.demo", "Demo", "1.2.0", description="Demo package.", publisher="acme")
class Demo:
    @action("hello", "Hello", description="Says hello.", inputs=[Input("name", "String", description="Name.")])
    def hello(self, ctx, name):
        return {}


def test_manifest_shape():
    m = build_manifest(Demo)
    assert m["id"] == "acme.demo" and m["actions"][0]["inputs"]["required"] == ["name"]
    assert m["actions"][0]["inputs"]["additionalProperties"] is False


def test_undocumented_actions_are_rejected():
    with pytest.raises(PackageError):
        @action("x", "X", description="")
        def f(self, ctx): ...


def test_signature_verification():
    acme, evil = PublisherKey.generate("acme"), PublisherKey.generate("acme")
    reg = PackageRegistry()
    reg.trust(acme)
    with pytest.raises(PackageError) as e:
        reg.install(Demo, None)
    assert e.value.code == "PKG.UNSIGNED"
    with pytest.raises(PackageError) as e:
        reg.install(Demo, sign_package(Demo, evil))  # same publisher name, wrong key
    assert e.value.code == "PKG.BAD_SIGNATURE"
    sig = sign_package(Demo, acme)
    with pytest.raises(PackageError) as e:
        reg.install(Demo, {**sig, "digest": "0" * 64})
    assert e.value.code == "PKG.TAMPERED"
    assert reg.install(Demo, sig).version == "1.2.0"


def test_publisher_allow_list():
    acme = PublisherKey.generate("acme")
    reg = PackageRegistry(allowed_publishers={"oneofficeautomation"})
    reg.trust(acme)
    with pytest.raises(PackageError) as e:
        reg.install(Demo, sign_package(Demo, acme))
    assert e.value.code == "PKG.PUBLISHER_NOT_ALLOWED"


@pytest.mark.parametrize("v,r,ok", [("1.4.2", "^1.2.0", True), ("2.0.0", "^1.2.0", False), ("0.3.1", "^0.3.0", True),
                                    ("0.4.0", "^0.3.0", False), ("1.2.9", "~1.2.0", True), ("1.3.0", "~1.2.0", False),
                                    ("3.0.0", ">=2.1.0", True), ("1.0.0", "1.0.0", True)])
def test_semver(v, r, ok):
    assert satisfies(v, r) is ok


def test_first_party_manifests_are_llm_ready():
    """Architecture s19: every action and input carries a description."""
    for cls in FIRST_PARTY:
        m = build_manifest(cls)
        for a in m["actions"]:
            assert a["description"], a["id"]
            for name, prop in a["inputs"]["properties"].items():
                assert prop.get("description"), f"{m['id']}/{a['id']}.{name}"
