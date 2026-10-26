package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Active Directory / LDAP administration of users, groups, computers and organizational units over JNDI.
 * All filter values are escaped (RFC 4515) and all new names are escaped as RDN values (RFC 4514).
 * Directory calls go through {@link Directory}, so tests can use an in-memory directory.
 */
@OoaPackage(id = "ooa.active_directory", name = "Active Directory", version = "1.0.0",
        description = "Create, change, move and delete users, groups, computers and organizational units, reset passwords and manage group membership in Active Directory or LDAP.")
public final class ActiveDirectoryPackage {
    private static final int ACCOUNTDISABLE = 0x2;
    private static final int NORMAL_ACCOUNT = 0x200;
    private static final int WORKSTATION_TRUST_ACCOUNT = 0x1000;
    private static final int DONT_EXPIRE_PASSWORD = 0x10000;

    /** One directory entry: its distinguished name and the attributes that were read. */
    record Entry(String dn, Attributes attributes) {}

    /** The directory operations the package uses; the real one is JNDI, tests use an in-memory one. */
    interface Directory extends AutoCloseable {
        void search(String base, String filter, SearchControls controls, Consumer<Entry> each) throws NamingException;
        Attributes read(String dn, String[] attributes) throws NamingException;
        void modify(String dn, ModificationItem[] items) throws NamingException;
        void create(String dn, Attributes attributes) throws NamingException;
        void delete(String dn) throws NamingException;
        void rename(String dn, String newDn) throws NamingException;
        @Override void close() throws NamingException;

        /** The object's security descriptor (self-relative, binary), at least its DACL; null when it cannot be read. */
        default byte[] readSecurity(String dn) throws NamingException {
            Attribute a = read(dn, new String[]{"nTSecurityDescriptor"}).get("nTSecurityDescriptor");
            return a != null && a.get() instanceof byte[] b ? b : null;
        }

