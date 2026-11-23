"""Okta cases: Connect keeps the org address and API token; the steps after it give neither."""
from helpers_business import drop_samples, cred, url, vars_

ORG = url("okta")
ANN, BEN, CARA = "ann.lee@ut.example", "ben.ray@ut.example", "cara.ng@ut.example"


def add(ua):
    drop_samples(ua)
    vars_(ua, "ok_user", "ok_profile", "ok_id", "ok_status", "ok_table", "ok_count", "ok_names", "ok_n", "ok_url",
          "ok_done", "ok_temp", "ok_closed")

    with ua.case("ooa.okta/connect", "keep the org and API token for later steps",
                 "Connect keeps the Okta address and API token as the Okta session, so the next steps leave them empty. "
                 "Checks a step without its own address then reads Ann's record.") as f:
        f.act("ooa.okta/connect", desc="Open the Okta session", org_url=ORG, api_token=cred("okta", "api_token"))
        f.act("ooa.okta/get_user", desc="Read Ann using the session", user=ANN, out={"user": "ok_user"})
        ua.check("eq", "$ok_user.first_name$", "Ann", what="Ann's first name")

    with ua.case("ooa.okta/get_user", "look up by sign-in name",
                 "Get user reads one person by Okta id or login and gives back a summary and the whole profile. "
                 "Checks the id and the profile's email.") as f:
        f.act("ooa.okta/get_user", desc="Read Ben", user=BEN, out={"user": "ok_user", "profile": "ok_profile"})
        ua.check("eq", "$ok_user.id$", "00uut000000000000002", what="Ben's id")
        ua.check("eq", "$ok_profile.email$", BEN, what="the profile email")

    with ua.case("ooa.okta/create_user", "new active user with a password and a group",
                 "Create user adds a person with a starting password (from the vault) and puts them in a group found by "
                 "name. A leftover from an earlier run is removed first. Checks the new user is ACTIVE and in the group.") as f:
        f.act("ooa.okta/delete_user", desc="Remove a leftover from an earlier run (may not exist)", user="erin.park@ut.example", cont=True)
        f.act("ooa.okta/create_user", desc="Create Erin Park", first_name="Erin", last_name="Park", email="erin.park@ut.example",
              password=cred("okta", "new_password"), groups=["UT Sales"], phone="+44 20 7946 0001",
              out={"user_id": "ok_id", "status": "ok_status"})
        ua.check_eq("ok_status", "ACTIVE")
        ua.check("notEmpty", "$ok_id$", what="the new user id")
        f.act("ooa.okta/list_user_groups", desc="Erin's groups", user="$ok_id$", out={"names": "ok_names"})
        ua.check("contains", "$ok_names$", "UT Sales", what="Erin's groups")

    with ua.case("ooa.okta/update_user", "change a name and a profile field",
                 "Update user profile changes only the values given: here the first name and a custom title field. "
                 "Checks the returned user and the stored profile.") as f:
        f.act("ooa.okta/update_user", desc="Rename Ben and set his title", user=BEN, first_name="Benjamin",
              profile_fields={"title": "Sales lead"}, out={"user": "ok_user"})
        ua.check("eq", "$ok_user.first_name$", "Benjamin", what="the new first name")
        f.act("ooa.okta/get_user", desc="Read Ben again", user=BEN, out={"profile": "ok_profile"})
        ua.check("eq", "$ok_profile.title$", "Sales lead", what="the title field")

    with ua.case("ooa.okta/activate_user", "activate a staged account without email",
                 "Activate user activates a staged account; with send_email off Okta gives back a one-time activation "
                 "link. The case creates a staged user first. Checks the link and the ACTIVE status.") as f:
        f.act("ooa.okta/delete_user", desc="Remove a leftover from an earlier run (may not exist)", user="sam.staged@ut.example", cont=True)
        f.act("ooa.okta/create_user", desc="Create a staged user", first_name="Sam", last_name="Staged",
              email="sam.staged@ut.example", activate=False, out={"user_id": "ok_id", "status": "ok_status"})
        ua.check_eq("ok_status", "STAGED")
        f.act("ooa.okta/activate_user", desc="Activate Sam", user="$ok_id$", send_email=False, out={"activation_url": "ok_url"})
        ua.check("contains", "$ok_url$", "/welcome/", what="the activation link")
        f.act("ooa.okta/get_user", desc="Read Sam", user="$ok_id$", out={"user": "ok_user"})
        ua.check("eq", "$ok_user.status$", "ACTIVE", what="Sam's status")

    with ua.case("ooa.okta/deactivate_user", "turn off an account",
                 "Deactivate user turns an account off. Checks Sam's status is DEPROVISIONED afterwards.") as f:
        f.act("ooa.okta/deactivate_user", desc="Deactivate Sam", user="sam.staged@ut.example", send_email=False)
        f.act("ooa.okta/get_user", desc="Read Sam", user="sam.staged@ut.example", out={"user": "ok_user"})
        ua.check("eq", "$ok_user.status$", "DEPROVISIONED", what="Sam's status")

    with ua.case("ooa.okta/delete_user", "remove an account for good",
                 "Delete user removes a person (deactivating first when needed). Checks reading Sam afterwards fails "
                 "with OKTA.NOT_FOUND.") as f:
        f.act("ooa.okta/delete_user", desc="Delete Sam", user="sam.staged@ut.example")
        ua.expect_error("OKTA.NOT_FOUND", lambda ff: ff.act("ooa.okta/get_user", desc="Read Sam (should be gone)",
                                                           user="sam.staged@ut.example", out={"user": "ok_user"}))

    with ua.case("ooa.okta/suspend_user", "block sign-in for now",
                 "Suspend user blocks an active account for now. Checks Ben is SUSPENDED, then lets him back in.") as f:
        f.act("ooa.okta/suspend_user", desc="Suspend Ben", user=BEN)
        f.act("ooa.okta/get_user", desc="Read Ben", user=BEN, out={"user": "ok_user"})
        ua.check("eq", "$ok_user.status$", "SUSPENDED", what="Ben's status")
        f.act("ooa.okta/unsuspend_user", desc="Let Ben back in", user=BEN)

    with ua.case("ooa.okta/unsuspend_user", "let a suspended person in again",
                 "Unsuspend user lets a suspended person sign in again. Dev starts suspended; checks he is ACTIVE "
                 "afterwards, and suspends him again so the next run starts the same.") as f:
        f.act("ooa.okta/unsuspend_user", desc="Unsuspend Dev", user="dev.rao@ut.example")
        f.act("ooa.okta/get_user", desc="Read Dev", user="dev.rao@ut.example", out={"user": "ok_user"})
        ua.check("eq", "$ok_user.status$", "ACTIVE", what="Dev's status")
        f.act("ooa.okta/suspend_user", desc="Suspend Dev again", user="dev.rao@ut.example")

    with ua.case("ooa.okta/unlock_user", "unlock a locked-out person",
                 "Unlock user lets a person locked out by wrong passwords sign in again. Checks Cara is ACTIVE.") as f:
        f.act("ooa.okta/unlock_user", desc="Unlock Cara", user=CARA)
        f.act("ooa.okta/get_user", desc="Read Cara", user=CARA, out={"user": "ok_user"})
        ua.check("eq", "$ok_user.status$", "ACTIVE", what="Cara's status")

    with ua.case("ooa.okta/expire_password", "temporary password",
                 "Expire password makes the person choose a new password at next sign-in; with temporary_password on "
                 "Okta makes a temporary one, kept secret in logs. Checks one came back.") as f:
        f.act("ooa.okta/expire_password", desc="Expire Ann's password", user=ANN, temporary_password=True,
              out={"temporary_password": "ok_temp"})
        ua.check("notEmpty", "$ok_temp$", what="the temporary password")

    with ua.case("ooa.okta/reset_password", "reset link instead of an email",
                 "Reset password starts a reset; with send_email off Okta gives back a one-time reset link. Checks the link.") as f:
        f.act("ooa.okta/reset_password", desc="Start Ann's reset", user=ANN, send_email=False, out={"reset_url": "ok_url"})
        ua.check("contains", "$ok_url$", "/reset_password/", what="the reset link")

    with ua.case("ooa.okta/clear_sessions", "sign someone out everywhere",
                 "Clear user sessions ends a person's Okta sessions and revokes app tokens. Checks it works for Ann and "
                 "fails with OKTA.NOT_FOUND for an unknown person.") as f:
        f.act("ooa.okta/clear_sessions", desc="Sign Ann out everywhere", user=ANN, revoke_app_tokens=True)
        ua.expect_error("OKTA.NOT_FOUND", lambda ff: ff.act("ooa.okta/clear_sessions", desc="Unknown person",
                                                           user="nobody@ut.example"))

    with ua.case("ooa.okta/list_users", "quick text search",
                 "List users with search_text finds people whose name or email starts with the text. Checks one match.") as f:
        f.act("ooa.okta/list_users", desc="Users starting with cara", search_text="cara", out={"table": "ok_table", "count": "ok_count"})
        ua.check_eq("ok_count", 1)

    with ua.case("ooa.okta/list_users", "search expression",
                 "List users with an Okta filter expression. Checks the one user with last name Ray.") as f:
        f.act("ooa.okta/list_users", desc="Users whose last name is Ray", filter='profile.lastName eq "Ray"', out={"count": "ok_count"})
        ua.check_eq("ok_count", 1)

    with ua.case("ooa.okta/list_groups", "name starts with",
                 "List groups returns groups whose name starts with the text. Checks UT Sales is the only match.") as f:
        f.act("ooa.okta/list_groups", desc="Groups starting with UT S", name_starts_with="UT S", out={"table": "ok_table", "count": "ok_count"})
        ua.check_eq("ok_count", 1)
        f.act("ooa.okta/list_groups", desc="Groups made by apps", search='type eq "APP_GROUP"', out={"count": "ok_count"})
        ua.check_eq("ok_count", 1)

    with ua.case("ooa.okta/add_user_to_group", "add by group name",
                 "Add user to group makes a person a member of a group given by name. Checks done and Ben's groups.") as f:
        f.act("ooa.okta/add_user_to_group", desc="Add Ben to UT Finance", user=BEN, group="UT Finance", out={"done": "ok_done"})
        ua.check_eq("ok_done", True)
        f.act("ooa.okta/list_user_groups", desc="Ben's groups", user=BEN, out={"names": "ok_names"})
        ua.check("contains", "$ok_names$", "UT Finance", what="Ben's groups")

    with ua.case("ooa.okta/list_user_groups", "every group of a person",
                 "List user's groups gives every group a person belongs to, as a table and a list of names. Ann is in two.") as f:
        f.act("ooa.okta/list_user_groups", desc="Ann's groups", user=ANN, out={"table": "ok_table", "names": "ok_names"})
        f.expr("ok_n", "len($ok_names$)", desc="Count the names")
        ua.check_eq("ok_n", 2)

    with ua.case("ooa.okta/remove_user_from_group", "remove by group id",
                 "Remove user from group takes a person out of a group given by id. Checks done and that Ben is no "
                 "longer in UT Finance.") as f:
        f.act("ooa.okta/add_user_to_group", desc="Make sure Ben is in UT Finance", user=BEN, group="00gut000000000000001")
        f.act("ooa.okta/remove_user_from_group", desc="Remove Ben from UT Finance", user=BEN, group="00gut000000000000001", out={"done": "ok_done"})
        ua.check_eq("ok_done", True)
        f.act("ooa.okta/list_user_groups", desc="Ben's groups", user=BEN, out={"names": "ok_names"})
        f.expr("ok_n", "len($ok_names$)", desc="Count the names")
        ua.check_eq("ok_n", 0)

    with ua.case("ooa.okta/disconnect", "forget the session",
                 "Disconnect forgets the Okta session; later steps give their own address and sign-in (here an OAuth "
                 "access token). Checks a session was open and the next step still works with its own sign-in.") as f:
        f.act("ooa.okta/disconnect", desc="Close the Okta session", out={"closed": "ok_closed"})
        ua.check_eq("ok_closed", True)
        f.act("ooa.okta/get_user", desc="Read Ann with the step's own address and access token", org_url=ORG,
              access_token=cred("okta", "access_token"), user=ANN, out={"user": "ok_user"})
        ua.check("eq", "$ok_user.login$", ANN, what="Ann's login")
