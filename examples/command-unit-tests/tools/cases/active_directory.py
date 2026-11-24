"""Active Directory against a local in-memory LDAP directory (standin/msg_ldap/LdapStandin.java, started separately):
DC=ut,DC=test with OU=Staff (users ann.lee and bob.ray, group Finance with Ann, computer PC-SEED) and OU=Archive.
Inputs ldap_url and ldap_reset say where it runs. Every case signs in itself and makes the objects it changes."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check  # noqa: E402

M = "ooa.active_directory/"
BASE = "DC=ut,DC=test"
STAFF = "OU=Staff," + BASE
ARCHIVE = "OU=Archive," + BASE
PW = {"$credential": "unit_tests/ldap/password"}
USER_PW = {"$credential": "unit_tests/ldap/user_password"}


def connect(f):
    f.act(M + "connect", desc="Sign in to the test directory", url="$ldap_url$", bind_user="CN=admin," + BASE, password=PW, base_dn=BASE)


def user(f, name, var="s"):
    connect(f)
    f.act(M + "create_user", desc="Create the test user " + name, out={"dn": var}, container_dn=STAFF, username=name, first_name="Test", last_name=name)


def add(ua):
    ua.f.var("ldap_url", "String", "input", "ldap://127.0.0.1:8895", "Address of the local in-memory LDAP directory (standin/msg_ldap).")
    ua.f.var("ldap_reset", "String", "input", "http://127.0.0.1:8896/reset", "Address that puts the test directory back as seeded.")
    start(ua, None, "")
    with ua.f.step("Prepare: fresh test directory", why="Every case below talks to the local in-memory LDAP directory; it starts as seeded."):
        ua.f.act("ooa.rest/request", desc="Put the directory back as seeded", url="$ldap_reset$", method="POST", out={"status": "ut_v"})
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("s2", "String"), ("e", "Dictionary"), ("l", "List"), ("x", "Any")):
        ua.var(v, t)

    with ua.case(M + "connect", "and a wrong password", "Connect signs in to the directory; with a wrong password it fails with AD.AUTH_FAILED.") as f:
        connect(f)
        f.act(M + "get_user", desc="Read Ann through the session", out={"user": "e"}, username="ann.lee")
        check(ua, "$e.sAMAccountName", "ann.lee", "sign-in name")
        ua.expect_error("AD.AUTH_FAILED", lambda ff: ff.act(M + "connect", desc="Sign in with a wrong password", url="$ldap_url$", bind_user="CN=admin," + BASE,
                                                             password={"$credential": "unit_tests/ldap/wrong_password"}, base_dn=BASE, session="wrong"))
    with ua.case(M + "disconnect", "then steps need a session", "Disconnect closes the directory session; a later step without one fails.") as f:
        connect(f)
        f.act(M + "disconnect", desc="Sign out")
        ua.expect_error("*", lambda ff: ff.act(M + "get_user", desc="Read Ann without a session", username="ann.lee"))
    with ua.case(M + "build_dn", "a user in a nested OU", "Build DN turns a path and a domain into a distinguished name, nothing read from a directory.") as f:
        f.act(M + "build_dn", desc="Build the DN", out={"dn": "s", "parent_dn": "s2"}, path="Staff/Robots", domain="ut.test", name="Jane Doe")
        ua.check("contains", "$s$", "CN=Jane Doe", what="dn")
        ua.check("contains", "$s2$", "DC=ut,DC=test", what="parent dn")

    # ---- users
    with ua.case(M + "get_user", "one attribute", "Get user reads a user; asked for one attribute it gives that value on its own.") as f:
        connect(f)
        f.act(M + "get_user", desc="Read Ann's department", out={"value": "x"}, username="ann.lee", attributes=["department"])
        ua.check_eq("x", "Finance")
    with ua.case(M + "create_user", "in Staff", "Create user makes a user in an OU and gives its distinguished name.") as f:
        user(f, "ut.create")
        ua.check("contains", "$s$", "OU=Staff", what="dn")
    with ua.case(M + "delete_user", "remove it", "Delete user removes the user; reading it afterwards fails with AD.USER_NOT_FOUND.") as f:
        user(f, "ut.delete")
        f.act(M + "delete_user", desc="Delete it", username="ut.delete")
        ua.expect_error("AD.USER_NOT_FOUND", lambda ff: ff.act(M + "get_user", desc="Read it", username="ut.delete"))
    with ua.case(M + "disable_user", "the account", "Disable user turns the account off; Get user then says it is not enabled.") as f:
        user(f, "ut.disable")
        f.act(M + "disable_user", desc="Disable it", username="ut.disable")
        f.act(M + "get_user", desc="Read it", out={"user": "e"}, username="ut.disable")
        check(ua, "$e.enabled", False, "enabled")
    with ua.case(M + "enable_user", "Bob's account", "Enable user turns a disabled account on.") as f:
        connect(f)
        f.act(M + "enable_user", desc="Enable Bob", username="bob.ray")
        f.act(M + "get_user", desc="Read Bob", out={"user": "e"}, username="bob.ray")
        check(ua, "$e.enabled", True, "enabled")
    with ua.case(M + "unlock_user", "a locked account", "Unlock user clears the lockout; Get user then says it is not locked.") as f:
        user(f, "ut.locked")
        f.act(M + "set_attribute", desc="Lock it for the test", username="ut.locked", attribute="lockoutTime", value="133000000000000000")
        f.act(M + "unlock_user", desc="Unlock it", username="ut.locked")
        f.act(M + "get_user", desc="Read it", out={"user": "e"}, username="ut.locked")
        check(ua, "$e.locked", False, "locked")
    with ua.case(M + "update_user_details", "title and office", "Update user details changes some attributes and names them.") as f:
        user(f, "ut.details")
        f.act(M + "update_user_details", desc="Set title and office", out={"changed": "l"}, username="ut.details", title="Analyst", office="Pune")
        check(ua, "len($l$)", 2, "attributes changed")
        f.act(M + "get_user", desc="Read the title", out={"value": "x"}, username="ut.details", attributes=["title"])
        ua.check_eq("x", "Analyst")
    with ua.case(M + "update_account_options", "password never expires", "Update account options sets account flags and gives the new userAccountControl.") as f:
        user(f, "ut.options")
        f.act(M + "update_account_options", desc="Password never expires, enabled", out={"user_account_control": "n"}, username="ut.options", enabled=True, password_never_expires=True)
        ua.check_eq("n", 66048)
    with ua.case(M + "set_attribute", "add a phone number", "Set attribute changes one attribute; Get user reads it back.") as f:
        user(f, "ut.attr")
        f.act(M + "set_attribute", desc="Set telephoneNumber", username="ut.attr", attribute="telephoneNumber", value="+91 20 0000 0000")
        f.act(M + "get_user", desc="Read it", out={"value": "x"}, username="ut.attr", attributes=["telephoneNumber"])
        ua.check_eq("x", "+91 20 0000 0000")
    with ua.case(M + "set_password", "a new password", "Set password writes a new password (a real domain needs ldaps://; the test directory takes it on ldap://).") as f:
        user(f, "ut.pw1")
        f.act(M + "set_password", desc="Set the password", username="ut.pw1", new_password=USER_PW, must_change=True)
        f.act(M + "get_user", desc="Read pwdLastSet", out={"value": "x"}, username="ut.pw1", attributes=["pwdLastSet"])
        ua.check_eq("x", "0")
    with ua.case(M + "reset_password", "and unlock", "Reset password sets a password as an administrator and can unlock the account.") as f:
        user(f, "ut.pw2")
        f.act(M + "reset_password", desc="Reset the password", username="ut.pw2", new_password=USER_PW, must_change=False, unlock=True)
        f.act(M + "get_user", desc="Read it", out={"user": "e"}, username="ut.pw2")
        check(ua, "$e.locked", False, "locked")
    with ua.case(M + "move_user", "to Archive", "Move user moves the user to another OU and gives the new distinguished name.") as f:
        user(f, "ut.move")
        f.act(M + "move_user", desc="Move it", out={"dn": "s"}, username="ut.move", target_ou_dn=ARCHIVE)
        ua.check("contains", "$s$", "OU=Archive", what="new dn")
    with ua.case(M + "rename_user", "a new name", "Rename user renames the user's directory object.") as f:
        user(f, "ut.rename")
        f.act(M + "rename_user", desc="Rename it", out={"dn": "s"}, username="ut.rename", new_name="UT Renamed")
        ua.check("contains", "$s$", "CN=UT Renamed", what="new dn")

    # ---- groups
    with ua.case(M + "create_group", "a security group", "Create group makes a group and gives its distinguished name.") as f:
        connect(f)
        f.act(M + "create_group", desc="Create UT Group", out={"dn": "s"}, container_dn=STAFF, name="UT Group", group_description="Unit tests")
        ua.check_eq("s", "CN=UT Group," + STAFF)
    with ua.case(M + "add_to_group", "two users", "Add to group adds users to a group and counts those added.") as f:
        user(f, "ut.member1")
        f.act(M + "create_group", desc="Create UT Add", out={"dn": "s2"}, container_dn=STAFF, name="UT Add")
        f.act(M + "add_to_group", desc="Add two users", out={"changed": "n"}, username="ut.member1", more_usernames=["bob.ray"], group_dn="$s2$")
        ua.check_eq("n", 2)
    with ua.case(M + "remove_from_group", "Ann from Finance", "Remove from group takes a user out and counts those removed.") as f:
        connect(f)
        f.act(M + "remove_from_group", desc="Remove Ann", out={"changed": "n"}, username="ann.lee", group_dn="CN=Finance," + STAFF)
        ua.check_eq("n", 1)
    with ua.case(M + "get_group_members", "names only", "Group members lists a group's members, here as names.") as f:
        user(f, "ut.member2")
        f.act(M + "create_group", desc="Create UT Members", out={"dn": "s2"}, container_dn=STAFF, name="UT Members")
        f.act(M + "add_to_group", desc="Add the user", username="ut.member2", group_dn="$s2$")
        f.act(M + "get_group_members", desc="Members as names", out={"list": "l", "count": "n"}, group="UT Members", return_as="names")
        ua.check_eq("n", 1)
    with ua.case(M + "get_group_property", "description", "Get group property reads one attribute of a group.") as f:
        connect(f)
        f.act(M + "get_group_property", desc="Finance's description", out={"value": "x"}, group="Finance", attributes=["description"])
        ua.check_eq("x", "Finance team")
    with ua.case(M + "set_group_property", "description", "Set group property changes one attribute of a group.") as f:
        connect(f)
        f.act(M + "create_group", desc="Create UT Prop", container_dn=STAFF, name="UT Prop")
        f.act(M + "set_group_property", desc="Set the description", group="UT Prop", attribute="description", value="Changed")
        f.act(M + "get_group_property", desc="Read it", out={"value": "x"}, group="UT Prop", attributes=["description"])
        ua.check_eq("x", "Changed")
    with ua.case(M + "move_group", "to Archive", "Move group moves a group to another OU.") as f:
        connect(f)
        f.act(M + "create_group", desc="Create UT Move", container_dn=STAFF, name="UT Move")
        f.act(M + "move_group", desc="Move it", out={"dn": "s"}, group="UT Move", target_ou_dn=ARCHIVE)
        ua.check_eq("s", "CN=UT Move," + ARCHIVE)
    with ua.case(M + "rename_group", "a new name", "Rename group renames a group.") as f:
        connect(f)
        f.act(M + "create_group", desc="Create UT Old", container_dn=STAFF, name="UT Old")
        f.act(M + "rename_group", desc="Rename it", out={"dn": "s"}, group="UT Old", new_name="UT New")
        ua.check_eq("s", "CN=UT New," + STAFF)
    with ua.case(M + "delete_group", "remove it", "Delete group removes a group; reading it afterwards fails.") as f:
        connect(f)
        f.act(M + "create_group", desc="Create UT Gone", container_dn=STAFF, name="UT Gone")
        f.act(M + "delete_group", desc="Delete it", group="UT Gone")
        ua.expect_error("*", lambda ff: ff.act(M + "get_group_property", desc="Read it", group="UT Gone"))

    # ---- computers
    with ua.case(M + "create_computer", "in Staff", "Create computer makes a computer account and gives its distinguished name.") as f:
        connect(f)
        f.act(M + "create_computer", desc="Create UT-PC1", out={"dn": "s"}, container_dn=STAFF, name="UT-PC1", computer_description="Test PC")
        ua.check_eq("s", "CN=UT-PC1," + STAFF)
    with ua.case(M + "get_computer_property", "description", "Get computer property reads one attribute of a computer.") as f:
        connect(f)
        f.act(M + "get_computer_property", desc="PC-SEED's description", out={"value": "x"}, computer="PC-SEED", attributes=["description"])
        ua.check_eq("x", "Seeded computer")
    with ua.case(M + "set_computer_property", "location", "Set computer property changes one attribute of a computer.") as f:
        connect(f)
        f.act(M + "create_computer", desc="Create UT-PC2", container_dn=STAFF, name="UT-PC2")
        f.act(M + "set_computer_property", desc="Set location", computer="UT-PC2", attribute="location", value="Pune")
        f.act(M + "get_computer_property", desc="Read it", out={"value": "x"}, computer="UT-PC2", attributes=["location"])
        ua.check_eq("x", "Pune")
    with ua.case(M + "move_computer", "to Archive", "Move computer moves a computer to another OU.") as f:
        connect(f)
        f.act(M + "create_computer", desc="Create UT-PC3", container_dn=STAFF, name="UT-PC3")
        f.act(M + "move_computer", desc="Move it", out={"dn": "s"}, computer="UT-PC3", target_ou_dn=ARCHIVE)
        ua.check_eq("s", "CN=UT-PC3," + ARCHIVE)
    with ua.case(M + "rename_computer", "a new name", "Rename computer renames a computer.") as f:
        connect(f)
        f.act(M + "create_computer", desc="Create UT-PC4", container_dn=STAFF, name="UT-PC4")
        f.act(M + "rename_computer", desc="Rename it", out={"dn": "s"}, computer="UT-PC4", new_name="UT-PC5")
        ua.check("contains", "$s$", "CN=UT-PC5", what="new dn")
    with ua.case(M + "delete_computer", "remove it", "Delete computer removes a computer account.") as f:
        connect(f)
        f.act(M + "create_computer", desc="Create UT-PC6", container_dn=STAFF, name="UT-PC6")
        f.act(M + "delete_computer", desc="Delete it", computer="UT-PC6")
        ua.expect_error("*", lambda ff: ff.act(M + "get_computer_property", desc="Read it", computer="UT-PC6"))

    # ---- organizational units
    with ua.case(M + "create_ou", "under the domain", "Create OU makes an organizational unit and gives its distinguished name.") as f:
        connect(f)
        f.act(M + "create_ou", desc="Create Robots", out={"dn": "s"}, parent_dn=BASE, name="Robots", ou_description="Agents")
        ua.check_eq("s", "OU=Robots," + BASE)
    with ua.case(M + "get_ou_property", "description", "Get OU property reads one attribute of an OU.") as f:
        connect(f)
        f.act(M + "get_ou_property", desc="Staff's description", out={"value": "x"}, ou="Staff", attributes=["description"])
        ua.check_eq("x", "People")
    with ua.case(M + "set_ou_property", "description", "Set OU property changes one attribute of an OU.") as f:
        connect(f)
        f.act(M + "create_ou", desc="Create UT Props", parent_dn=BASE, name="UT Props")
        f.act(M + "set_ou_property", desc="Set the description", ou="UT Props", attribute="description", value="Changed")
        f.act(M + "get_ou_property", desc="Read it", out={"value": "x"}, ou="UT Props", attributes=["description"])
        ua.check_eq("x", "Changed")
    with ua.case(M + "move_ou", "under Archive", "Move OU moves an OU under another one.") as f:
        connect(f)
        f.act(M + "create_ou", desc="Create UT Moving", parent_dn=BASE, name="UT Moving")
        f.act(M + "move_ou", desc="Move it", out={"dn": "s"}, ou="UT Moving", target_parent_dn=ARCHIVE)
        ua.check_eq("s", "OU=UT Moving," + ARCHIVE)
    with ua.case(M + "rename_ou", "a new name", "Rename OU renames an OU.") as f:
        connect(f)
        f.act(M + "create_ou", desc="Create UT Before", parent_dn=BASE, name="UT Before")
        f.act(M + "rename_ou", desc="Rename it", out={"dn": "s"}, ou="UT Before", new_name="UT After")
        ua.check_eq("s", "OU=UT After," + BASE)
    with ua.case(M + "delete_ou", "with what is in it", "Delete OU removes an OU and, recursive, the objects inside it.") as f:
        connect(f)
        f.act(M + "create_ou", desc="Create UT Doomed", out={"dn": "s"}, parent_dn=BASE, name="UT Doomed")
        f.act(M + "create_group", desc="Put a group in it", container_dn="$s$", name="UT Inside")
        f.act(M + "delete_ou", desc="Delete it all", ou="UT Doomed", recursive=True)
        ua.expect_error("*", lambda ff: ff.act(M + "get_ou_property", desc="Read it", ou="UT Doomed"))

    # ---- searching
    with ua.case(M + "search", "Finance users", "Search runs an LDAP filter over the directory and gives a table of entries.") as f:
        connect(f)
        f.act(M + "search", desc="Users in Finance", out={"table": "t", "count": "n"}, filter="(&(objectClass=user)(department=Finance))", attributes=["sAMAccountName"])
        ua.check_eq("n", 2)
    with ua.case(M + "run_query", "OUs one level down, as names", "Run query searches from a starting point with a scope and can give the names only.") as f:
        connect(f)
        f.act(M + "run_query", desc="OUs under the domain", out={"list": "l", "count": "n"}, filter="(objectClass=organizationalUnit)", base_dn=BASE,
              scope="one_level", return_as="names")
        ua.check("ge", "$n$", 2, what="OUs found")
        ua.check("contains", "$l$", "Staff", what="names")