        /** Writes the DACL of a security descriptor back to the object. */
        default void writeSecurity(String dn, byte[] sd) throws NamingException {
            modify(dn, new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("nTSecurityDescriptor", sd))});
        }
    }

    static final class JndiDirectory implements Directory {
        final LdapContext c;
        JndiDirectory(LdapContext c) { this.c = c; }
        @Override public void search(String base, String filter, SearchControls sc, Consumer<Entry> each) throws NamingException {
            NamingEnumeration<SearchResult> r = c.search(new LdapName(base), filter, sc);
            try { while (r.hasMore()) { SearchResult e = r.next(); each.accept(new Entry(e.getNameInNamespace(), e.getAttributes())); } }
            finally { r.close(); }
        }
        @Override public Attributes read(String dn, String[] attributes) throws NamingException { return c.getAttributes(new LdapName(dn), attributes); }
        @Override public void modify(String dn, ModificationItem[] items) throws NamingException { c.modifyAttributes(new LdapName(dn), items); }
        @Override public void create(String dn, Attributes attributes) throws NamingException { c.createSubcontext(new LdapName(dn), attributes).close(); }
        @Override public void delete(String dn) throws NamingException { c.destroySubcontext(new LdapName(dn)); }
        @Override public void rename(String dn, String newDn) throws NamingException { c.rename(new LdapName(dn), new LdapName(newDn)); }
        @Override public void close() throws NamingException { c.close(); }

        /** Reads and writes only the DACL (LDAP_SERVER_SD_FLAGS_OID with DACL_SECURITY_INFORMATION), so no SACL rights are needed. */
        private static final javax.naming.ldap.Control[] DACL_ONLY = {new javax.naming.ldap.BasicControl("1.2.840.113556.1.4.801", true, new byte[]{0x30, 0x03, 0x02, 0x01, 0x04})};

        @Override public byte[] readSecurity(String dn) throws NamingException {
            c.addToEnvironment("java.naming.ldap.attributes.binary", "nTSecurityDescriptor");
            c.setRequestControls(DACL_ONLY);
            try { return Directory.super.readSecurity(dn); } finally { c.setRequestControls(null); }
        }

        @Override public void writeSecurity(String dn, byte[] sd) throws NamingException {
            c.setRequestControls(DACL_ONLY);
            try { Directory.super.writeSecurity(dn, sd); } finally { c.setRequestControls(null); }
        }
    }

    static final class AdSession implements AutoCloseable {
        final Directory dir; final String baseDn;
        AdSession(Directory d, String b) { dir = d; baseDn = b; }
        @Override public void close() throws NamingException { dir.close(); }
    }

    /** RFC 4515 escaping, so user-supplied values can never change the meaning of a filter. */
    static String escape(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c"); case '*' -> sb.append("\\2a"); case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29"); case '\0' -> sb.append("\\00"); default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static ActionException ad(NamingException e) {
        String m = String.valueOf(e.getExplanation() != null ? e.getExplanation() : e.getMessage());
        if (e instanceof AuthenticationException) return new ActionException("AD.AUTH_FAILED", "Directory sign-in failed: " + m, "Check the bind user and password.");
        if (e instanceof NoPermissionException) return new ActionException("AD.ACCESS_DENIED", "Not allowed: " + m, "The bind account needs permission for this change.");
        if (e instanceof CommunicationException) return new ActionException("AD.UNREACHABLE", "Cannot reach the directory: " + m, "Check the server name, port and network.", true);
        if (e instanceof NameAlreadyBoundException) return new ActionException("AD.ALREADY_EXISTS", "An object with that name already exists: " + m, "Pick another name or container.");
        if (e instanceof ContextNotEmptyException) return new ActionException("AD.NOT_EMPTY", "The object still contains other objects: " + m, "Move or delete what is inside first, or use the recursive option.");
        return new ActionException("AD.ERROR", m, null);
    }

    private static AdSession s(ActionContext ctx, String name) { return ctx.session(name, AdSession.class); }

    @Action(id = "connect", name = "Connect", category = "Directory", capabilities = "network", description = "Sign in to Active Directory or an LDAP server.",
            panel = "session, url, base_dn, bind_user, password | connect_timeout_seconds, read_timeout_seconds, follow_referrals")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^ldaps?://", example = "ldaps://dc01.corp.example.com:636", description = "Server URL; use ldaps:// for password changes.") String url,
            @Input(name = "bind_user", type = OoaType.STRING, example = "svc-bot@corp.example.com", description = "User to sign in as (UPN or distinguished name).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Password from the vault.") SecureString password,
            @Input(name = "base_dn", type = OoaType.STRING, example = "DC=corp,DC=example,DC=com", description = "Where searches start.") String baseDn,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "connect_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Seconds to wait for the server to accept the connection.") int connectTimeout,
            @Input(name = "read_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Seconds to wait for each answer from the server.") int readTimeout,
            @Input(name = "follow_referrals", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Follow referrals to other domain controllers; turn off to ignore them.") boolean referrals) {
        if (connectTimeout < 1 || connectTimeout > 600 || readTimeout < 1 || readTimeout > 3600)
            throw new ActionException("AD.BAD_TIMEOUT", "Timeouts must be 1-600 s (connect) and 1-3600 s (read)");
        try { ctx.sessions().put(session, new AdSession(new JndiDirectory(new InitialLdapContext(environment(url, user, password, connectTimeout, readTimeout, referrals), null)), baseDn)); }
        catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the timeout options. */
    public Map<String, Object> connect(ActionContext ctx, String url, String user, SecureString password, String baseDn, String session) {
        return connect(ctx, url, user, password, baseDn, session, 10, 30, true);
    }

    static Hashtable<String, Object> environment(String url, String user, SecureString password, int connectTimeout, int readTimeout, boolean referrals) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, user);
        env.put(Context.SECURITY_CREDENTIALS, password.reveal());
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeout * 1000));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeout * 1000));
        env.put(Context.REFERRAL, referrals ? "follow" : "ignore");
        return env;
    }

    private Entry findUser(AdSession s, String username) throws NamingException {
        String f = "(&(objectClass=user)(!(objectClass=computer))(|(sAMAccountName=" + escape(username) + ")(userPrincipalName=" + escape(username) + ")))";
        Entry e = first(s, f);
        if (e == null) throw new ActionException("AD.USER_NOT_FOUND", "User '" + username + "' not found", "Check the user name (sAMAccountName or UPN).");
        return e;
    }

    /** First match of a subtree search from the base DN (more matches are ignored). */
    private static Entry first(AdSession s, String filter) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(2);
        List<Entry> found = new ArrayList<>();
        try { s.dir.search(s.baseDn, filter, sc, found::add); } catch (SizeLimitExceededException ignored) { }
        return found.isEmpty() ? null : found.get(0);
    }

    static Object attrValue(Attribute a) throws NamingException {
        if (a == null) return null;
        if (a.size() == 1) { Object v = a.get(); return v instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) v) : String.valueOf(v); }
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) { Object v = a.get(i); l.add(v instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) v) : String.valueOf(v)); }
        return l;
    }

    private Map<String, Object> table(ActionContext ctx, AdSession s, String base, String filter, int scope, List<Object> attrs, int max) {
        List<String> names = new ArrayList<>();
        if (attrs != null) for (Object a : attrs) names.add(Values.text(a));
        if (names.isEmpty()) names.addAll(List.of("cn", "sAMAccountName", "mail"));
        SearchControls sc = new SearchControls(scope, max, 30000, names.toArray(new String[0]), false, false);
        List<String> cols = new ArrayList<>(List.of("dn"));
        cols.addAll(names);
        List<Object> rows = new ArrayList<>();
        try {
            s.dir.search(base, filter, sc, e -> {
                List<Object> row = new ArrayList<>();
                row.add(e.dn());
                try { for (String n : names) row.add(attrValue(e.attributes().get(n))); } catch (NamingException x) { throw new IllegalStateException(x); }
                rows.add(row);
            });
        } catch (SizeLimitExceededException e) { ctx.warn("Search stopped at " + max + " results");
        } catch (InvalidSearchFilterException e) { throw new ActionException("AD.BAD_FILTER", "Invalid LDAP filter: " + filter, "Example: (&(objectClass=user)(sAMAccountName=jdoe))");
        } catch (NamingException e) { throw ad(e); }
        return Out.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "search", name = "Search", category = "Directory", idempotent = true, capabilities = "network", description = "Run an LDAP filter and return matching entries as a table.",
            panel = "session, filter, attributes | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per entry; columns are dn plus the requested attributes.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of entries.")
    public Map<String, Object> search(ActionContext ctx,
            @Input(name = "filter", type = OoaType.STRING, example = "(&(objectClass=user)(department=Finance))", description = "LDAP filter.") String filter,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to return, e.g. [\"cn\",\"mail\"].") List<Object> attrs,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Safety limit.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        AdSession s = s(ctx, session);
        return table(ctx, s, s.baseDn, filter, SearchControls.SUBTREE_SCOPE, attrs, max);
    }

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network", description = "Read a user's properties by sAMAccountName or UPN.",
            panel = "session, username, attributes")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "dn, sAMAccountName, displayName, mail, enabled, locked and more.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String username,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "More attributes to read, e.g. [\"employeeID\",\"physicalDeliveryOfficeName\"]; the usual set is always returned.") List<Object> extra) {
        try {
            AdSession s = s(ctx, session);
            Entry r = findUser(s, username);
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("dn", r.dn());
            for (String a : List.of("sAMAccountName", "userPrincipalName", "displayName", "givenName", "sn", "mail", "department", "title", "manager", "memberOf", "telephoneNumber"))
                u.put(a, attrValue(r.attributes().get(a)));
            if (extra != null && !extra.isEmpty()) {
                String[] names = new String[extra.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = Values.text(extra.get(i));
                    if (!names[i].matches("^[A-Za-z][A-Za-z0-9-]{0,63}$")) throw new ActionException("AD.BAD_ATTRIBUTE", "Invalid attribute name '" + names[i] + "'");
                }
                Attributes more = s.dir.read(r.dn(), names);
                for (String n : names) u.put(n, attrValue(more.get(n)));
            }
            Object uac = attrValue(r.attributes().get("userAccountControl"));
            if (uac != null) u.put("enabled", (Integer.parseInt(String.valueOf(uac)) & ACCOUNTDISABLE) == 0);
            Object lock = attrValue(r.attributes().get("lockoutTime"));
            u.put("locked", lock != null && !"0".equals(String.valueOf(lock)));
            return Map.of("user", u);
        } catch (NamingException e) { throw ad(e); }
    }

    /** Earlier signature, kept for callers written before the attributes option. */
    public Map<String, Object> getUser(ActionContext ctx, String username, String session) { return getUser(ctx, username, session, null); }

    private Map<String, Object> modify(ActionContext ctx, String session, String username, ModificationItem... items) {
        AdSession s = s(ctx, session);
        try { s.dir.modify(findUser(s, username).dn(), items); }
        catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    private int uac(ActionContext ctx, String session, String username) {
        try { return Integer.parseInt(String.valueOf(attrValue(findUser(s(ctx, session), username).attributes().get("userAccountControl")))); }
        catch (NamingException e) { throw ad(e); }
    }

    @Action(id = "enable_user", name = "Enable user", category = "Users", capabilities = "network", description = "Enable a disabled user account.",
            panel = "session, username")
    public Map<String, Object> enable(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(uac(ctx, session, u) & ~ACCOUNTDISABLE))));
    }

    @Action(id = "disable_user", name = "Disable user", category = "Users", capabilities = "network", description = "Disable a user account (for example when someone leaves).",
            panel = "session, username")
    public Map<String, Object> disable(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(uac(ctx, session, u) | ACCOUNTDISABLE))));
    }

    @Action(id = "unlock_user", name = "Unlock user", category = "Users", capabilities = "network", description = "Unlock an account locked by failed sign-ins.",
            panel = "session, username")
    public Map<String, Object> unlock(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("lockoutTime", "0")));
    }

    private static ModificationItem passwordItem(SecureString pw) {
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("unicodePwd", ("\"" + pw.reveal() + "\"").getBytes(StandardCharsets.UTF_16LE)));
    }

    private static ActionException passwordHint(ActionException e) {
        if ("AD.ERROR".equals(e.code()) || "AD.ACCESS_DENIED".equals(e.code()))
            return new ActionException(e.code(), e.getMessage(), "Password changes need an ldaps:// connection and a password that meets the domain policy.");
        return e;
    }

    @Action(id = "reset_password", name = "Reset password", category = "Users", capabilities = "network",
            description = "Set a new password (needs an ldaps:// connection), optionally forcing a change at next sign-in.",
            panel = "session, username, new_password, must_change | unlock")
    public Map<String, Object> resetPassword(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_password", type = OoaType.CREDENTIAL, description = "New password from the vault or a Credential variable.") SecureString pw,
            @Input(name = "must_change", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Require a change at next sign-in.") boolean mustChange,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "unlock", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also unlock the account.") boolean unlock) {
        List<ModificationItem> items = new ArrayList<>(List.of(passwordItem(pw)));
        if (mustChange) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        if (unlock) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("lockoutTime", "0")));
        try { return modify(ctx, session, u, items.toArray(new ModificationItem[0])); }
        catch (ActionException e) { throw passwordHint(e); }
    }

    /** Earlier signature, kept for callers written before the unlock option. */
    public Map<String, Object> resetPassword(ActionContext ctx, String u, SecureString pw, boolean mustChange, String session) { return resetPassword(ctx, u, pw, mustChange, session, false); }

    private Map<String, Object> group(ActionContext ctx, String session, String username, List<Object> more, String group, int op) {
        AdSession s = s(ctx, session);
        List<String> users = new ArrayList<>(List.of(username));
        if (more != null) for (Object o : more) { String n = Values.text(o).strip(); if (!n.isEmpty()) users.add(n); }
        int changed = 0;
        try {
            String groupDn = groupDn(s, group);
            for (String user : users) {
                String userDn = findUser(s, user).dn();
                try { s.dir.modify(groupDn, new ModificationItem[]{new ModificationItem(op, new BasicAttribute("member", userDn))}); changed++; }
                catch (AttributeInUseException e) { ctx.info(user + " is already a member"); }
                catch (NoSuchAttributeException e) { ctx.info(user + " was not a member"); }
            }
        } catch (NamingException e) { throw ad(e); }
        return Map.of("changed", BigDecimal.valueOf(changed));
    }

    @Action(id = "add_to_group", name = "Add users to group", category = "Groups", capabilities = "network", description = "Add one or more users to a group (no error for users already in it).",
            panel = "session, username, group_dn, more_usernames")
    @Output(name = "changed", type = OoaType.NUMBER, description = "How many users were added (users already in the group are not counted).")
    public Map<String, Object> addToGroup(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "group_dn", type = OoaType.STRING, example = "CN=Finance,OU=Groups,DC=corp,DC=example,DC=com", description = "Group distinguished name, or its sAMAccountName or common name.") String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "more_usernames", type = OoaType.LIST, required = false, description = "More users (sAMAccountName or UPN) to add in the same step.") List<Object> more) {
        return group(ctx, session, u, more, g, DirContext.ADD_ATTRIBUTE);
    }

    /** Earlier signature, kept for callers written before more_usernames. */
    public Map<String, Object> addToGroup(ActionContext ctx, String u, String g, String session) { return addToGroup(ctx, u, g, session, null); }

    @Action(id = "remove_from_group", name = "Remove users from group", category = "Groups", capabilities = "network", description = "Remove one or more users from a group (no error for users not in it).",
            panel = "session, username, group_dn, more_usernames")
    @Output(name = "changed", type = OoaType.NUMBER, description = "How many users were removed.")
    public Map<String, Object> removeFromGroup(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "group_dn", type = OoaType.STRING, description = "Group distinguished name, or its sAMAccountName or common name.") String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "more_usernames", type = OoaType.LIST, required = false, description = "More users (sAMAccountName or UPN) to remove in the same step.") List<Object> more) {
        return group(ctx, session, u, more, g, DirContext.REMOVE_ATTRIBUTE);
    }

    /** Earlier signature, kept for callers written before more_usernames. */
    public Map<String, Object> removeFromGroup(ActionContext ctx, String u, String g, String session) { return removeFromGroup(ctx, u, g, session, null); }

    @Action(id = "set_attribute", name = "Set user property", category = "Users", capabilities = "network", description = "Change one attribute of a user, e.g. title or telephoneNumber; values can be replaced, added or removed.",
            panel = "session, username, attribute, value | mode")
    public Map<String, Object> setAttribute(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "New value; empty clears it.") String value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"replace\"", enumValues = {"replace", "add", "remove"},
                    description = "replace: set the value; add: add one more value to a multi-valued attribute; remove: remove this one value.") String mode) {
        return modify(ctx, session, u, setItem(attr, value, mode));
    }

    /** Earlier signature, kept for callers written before the mode option. */
    public Map<String, Object> setAttribute(ActionContext ctx, String u, String attr, String value, String session) { return setAttribute(ctx, u, attr, value, session, "replace"); }

    @Action(id = "disconnect", name = "Disconnect", category = "Directory", description = "Close the directory session.",
            panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof AdSession a) try { a.close(); } catch (NamingException ignored) { }
        return Map.of();
    }

    // ---- Shared helpers for users, groups, computers and organizational units ----

    private static final String D_SESSION = "Session name.";
    private static final String D_GROUP = "Group distinguished name, or its sAMAccountName or common name.";
    private static final String D_COMPUTER = "Computer distinguished name, or its name (with or without the trailing $).";
    private static final String D_OU = "Organizational unit distinguished name, or its name when unique.";
    private static final String D_CONTAINER = "Distinguished name of the organizational unit or container to create it in.";

    static boolean isDn(String v) {
        if (v == null || !v.contains("=")) return false;
        try { return new LdapName(v).size() > 0; } catch (InvalidNameException e) { return false; }
    }

    private static String container(String dn) {
        if (!isDn(dn)) throw new ActionException("AD.BAD_DN", "Not a distinguished name: '" + dn + "'", "Example: OU=Staff,DC=corp,DC=example,DC=com.");
        return dn;
    }

    private static String name(String n, String what) {
        if (n == null || n.isBlank()) throw new ActionException("AD.BAD_NAME", "The " + what + " name is empty");
        if (n.length() > 64) throw new ActionException("AD.BAD_NAME", "The " + what + " name is longer than 64 characters");
        return n.strip();
    }

    /** Resolves a DN or a name to one DN, failing when nothing or more than one object matches. */
    private static String resolve(AdSession s, String idOrDn, String filter, String what) throws NamingException {
        if (isDn(idOrDn)) return idOrDn;
        if (idOrDn == null || idOrDn.isBlank()) throw new ActionException("AD.BAD_NAME", "No " + what + " given");
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(2);
        sc.setReturningAttributes(new String[]{"cn"});
        List<Entry> found = new ArrayList<>();
        boolean more = false;
        try { s.dir.search(s.baseDn, filter, sc, found::add); } catch (SizeLimitExceededException e) { more = true; }
        String code = what.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (found.isEmpty()) throw new ActionException("AD." + code + "_NOT_FOUND", what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1) + " '" + idOrDn + "' not found", "Check the name or give the distinguished name.");
        if (found.size() > 1 || more) throw new ActionException("AD.AMBIGUOUS", "More than one " + what + " is named '" + idOrDn + "'", "Give the distinguished name instead.");
        return found.get(0).dn();
    }

    private static String groupDn(AdSession s, String g) throws NamingException {
        return resolve(s, g, "(&(objectClass=group)(|(sAMAccountName=" + escape(g) + ")(cn=" + escape(g) + ")))", "group");
    }
    private static String computerDn(AdSession s, String c) throws NamingException {
        String bare = c == null ? "" : c.replaceFirst("\\$$", "");
        return resolve(s, c, "(&(objectClass=computer)(|(sAMAccountName=" + escape(bare) + "$)(cn=" + escape(bare) + ")))", "computer");
    }
    private static String ouDn(AdSession s, String o) throws NamingException {
        return resolve(s, o, "(&(objectClass=organizationalUnit)(ou=" + escape(o) + "))", "organizational unit");
    }

    /** Like {@link #setItem(String, Object)}, but mode add or remove changes single values of a multi-valued attribute. */
    static ModificationItem setItem(String attr, Object value, String mode) {
        String m = mode == null || mode.isBlank() ? "replace" : mode;
        if (m.equals("replace")) return setItem(attr, value);
        if (!m.equals("add") && !m.equals("remove")) throw new ActionException("AD.BAD_MODE", "Unknown mode '" + mode + "'", "Use replace, add or remove.");
        ModificationItem base = setItem(attr, value);
        if (base.getModificationOp() == DirContext.REMOVE_ATTRIBUTE) throw new ActionException("AD.NO_VALUE", "Give the value to " + m);
        return new ModificationItem(m.equals("add") ? DirContext.ADD_ATTRIBUTE : DirContext.REMOVE_ATTRIBUTE, base.getAttribute());
    }

    static ModificationItem setItem(String attr, Object value) {
        if (attr == null || !attr.matches("^[A-Za-z][A-Za-z0-9-]{0,63}$")) throw new ActionException("AD.BAD_ATTRIBUTE", "Invalid attribute name '" + attr + "'", "Example: description, telephoneNumber or managedBy.");
        if (attr.equalsIgnoreCase("unicodePwd") || attr.equalsIgnoreCase("userPassword"))
            throw new ActionException("AD.USE_RESET_PASSWORD", "Use Reset password to change passwords");
        if (value == null || (value instanceof String str && str.isEmpty()) || (value instanceof List<?> l && l.isEmpty()))
            return new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(attr));
        BasicAttribute a = new BasicAttribute(attr);
        if (value instanceof List<?> l) for (Object v : l) a.add(Values.text(v)); else a.add(Values.text(value));
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, a);
    }

    private static Map<String, Object> properties(AdSession s, String dn, List<Object> attrs) throws NamingException {
        String[] names = null;
        if (attrs != null && !attrs.isEmpty()) { names = new String[attrs.size()]; for (int i = 0; i < names.length; i++) names[i] = Values.text(attrs.get(i)); }
        Attributes a = s.dir.read(dn, names);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dn", dn);
        if (names != null) for (String n : names) out.put(n, attrValue(a.get(n)));
        else {
            List<String> ids = new ArrayList<>();
            NamingEnumeration<String> e = a.getIDs();
            while (e.hasMore()) ids.add(e.next());
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            for (String n : ids) out.put(n, attrValue(a.get(n)));
        }
        return out;
    }

    /** Changes the leftmost RDN value, keeping its type and parent. Returns the new DN. */
    static String renamedDn(String dn, String newName) throws InvalidNameException {
        LdapName n = new LdapName(dn);
        Rdn rdn = n.getRdn(n.size() - 1);
        String parent = n.getPrefix(n.size() - 1).toString();
        return rdn.getType() + "=" + Rdn.escapeValue(newName) + (parent.isEmpty() ? "" : "," + parent);
    }

    /** Keeps the leftmost RDN and puts it under a new parent. Returns the new DN. */
    static String movedDn(String dn, String newParent) throws InvalidNameException {
        LdapName n = new LdapName(dn);
        return n.getRdn(n.size() - 1).toString() + "," + newParent;
    }

    private interface DirWork { Map<String, Object> run(AdSession s) throws NamingException; }
    private static Map<String, Object> work(ActionContext ctx, String session, DirWork w) {
        try { return w.run(s(ctx, session)); } catch (NamingException e) { throw ad(e); }
    }

    private static Map<String, Object> rename(AdSession s, String dn, String newName, String what) throws NamingException {
        String to = renamedDn(dn, name(newName, what));
        s.dir.rename(dn, to);
        return Map.of("dn", to);
    }

    private static Map<String, Object> move(AdSession s, String dn, String target) throws NamingException {
        String to = movedDn(dn, container(target));
        s.dir.rename(dn, to);
        return Map.of("dn", to);
    }

    private static Map<String, Object> set(AdSession s, String dn, String attr, Object value) throws NamingException {
        s.dir.modify(dn, new ModificationItem[]{setItem(attr, value)});
        return Map.of();
    }

    // ---- Users ----

    @Action(id = "create_user", name = "Create user", category = "Users", capabilities = "network",
            description = "Create a user account in an organizational unit, optionally with a first password, and enable it.",
            panel = "session, username, user_principal_name, first_name, last_name, display_name, initials, user_description, email, department, title, enabled, [Set a first password: password], must_change_password, password_never_expires, container_dn | attributes")
    @Output(name = "dn", type = OoaType.STRING, description = "Distinguished name of the new user.")
    public Map<String, Object> createUser(ActionContext ctx,
            @Input(name = "container_dn", type = OoaType.STRING, example = "OU=Staff,DC=corp,DC=example,DC=com", description = D_CONTAINER) String parent,
            @Input(name = "username", type = OoaType.STRING, example = "jdoe", description = "Sign-in name (sAMAccountName, up to 20 characters).") String username,
            @Input(name = "first_name", type = OoaType.STRING, required = false, description = "Given name.") String first,
            @Input(name = "last_name", type = OoaType.STRING, required = false, description = "Surname.") String last,
            @Input(name = "display_name", type = OoaType.STRING, required = false, description = "Display name; also the object name. First and last name when empty.") String display,
            @Input(name = "user_principal_name", type = OoaType.STRING, required = false, example = "jdoe@corp.example.com", description = "User principal name (UPN).") String upn,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "First password from the vault (needs an ldaps:// connection); the account stays disabled without one.") SecureString password,
            @Input(name = "enabled", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Enable the account once the password is set.") boolean enabled,
            @Input(name = "must_change_password", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Require a password change at first sign-in.") boolean mustChange,
            @Input(name = "attributes", type = OoaType.DICTIONARY, required = false, description = "More attributes, e.g. {\"mail\": \"jdoe@example.com\", \"department\": \"Finance\"}.") Map<String, Object> extra,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session,
            @Input(name = "initials", type = OoaType.STRING, required = false, description = "Initials (up to 6 characters).") String initials,
            @Input(name = "user_description", type = OoaType.STRING, required = false, description = "Description shown in the directory.") String description,
            @Input(name = "password_never_expires", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the account with a password that never expires (must_change_password is then ignored by the directory).") boolean neverExpires,
            @Input(name = "email", type = OoaType.STRING, required = false, example = "jdoe@corp.example.com", description = "Email address (stored in mail).") String email,
            @Input(name = "department", type = OoaType.STRING, required = false, description = "Department.") String department,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Job title.") String title) {
        if (initials != null && initials.strip().length() > 6) throw new ActionException("AD.BAD_NAME", "Initials can have at most 6 characters");
        if (email != null && !email.isBlank() && !email.strip().matches("^[^@\\s]+@[^@\\s]+$"))
            throw new ActionException("AD.BAD_EMAIL", "Not an email address: '" + email + "'", "Example: jdoe@corp.example.com.");
        if (username == null || !username.matches("^[^\"/\\\\\\[\\]:;|=,+*?<>@]{1,20}$"))
            throw new ActionException("AD.BAD_NAME", "Invalid user name '" + username + "'", "Up to 20 characters, without \" / \\ [ ] : ; | = , + * ? < > @.");
        String cn = display != null && !display.isBlank() ? display.strip()
                : ((first == null ? "" : first.strip()) + " " + (last == null ? "" : last.strip())).strip();
        if (cn.isEmpty()) cn = username;
        String dn = "CN=" + Rdn.escapeValue(name(cn, "user")) + "," + container(parent);
        BasicAttributes a = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass");
        for (String c : List.of("top", "person", "organizationalPerson", "user")) oc.add(c);
        a.put(oc);
        a.put("cn", cn);
        a.put("sAMAccountName", username);
        if (first != null && !first.isBlank()) a.put("givenName", first.strip());
        if (last != null && !last.isBlank()) a.put("sn", last.strip());
        if (display != null && !display.isBlank()) a.put("displayName", display.strip()); else a.put("displayName", cn);
        if (upn != null && !upn.isBlank()) a.put("userPrincipalName", upn.strip());
        if (extra != null) for (var e : extra.entrySet()) {
            ModificationItem m = setItem(e.getKey(), e.getValue());
            if (m.getModificationOp() == DirContext.REPLACE_ATTRIBUTE) a.put(m.getAttribute());
        }
        if (initials != null && !initials.isBlank()) a.put("initials", initials.strip());
        if (description != null && !description.isBlank()) a.put("description", description);
        if (email != null && !email.isBlank()) a.put("mail", email.strip());
        if (department != null && !department.isBlank()) a.put("department", department.strip());
        if (title != null && !title.isBlank()) a.put("title", title.strip());
        int extraFlags = neverExpires ? DONT_EXPIRE_PASSWORD : 0;
        a.put("userAccountControl", String.valueOf(NORMAL_ACCOUNT | ACCOUNTDISABLE | extraFlags));
        AdSession s = s(ctx, session);
        try { s.dir.create(dn, a); } catch (NamingException e) { throw ad(e); }
        List<ModificationItem> after = new ArrayList<>();
        if (password != null) after.add(passwordItem(password));
        if (mustChange && !neverExpires) after.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        if (enabled && password != null) after.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(NORMAL_ACCOUNT | extraFlags))));
        if (enabled && password == null) ctx.warn("The account " + username + " was left disabled because no password was given.");
        if (!after.isEmpty()) {
            try { s.dir.modify(dn, after.toArray(new ModificationItem[0])); }
            catch (NamingException e) {
                ActionException x = passwordHint(ad(e));
                throw new ActionException(x.code(), "The user was created (disabled) but the password or options could not be set: " + x.getMessage(), x.suggestedFix());
            }
        }
        return Map.of("dn", dn);
    }

    /** Earlier signature, kept for callers written before initials, description and password_never_expires. */
    public Map<String, Object> createUser(ActionContext ctx, String parent, String username, String first, String last, String display, String upn,
                                          SecureString password, boolean enabled, boolean mustChange, Map<String, Object> extra, String session) {
        return createUser(ctx, parent, username, first, last, display, upn, password, enabled, mustChange, extra, session, null, null, false);
    }

    /** Earlier signature, kept for callers written before email, department and title. */
    public Map<String, Object> createUser(ActionContext ctx, String parent, String username, String first, String last, String display, String upn,
                                          SecureString password, boolean enabled, boolean mustChange, Map<String, Object> extra, String session,
                                          String initials, String description, boolean neverExpires) {
        return createUser(ctx, parent, username, first, last, display, upn, password, enabled, mustChange, extra, session, initials, description, neverExpires, null, null, null);
    }

    @Action(id = "update_user_details", name = "Update user details", category = "Users", capabilities = "network",
            description = "Change several details of a user in one step (names, description, office, phone, email, title, department, manager and any other attributes); empty inputs stay as they are.",
            panel = "session, username, first_name, last_name, display_name, initials, email, user_description, department, title | office, telephone, company, manager, attributes")
    @Output(name = "changed", type = OoaType.LIST, description = "Names of the attributes that were changed.")
    public Map<String, Object> updateUserDetails(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "first_name", type = OoaType.STRING, required = false, description = "Given name.") String first,
            @Input(name = "last_name", type = OoaType.STRING, required = false, description = "Surname.") String last,
            @Input(name = "initials", type = OoaType.STRING, required = false, description = "Initials.") String initials,
            @Input(name = "display_name", type = OoaType.STRING, required = false, description = "Display name.") String display,
            @Input(name = "user_description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "office", type = OoaType.STRING, required = false, description = "Office (physicalDeliveryOfficeName).") String office,
            @Input(name = "telephone", type = OoaType.STRING, required = false, description = "Telephone number.") String phone,
            @Input(name = "email", type = OoaType.STRING, required = false, description = "Email address (mail).") String email,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Job title.") String title,
            @Input(name = "department", type = OoaType.STRING, required = false, description = "Department.") String department,
            @Input(name = "company", type = OoaType.STRING, required = false, description = "Company.") String company,
            @Input(name = "manager", type = OoaType.STRING, required = false, description = "Manager: a distinguished name, or a sAMAccountName or UPN that is looked up.") String manager,
            @Input(name = "attributes", type = OoaType.DICTIONARY, required = false, description = "Any other attributes, e.g. {\"employeeID\": \"E123\"}; an empty value clears the attribute.") Map<String, Object> extra,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            Entry e = findUser(s, u);
            Map<String, Object> want = new LinkedHashMap<>();
            String[][] named = {{"givenName", first}, {"sn", last}, {"initials", initials}, {"displayName", display}, {"description", description},
                    {"physicalDeliveryOfficeName", office}, {"telephoneNumber", phone}, {"mail", email}, {"title", title}, {"department", department}, {"company", company}};
            for (String[] n : named) if (n[1] != null && !n[1].isBlank()) want.put(n[0], n[1].strip());
            if (manager != null && !manager.isBlank()) want.put("manager", isDn(manager) ? manager.strip() : findUser(s, manager.strip()).dn());
            if (extra != null) want.putAll(extra);
            if (want.isEmpty()) throw new ActionException("AD.NO_CHANGES", "No details to change", "Fill at least one input.");
            List<ModificationItem> items = new ArrayList<>();
            for (var w : want.entrySet()) items.add(setItem(w.getKey(), w.getValue()));
            s.dir.modify(e.dn(), items.toArray(new ModificationItem[0]));
            return Map.of("changed", new ArrayList<Object>(want.keySet()));
        });
    }

    @Action(id = "delete_user", name = "Delete user", category = "Users", capabilities = "network", description = "Delete a user account.",
            panel = "session, username")
    public Map<String, Object> deleteUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(findUser(s, u).dn()); return Map.of(); });
    }

    @Action(id = "rename_user", name = "Rename user", category = "Users", capabilities = "network",
            description = "Change the object name of a user (optionally also its display name), its sign-in name (sAMAccountName and the part of the UPN before @), or both.",
            panel = "session, username, new_name, rename_target | update_display_name, new_sign_in_name")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_name", type = OoaType.STRING, description = "New object name (common name), e.g. Jane Doe; also the new sign-in name when only the sign-in name is renamed and new_sign_in_name is empty.") String newName,
            @Input(name = "update_display_name", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also set the display name to the new name (when the object name changes).") boolean display,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session,
            @Input(name = "rename_target", type = OoaType.STRING, required = false, defaultValue = "\"object\"", enumValues = {"object", "sign_in", "both"},
                    description = "object: the object name only (the sign-in name is kept); sign_in: the sign-in name only; both: object name and sign-in name.") String target,
            @Input(name = "new_sign_in_name", type = OoaType.STRING, required = false, example = "jdoe2",
                    description = "New sign-in name (sAMAccountName, up to 20 characters); new_name is used when empty.") String newSignIn) {
        String t = target == null || target.isBlank() ? "object" : target.strip().toLowerCase(Locale.ROOT);
        if (!t.equals("object") && !t.equals("sign_in") && !t.equals("both"))
            throw new ActionException("AD.BAD_TARGET", "Unknown rename target '" + target + "'", "Use object, sign_in or both.");
        boolean object = !t.equals("sign_in"), signIn = !t.equals("object");
        String sam = signIn ? (newSignIn != null && !newSignIn.isBlank() ? newSignIn.strip() : newName == null ? "" : newName.strip()) : null;
        if (signIn && !sam.matches("^[^\"/\\\\\\[\\]:;|=,+*?<>@]{1,20}$"))
            throw new ActionException("AD.BAD_NAME", "Invalid sign-in name '" + sam + "'", "Up to 20 characters, without \" / \\ [ ] : ; | = , + * ? < > @.");
        return work(ctx, session, s -> {
            Entry e = findUser(s, u);
            String dn = e.dn();
            if (signIn) {
                List<ModificationItem> items = new ArrayList<>();
                items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("sAMAccountName", sam)));
                Object upn = attrValue(e.attributes().get("userPrincipalName"));
                if (upn != null && String.valueOf(upn).contains("@")) {
                    String suffix = String.valueOf(upn).substring(String.valueOf(upn).lastIndexOf('@'));
                    items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userPrincipalName", sam + suffix)));
                }
                s.dir.modify(dn, items.toArray(new ModificationItem[0]));
            }
            if (!object) return Map.of("dn", dn);
            Map<String, Object> r = rename(s, dn, newName, "user");
            if (display) s.dir.modify((String) r.get("dn"), new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("displayName", newName.strip()))});
            return r;
        });
    }

    /** Earlier signature, kept for callers written before rename_target and new_sign_in_name. */
    public Map<String, Object> renameUser(ActionContext ctx, String u, String newName, boolean display, String session) {
        return renameUser(ctx, u, newName, display, session, "object", null);
    }

    @Action(id = "move_user", name = "Move user", category = "Users", capabilities = "network", description = "Move a user to another organizational unit.",
            panel = "username, target_ou_dn, session")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "target_ou_dn", type = OoaType.STRING, description = "Distinguished name of the destination organizational unit.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, findUser(s, u).dn(), target));
    }

    @Action(id = "set_password", name = "Set password", category = "Users", capabilities = "network",
            description = "Set a user's password as an administrator (needs an ldaps:// connection), optionally unlocking the account.",
            panel = "session, username, new_password, must_change | unlock")
    public Map<String, Object> setPassword(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_password", type = OoaType.CREDENTIAL, description = "New password from the vault or a Credential variable.") SecureString pw,
            @Input(name = "must_change", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Require a change at next sign-in.") boolean mustChange,
            @Input(name = "unlock", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also unlock the account.") boolean unlock,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        List<ModificationItem> items = new ArrayList<>(List.of(passwordItem(pw)));
        if (mustChange) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        if (unlock) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("lockoutTime", "0")));
        try { return modify(ctx, session, u, items.toArray(new ModificationItem[0])); }
        catch (ActionException e) { throw passwordHint(e); }
    }

    @Action(id = "update_account_options", name = "Update account options", category = "Users", capabilities = "network",
            description = "Turn account options on or off: enabled, password never expires, and must change password at next sign-in; empty options stay as they are.",
            panel = "session, username, enabled, must_change_password, password_never_expires, cannot_change_password | smart_card_required, not_delegated, password_not_required, no_kerberos_preauth, account_expires")
    @Output(name = "user_account_control", type = OoaType.NUMBER, description = "The account control flags after the change.")
    public Map<String, Object> accountOptions(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "enabled", type = OoaType.BOOLEAN, required = false, description = "Enable (true) or disable (false) the account.") Boolean enabled,
            @Input(name = "password_never_expires", type = OoaType.BOOLEAN, required = false, description = "Whether the password never expires.") Boolean neverExpires,
            @Input(name = "must_change_password", type = OoaType.BOOLEAN, required = false, description = "Whether a password change is required at next sign-in.") Boolean mustChange,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session,
            @Input(name = "smart_card_required", type = OoaType.BOOLEAN, required = false, description = "Whether a smart card is required for interactive sign-in.") Boolean smartCard,
            @Input(name = "not_delegated", type = OoaType.BOOLEAN, required = false, description = "Whether the account is sensitive and cannot be delegated.") Boolean notDelegated,
            @Input(name = "password_not_required", type = OoaType.BOOLEAN, required = false, description = "Whether the account may have an empty password (normally false).") Boolean pwNotRequired,
            @Input(name = "no_kerberos_preauth", type = OoaType.BOOLEAN, required = false, description = "Whether Kerberos pre-authentication is not required (normally false).") Boolean noPreauth,
            @Input(name = "account_expires", type = OoaType.STRING, required = false, example = "2026-12-31",
                    description = "Last day the account can be used (yyyy-MM-dd, the account ends at the start of the next day UTC), or never.") String expires,
            @Input(name = "cannot_change_password", type = OoaType.BOOLEAN, required = false,
                    description = "Whether the user is stopped from changing their own password (Active Directory only; changes the object's permissions).") Boolean cannotChange) {
        Long expiresValue = accountExpires(expires);
        return work(ctx, session, s -> {
            Entry e = findUser(s, u);
            Object raw = attrValue(e.attributes().get("userAccountControl"));
            int before = raw == null ? NORMAL_ACCOUNT : Integer.parseInt(String.valueOf(raw)), flags = before;
            if (enabled != null) flags = enabled ? flags & ~ACCOUNTDISABLE : flags | ACCOUNTDISABLE;
            if (neverExpires != null) flags = neverExpires ? flags | DONT_EXPIRE_PASSWORD : flags & ~DONT_EXPIRE_PASSWORD;
            flags = flag(flags, smartCard, SMARTCARD_REQUIRED);
            flags = flag(flags, notDelegated, NOT_DELEGATED);
            flags = flag(flags, pwNotRequired, PASSWD_NOTREQD);
            flags = flag(flags, noPreauth, DONT_REQ_PREAUTH);
            List<ModificationItem> items = new ArrayList<>();
            if (flags != before) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(flags))));
            if (mustChange != null) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", mustChange ? "0" : "-1")));
            if (expiresValue != null) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("accountExpires", String.valueOf(expiresValue))));
            if (!items.isEmpty()) s.dir.modify(e.dn(), items.toArray(new ModificationItem[0]));
            if (cannotChange != null) {
                byte[] sd = s.dir.readSecurity(e.dn());
                if (sd == null) throw new ActionException("AD.NO_SECURITY_DESCRIPTOR", "Cannot read the permissions of " + e.dn(),
                        "The bind account needs permission to read and change permissions; this option works on Active Directory only.");
                s.dir.writeSecurity(e.dn(), withChangePassword(sd, cannotChange));
            }
            return Map.of("user_account_control", BigDecimal.valueOf(flags));
        });
    }

    /** Earlier signature, kept for callers written before the extra account options. */
    public Map<String, Object> accountOptions(ActionContext ctx, String u, Boolean enabled, Boolean neverExpires, Boolean mustChange, String session) {
        return accountOptions(ctx, u, enabled, neverExpires, mustChange, session, null, null, null, null, null);
    }

    /** Earlier signature, kept for callers written before cannot_change_password. */
    public Map<String, Object> accountOptions(ActionContext ctx, String u, Boolean enabled, Boolean neverExpires, Boolean mustChange, String session,
                                              Boolean smartCard, Boolean notDelegated, Boolean pwNotRequired, Boolean noPreauth, String expires) {
        return accountOptions(ctx, u, enabled, neverExpires, mustChange, session, smartCard, notDelegated, pwNotRequired, noPreauth, expires, null);
    }

    // ---- "User cannot change password": object ACEs for the Change Password extended right in the DACL ----

    /** Change Password extended right {AB721A53-1E2F-11D0-9819-00AA0040529B} in its binary (little-endian) GUID form. */
    static final byte[] CHANGE_PASSWORD_GUID = {0x53, 0x1A, 0x72, (byte) 0xAB, 0x2F, 0x1E, (byte) 0xD0, 0x11, (byte) 0x98, 0x19, 0x00, (byte) 0xAA, 0x00, 0x40, 0x52, (byte) 0x9B};
    static final byte[] SID_EVERYONE = {1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0};   // S-1-1-0
    static final byte[] SID_SELF = {1, 1, 0, 0, 0, 0, 0, 5, 10, 0, 0, 0};       // S-1-5-10
    static final int ACCESS_ALLOWED_OBJECT = 5, ACCESS_DENIED_OBJECT = 6, INHERITED_ACE = 0x10, DS_CONTROL_ACCESS = 0x100;

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    private static int u32(byte[] b, int o) { return u16(b, o) | u16(b, o + 2) << 16; }
    private static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void put32(byte[] b, int o, int v) { put16(b, o, v); put16(b, o + 2, v >>> 16); }
    private static int sidLength(byte[] b, int o) { return 8 + 4 * (b[o + 1] & 0xFF); }

    private static ActionException badSd() {
        return new ActionException("AD.BAD_SECURITY_DESCRIPTOR", "The user's security descriptor could not be read", "Check that the server is Active Directory.");
    }

    /** One explicit object ACE for the change-password right. */
    static byte[] changePasswordAce(int type, byte[] sid) {
        byte[] a = new byte[12 + 16 + sid.length];
        a[0] = (byte) type;
        put16(a, 2, a.length);
        put32(a, 4, DS_CONTROL_ACCESS);
        put32(a, 8, 1);                                          // ACE_OBJECT_TYPE_PRESENT
        System.arraycopy(CHANGE_PASSWORD_GUID, 0, a, 12, 16);
        System.arraycopy(sid, 0, a, 28, sid.length);
        return a;
    }

    /** True when an ACE is an explicit allow or deny object ACE for the change-password right given to Everyone or SELF. */
    static boolean isChangePasswordAce(byte[] ace) {
        int type = ace[0] & 0xFF;
        if ((type != ACCESS_ALLOWED_OBJECT && type != ACCESS_DENIED_OBJECT) || (ace[1] & INHERITED_ACE) != 0 || ace.length < 12) return false;
        int flags = u32(ace, 8), o = 12;
        if ((flags & 1) == 0 || ace.length < o + 16 || !Arrays.equals(Arrays.copyOfRange(ace, o, o + 16), CHANGE_PASSWORD_GUID)) return false;
        o += 16;
        if ((flags & 2) != 0) o += 16;
        if (ace.length < o + 8) return false;
        byte[] sid = Arrays.copyOfRange(ace, o, Math.min(ace.length, o + sidLength(ace, o)));
        return Arrays.equals(sid, SID_EVERYONE) || Arrays.equals(sid, SID_SELF);
    }

    /**
     * Returns the security descriptor with the change-password ACEs for Everyone and SELF set to deny (cannot change) or allow.
     * Other ACEs are kept in order; deny ACEs go first and allow ACEs after the explicit deny ACEs, the canonical order.
     */
    static byte[] withChangePassword(byte[] sd, boolean cannotChange) {
        if (sd.length < 20 || sd[0] != 1) throw badSd();
        int control = u16(sd, 2), oOwner = u32(sd, 4), oGroup = u32(sd, 8), oSacl = u32(sd, 12), oDacl = u32(sd, 16);
        List<byte[]> aces = new ArrayList<>();
        int revision = 4;
        if (oDacl != 0) {
            if (oDacl + 8 > sd.length) throw badSd();
            revision = Math.max(revision, sd[oDacl] & 0xFF);
            int count = u16(sd, oDacl + 4), p = oDacl + 8;
            for (int i = 0; i < count; i++) {
                if (p + 4 > sd.length) throw badSd();
                int size = u16(sd, p + 2);
                if (size < 4 || p + size > sd.length) throw badSd();
                byte[] ace = Arrays.copyOfRange(sd, p, p + size);
                if (!isChangePasswordAce(ace)) aces.add(ace);
                p += size;
            }
        }
        int type = cannotChange ? ACCESS_DENIED_OBJECT : ACCESS_ALLOWED_OBJECT;
        int at = 0;
        if (!cannotChange) while (at < aces.size() && (aces.get(at)[1] & INHERITED_ACE) == 0 && ((aces.get(at)[0] & 0xFF) == 1 || (aces.get(at)[0] & 0xFF) == ACCESS_DENIED_OBJECT)) at++;
        aces.add(at, changePasswordAce(type, SID_SELF));
        aces.add(at, changePasswordAce(type, SID_EVERYONE));
        int aclSize = 8;
        for (byte[] a : aces) aclSize += a.length;
        byte[] acl = new byte[aclSize];
        acl[0] = (byte) revision;
        put16(acl, 2, aclSize);
        put16(acl, 4, aces.size());
        int p = 8;
        for (byte[] a : aces) { System.arraycopy(a, 0, acl, p, a.length); p += a.length; }
        // rebuild the self-relative descriptor: header, owner, group, SACL, then the new DACL
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] head = new byte[20];
        head[0] = 1;
        put16(head, 2, control | 0x8004);                      // SE_SELF_RELATIVE and SE_DACL_PRESENT
        out.writeBytes(head);
        int[] offsets = {oOwner, oGroup, oSacl};
        for (int i = 0; i < 3; i++) {
            if (offsets[i] == 0) continue;
            int len = i < 2 ? sidLength(sd, offsets[i]) : u16(sd, offsets[i] + 2);
            if (offsets[i] + len > sd.length) throw badSd();
            put32(head, 4 + 4 * i, out.size());
            out.write(sd, offsets[i], len);
        }
        put32(head, 16, out.size());
        out.writeBytes(acl);
        byte[] result = out.toByteArray();
        System.arraycopy(head, 0, result, 0, 20);
        return result;
    }

    private static final int PASSWD_NOTREQD = 0x20, SMARTCARD_REQUIRED = 0x40000, NOT_DELEGATED = 0x100000, DONT_REQ_PREAUTH = 0x400000;

    private static int flag(int flags, Boolean on, int bit) { return on == null ? flags : on ? flags | bit : flags & ~bit; }

    /** accountExpires value: 0 for never, else 100-ns intervals since 1601-01-01 UTC at the start of the day after the given date. */
    static Long accountExpires(String v) {
        if (v == null || v.isBlank()) return null;
        if (v.strip().equalsIgnoreCase("never")) return 0L;
        java.time.LocalDate d;
        try { d = java.time.LocalDate.parse(v.strip()); }
        catch (java.time.format.DateTimeParseException e) { throw new ActionException("AD.BAD_DATE", "Invalid date '" + v + "'", "Use yyyy-MM-dd, e.g. 2026-12-31, or never."); }
        long epochSeconds = d.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        return (epochSeconds + 11_644_473_600L) * 10_000_000L;
    }

    // ---- Groups ----

    @Action(id = "get_group_members", name = "Get group members", category = "Groups", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the members of a group, optionally including members of nested groups.",
            panel = "session, group, member_type, recursive | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: dn, name, sAMAccountName, type, mail.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of members.")
    public Map<String, Object> groupMembers(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Include members of nested groups (Active Directory only).") boolean recursive,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session,
            @Input(name = "member_type", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "user", "group", "computer"}, description = "Only return members of this kind.") String memberType) {
        String typeFilter = switch (memberType == null ? "all" : memberType) {
            case "all" -> "";
            case "user" -> "(objectClass=user)(!(objectClass=computer))";
            case "group" -> "(objectClass=group)";
            case "computer" -> "(objectClass=computer)";
            default -> throw new ActionException("AD.BAD_TYPE", "Unknown member type '" + memberType + "'", "Use all, user, group or computer.");
        };
        return work(ctx, session, s -> {
            String dn = groupDn(s, g);
            String member = "(memberOf" + (recursive ? ":1.2.840.113556.1.4.1941:" : "") + "=" + escape(dn) + ")";
            String filter = typeFilter.isEmpty() ? member : "(&" + member + typeFilter + ")";
            SearchControls sc = new SearchControls(SearchControls.SUBTREE_SCOPE, max, 30000, new String[]{"cn", "sAMAccountName", "objectClass", "mail"}, false, false);
            List<Object> rows = new ArrayList<>();
            try {
                s.dir.search(s.baseDn, filter, sc, e -> {
                    try {
                        Attribute oc = e.attributes().get("objectClass");
                        Object type = oc == null || oc.size() == 0 ? null : oc.get(oc.size() - 1);
                        rows.add(new ArrayList<>(Arrays.asList(e.dn(), attrValue(e.attributes().get("cn")), attrValue(e.attributes().get("sAMAccountName")), type == null ? null : String.valueOf(type), attrValue(e.attributes().get("mail")))));
                    } catch (NamingException x) { throw new IllegalStateException(x); }
                });
            } catch (SizeLimitExceededException e) { ctx.warn("Stopped at " + max + " members"); }
            if (rows.isEmpty() && !recursive) {
                // directories that do not keep memberOf (OpenLDAP without the overlay, test servers): read the group's own member list
                Attribute members = s.dir.read(dn, new String[]{"member"}).get("member");
                for (int i = 0; members != null && i < members.size() && rows.size() < max; i++) {
                    String mdn = String.valueOf(members.get(i));
                    Attributes a = s.dir.read(mdn, new String[]{"cn", "sAMAccountName", "objectClass", "mail"});
                    Attribute oc = a.get("objectClass");
                    Set<String> classes = new HashSet<>();
                    for (int k = 0; oc != null && k < oc.size(); k++) classes.add(String.valueOf(oc.get(k)).toLowerCase(Locale.ROOT));
                    boolean ok = switch (memberType == null ? "all" : memberType) {
                        case "user" -> classes.contains("user") && !classes.contains("computer");
                        case "group" -> classes.contains("group");
                        case "computer" -> classes.contains("computer");
                        default -> true;
                    };
                    if (!ok) continue;
                    Object type = oc == null || oc.size() == 0 ? null : oc.get(oc.size() - 1);
                    rows.add(new ArrayList<>(Arrays.asList(mdn, attrValue(a.get("cn")), attrValue(a.get("sAMAccountName")), type == null ? null : String.valueOf(type), attrValue(a.get("mail")))));
                }
            }
            return Map.of("table", Values.table(List.of("dn", "name", "sAMAccountName", "type", "mail"), rows), "count", BigDecimal.valueOf(rows.size()));
        });
    }

    /** Earlier signature, kept for callers written before member_type. */
    public Map<String, Object> groupMembers(ActionContext ctx, String g, boolean recursive, int max, String session) { return groupMembers(ctx, g, recursive, max, session, "all"); }

    @Action(id = "move_group", name = "Move group", category = "Groups", capabilities = "network", description = "Move a group to another organizational unit; its members stay.",
            panel = "group, target_ou_dn, session")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveGroup(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "target_ou_dn", type = OoaType.STRING, description = "Distinguished name of the destination organizational unit.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, groupDn(s, g), target));
    }

    @Action(id = "create_group", name = "Create group", category = "Groups", capabilities = "network", description = "Create a security or distribution group.",
            panel = "session, name, group_description, scope, group_type, container_dn | sam_account_name")
    @Output(name = "dn", type = OoaType.STRING, description = "Distinguished name of the new group.")
    public Map<String, Object> createGroup(ActionContext ctx,
            @Input(name = "container_dn", type = OoaType.STRING, example = "OU=Groups,DC=corp,DC=example,DC=com", description = D_CONTAINER) String parent,
            @Input(name = "name", type = OoaType.STRING, description = "Group name (also its sAMAccountName unless given).") String name,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"global\"", enumValues = {"global", "domain_local", "universal"}, description = "Group scope.") String scope,
            @Input(name = "group_type", type = OoaType.STRING, required = false, defaultValue = "\"security\"", enumValues = {"security", "distribution"}, description = "Security groups can be used in permissions; distribution groups only for mail.") String type,
            @Input(name = "group_description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "sam_account_name", type = OoaType.STRING, required = false, description = "Pre-Windows 2000 name; the group name when empty.") String sam,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        int gt = switch (scope == null ? "global" : scope) { case "domain_local" -> 0x4; case "universal" -> 0x8; default -> 0x2; };
        if (!"distribution".equals(type)) gt |= 0x80000000;
        String n = name(name, "group");
        String dn = "CN=" + Rdn.escapeValue(n) + "," + container(parent);
        BasicAttributes a = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass"); oc.add("top"); oc.add("group");
        a.put(oc);
        a.put("cn", n);
        a.put("sAMAccountName", sam == null || sam.isBlank() ? n : sam.strip());
        a.put("groupType", String.valueOf(gt));
        if (description != null && !description.isBlank()) a.put("description", description);
        return work(ctx, session, s -> { s.dir.create(dn, a); return Map.of("dn", dn); });
    }

    @Action(id = "delete_group", name = "Delete group", category = "Groups", capabilities = "network", description = "Delete a group (its members are not deleted).",
            panel = "session, group")
    public Map<String, Object> deleteGroup(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(groupDn(s, g)); return Map.of(); });
    }

    @Action(id = "rename_group", name = "Rename group", category = "Groups", capabilities = "network", description = "Change a group's name, optionally also its pre-Windows 2000 name.",
            panel = "session, group, new_name | update_sam_account_name")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameGroup(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "new_name", type = OoaType.STRING, description = "New group name.") String newName,
            @Input(name = "update_sam_account_name", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also change the sAMAccountName to the new name.") boolean sam,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            Map<String, Object> r = rename(s, groupDn(s, g), newName, "group");
            if (sam) s.dir.modify((String) r.get("dn"), new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("sAMAccountName", newName.strip()))});
            return r;
        });
    }

    @Action(id = "get_group_property", name = "Get group properties", category = "Groups", idempotent = true, capabilities = "network", description = "Read attributes of a group.",
            panel = "session, group, attributes")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getGroupProperty(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"description\",\"managedBy\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, groupDn(s, g), attrs)));
    }

    @Action(id = "set_group_property", name = "Set group property", category = "Groups", capabilities = "network", description = "Change one attribute of a group, e.g. description or managedBy.",
            panel = "session, group, attribute, value")
    public Map<String, Object> setGroupProperty(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, groupDn(s, g), attr, value));
    }

    // ---- Computers ----

    @Action(id = "create_computer", name = "Create computer", category = "Computers", capabilities = "network", description = "Create a computer account so a machine can join the domain.",
            panel = "session, name, computer_description, container_dn | enabled")
    @Output(name = "dn", type = OoaType.STRING, description = "Distinguished name of the new computer.")
    public Map<String, Object> createComputer(ActionContext ctx,
            @Input(name = "container_dn", type = OoaType.STRING, example = "OU=Workstations,DC=corp,DC=example,DC=com", description = D_CONTAINER) String parent,
            @Input(name = "name", type = OoaType.STRING, example = "PC-0042", description = "Computer name (up to 15 characters).") String name,
            @Input(name = "computer_description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "enabled", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Create the account enabled.") boolean enabled,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        String n = name(name, "computer").replaceFirst("\\$$", "");
        if (!n.matches("^[A-Za-z0-9-]{1,15}$")) throw new ActionException("AD.BAD_NAME", "Invalid computer name '" + name + "'", "Use up to 15 letters, digits and hyphens.");
        String dn = "CN=" + Rdn.escapeValue(n) + "," + container(parent);
        BasicAttributes a = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass");
        for (String c : List.of("top", "person", "organizationalPerson", "user", "computer")) oc.add(c);
        a.put(oc);
        a.put("cn", n);
        a.put("sAMAccountName", n.toUpperCase(Locale.ROOT) + "$");
        a.put("userAccountControl", String.valueOf(WORKSTATION_TRUST_ACCOUNT | (enabled ? 0 : ACCOUNTDISABLE)));
        if (description != null && !description.isBlank()) a.put("description", description);
        return work(ctx, session, s -> { s.dir.create(dn, a); return Map.of("dn", dn); });
    }

    @Action(id = "delete_computer", name = "Delete computer", category = "Computers", capabilities = "network", description = "Delete a computer account.",
            panel = "session, computer")
    public Map<String, Object> deleteComputer(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(computerDn(s, c)); return Map.of(); });
    }

    @Action(id = "rename_computer", name = "Rename computer", category = "Computers", capabilities = "network",
            description = "Rename a computer account (object name and sAMAccountName); the machine itself is not renamed.",
            panel = "session, computer, new_name")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameComputer(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "new_name", type = OoaType.STRING, description = "New computer name (up to 15 characters).") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        String n = name(newName, "computer").replaceFirst("\\$$", "");
        if (!n.matches("^[A-Za-z0-9-]{1,15}$")) throw new ActionException("AD.BAD_NAME", "Invalid computer name '" + newName + "'", "Use up to 15 letters, digits and hyphens.");
        return work(ctx, session, s -> {
            Map<String, Object> r = rename(s, computerDn(s, c), n, "computer");
            s.dir.modify((String) r.get("dn"), new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("sAMAccountName", n.toUpperCase(Locale.ROOT) + "$"))});
            return r;
        });
    }

    @Action(id = "move_computer", name = "Move computer", category = "Computers", capabilities = "network", description = "Move a computer account to another organizational unit.",
            panel = "computer, target_ou_dn, session")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveComputer(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "target_ou_dn", type = OoaType.STRING, description = "Distinguished name of the destination organizational unit.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, computerDn(s, c), target));
    }

    @Action(id = "get_computer_property", name = "Get computer properties", category = "Computers", idempotent = true, capabilities = "network", description = "Read attributes of a computer account.",
            panel = "session, computer, attributes")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getComputerProperty(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"operatingSystem\",\"dNSHostName\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, computerDn(s, c), attrs)));
    }

    @Action(id = "set_computer_property", name = "Set computer property", category = "Computers", capabilities = "network", description = "Change one attribute of a computer account, e.g. description or location.",
            panel = "session, computer, attribute, value")
    public Map<String, Object> setComputerProperty(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, computerDn(s, c), attr, value));
    }

    // ---- Organizational units ----

    @Action(id = "create_ou", name = "Create organizational unit", category = "Organizational units", capabilities = "network", description = "Create an organizational unit.",
            panel = "session, name, ou_description, parent_dn")
    @Output(name = "dn", type = OoaType.STRING, description = "Distinguished name of the new organizational unit.")
    public Map<String, Object> createOu(ActionContext ctx,
            @Input(name = "parent_dn", type = OoaType.STRING, example = "DC=corp,DC=example,DC=com", description = "Distinguished name of the parent domain or organizational unit.") String parent,
            @Input(name = "name", type = OoaType.STRING, description = "Name of the organizational unit.") String name,
            @Input(name = "ou_description", type = OoaType.STRING, required = false, description = "Description.") String description,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        String n = name(name, "organizational unit");
        String dn = "OU=" + Rdn.escapeValue(n) + "," + container(parent);
        BasicAttributes a = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass"); oc.add("top"); oc.add("organizationalUnit");
        a.put(oc);
        a.put("ou", n);
        if (description != null && !description.isBlank()) a.put("description", description);
        return work(ctx, session, s -> { s.dir.create(dn, a); return Map.of("dn", dn); });
    }

    private static void deleteTree(AdSession s, String dn) throws NamingException {
        SearchControls sc = new SearchControls(SearchControls.ONELEVEL_SCOPE, 0, 30000, new String[]{"objectClass"}, false, false);
        List<String> children = new ArrayList<>();
        s.dir.search(dn, "(objectClass=*)", sc, e -> children.add(e.dn()));
        for (String c : children) deleteTree(s, c);
        s.dir.delete(dn);
    }

    @Action(id = "delete_ou", name = "Delete organizational unit", category = "Organizational units", capabilities = "network",
            description = "Delete an organizational unit; with recursive, everything inside it is deleted too.",
            panel = "session, ou | recursive")
    public Map<String, Object> deleteOu(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete every object inside it (cannot be undone).") boolean recursive,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            String dn = ouDn(s, ou);
            if (recursive) deleteTree(s, dn); else s.dir.delete(dn);
            return Map.of();
        });
    }

    @Action(id = "rename_ou", name = "Rename organizational unit", category = "Organizational units", capabilities = "network", description = "Change the name of an organizational unit.",
            panel = "session, ou, new_name")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameOu(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "new_name", type = OoaType.STRING, description = "New name.") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> rename(s, ouDn(s, ou), newName, "organizational unit"));
    }

    @Action(id = "move_ou", name = "Move organizational unit", category = "Organizational units", capabilities = "network", description = "Move an organizational unit, with everything in it, under another parent.",
            panel = "ou, target_parent_dn, session")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveOu(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "target_parent_dn", type = OoaType.STRING, description = "Distinguished name of the new parent.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, ouDn(s, ou), target));
    }

    @Action(id = "get_ou_property", name = "Get organizational unit properties", category = "Organizational units", idempotent = true, capabilities = "network", description = "Read attributes of an organizational unit.",
            panel = "session, ou, attributes")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getOuProperty(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"description\",\"managedBy\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, ouDn(s, ou), attrs)));
    }

    @Action(id = "set_ou_property", name = "Set organizational unit property", category = "Organizational units", capabilities = "network", description = "Change one attribute of an organizational unit, e.g. description or managedBy.",
            panel = "session, ou, attribute, value")
    public Map<String, Object> setOuProperty(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, ouDn(s, ou), attr, value));
    }

    // ---- Queries ----

    @Action(id = "run_query", name = "Run LDAP query", category = "Directory", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Run a raw LDAP filter from any starting point and scope and return the entries as a table.",
            panel = "session, filter, attributes | base_dn, scope, max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per entry; columns are dn plus the requested attributes.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of entries.")
    public Map<String, Object> runQuery(ActionContext ctx,
            @Input(name = "filter", type = OoaType.STRING, example = "(&(objectClass=computer)(operatingSystem=*Server*))", description = "LDAP filter.") String filter,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to return; cn, sAMAccountName and mail when empty.") List<Object> attrs,
            @Input(name = "base_dn", type = OoaType.STRING, required = false, description = "Where to start; the session's base when empty.") String base,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"subtree\"", enumValues = {"subtree", "one_level", "base"}, description = "How deep to search below the starting point.") String scope,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Safety limit.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        AdSession s = s(ctx, session);
        int sc = switch (scope == null ? "subtree" : scope) { case "one_level" -> SearchControls.ONELEVEL_SCOPE; case "base" -> SearchControls.OBJECT_SCOPE; default -> SearchControls.SUBTREE_SCOPE; };
        return table(ctx, s, base == null || base.isBlank() ? s.baseDn : container(base), filter, sc, attrs, max);
    }
}
