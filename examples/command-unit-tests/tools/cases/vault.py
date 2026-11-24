"""Credential vault cases for One CommandCenter, on the made-up credential unit_tests/ut_vault (attributes password and
new_password). Locally the vault is read-only, so save_credential needs One CommandCenter."""
from helpers_platform import drop, renumber, credential_var

V = "ooa.vault/"
REF = "unit_tests/ut_vault/password"


def add(ua):
    drop(ua, "Studio:", "condition: not expired when no date is known")   # those cases expect a run outside One CommandCenter
    credential_var(ua, "pw", "unit_tests/ut_vault/new_password")
    for v, t in (("vt_expires", "String"), ("vt_days", "Number"), ("vt_expired", "Boolean"), ("vt_soon", "Boolean"),
                 ("vt_has", "Boolean")):
        ua.var(v, t)
    f = ua.f
    with ua.case(V + "save_credential", "a new value that expires in 90 days",
                 "Save credential stores a new value of a vault attribute (here the made-up new_password, copied into "
                 "password) with an expiry date 90 days away; it gives back the date and the days left."):
        f.act(V + "save_credential", credential=REF, value="$pw$", expires_in_days=90,
              out={"expires": "vt_expires", "days_left": "vt_days"})
        ua.check("notEmpty", "$vt_expires$", what="the expiry date")
        ua.check("between", "$vt_days$", [89, 90], what="days left")
    with ua.case(V + "credential_expiry", "days left, not expired, not expiring soon",
                 "Credential expiry reads when an attribute expires. Just after it was saved for 90 days: it has an "
                 "expiry, 89 or 90 days are left, it has not expired and with warn_days 14 it is not expiring soon."):
        f.act(V + "save_credential", credential=REF, value="$pw$", expires_in_days=90)
        f.act(V + "credential_expiry", credential=REF, warn_days=14,
              out={"has_expiry": "vt_has", "days_left": "vt_days", "expired": "vt_expired", "expiring_soon": "vt_soon"})
        ua.check_eq("vt_has", True)
        ua.check("between", "$vt_days$", [89, 90], what="days left")
        ua.check_eq("vt_expired", False)
        ua.check_eq("vt_soon", False)
    ua.condition_case(V + "is_expired", "a credential saved for 90 days",
                      "Credential has expired is false while the expiry date is still ahead.", {"credential": REF}, False)
    with ua.case(V + "is_expired", "an expiry date in the past",
                 "After the value is saved with an expiry date in the past, Credential has expired is true. The "
                 "credential is saved again for 90 days afterwards."):
        ua.var("vt_cond", "Boolean", False)
        f.act(V + "save_credential", credential=REF, value="$pw$", expires="2020-01-01")
        f.set("vt_cond", False)
        with f.if_({"condition": V + "is_expired", "args": {"credential": REF}}, desc="Use the condition in an If"):
            f.set("vt_cond", True)
        f.act(V + "save_credential", credential=REF, value="$pw$", expires_in_days=90)
        ua.check_eq("vt_cond", True)
    renumber(ua)
