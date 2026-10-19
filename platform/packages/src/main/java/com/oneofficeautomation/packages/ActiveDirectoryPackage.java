package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import javax.naming.*;
import javax.naming.directory.*;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Active Directory / LDAP user and group administration over JNDI. All filter values are escaped (RFC 4515). */
@OoaPackage(id = "ooa.active_directory", name = "Active Directory", version = "1.0.0",
        description = "Search, enable, disable, unlock users, reset passwords and manage group membership in Active Directory or LDAP.")
public final class ActiveDirectoryPackage {
    private static final int ACCOUNTDISABLE = 0x2;

    static final class AdSession implements AutoCloseable {
        final LdapContext ctx; final String baseDn;
        AdSession(LdapContext c, String b) { ctx = c; baseDn = b; }
        @Override public void close() throws NamingException { ctx.close(); }
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
        return new ActionException("AD.ERROR", m, null);
    }

    private static AdSession s(ActionContext ctx, String name) { return ctx.session(name, AdSession.class); }

    @Action(id = "connect", name = "Connect", category = "Directory", capabilities = "network", description = "Sign in to Active Directory or an LDAP server.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, pattern = "^ldaps?://", example = "ldaps://dc01.corp.example.com:636", description = "Server URL; use ldaps:// for password changes.") String url,
            @Input(name = "bind_user", type = OoaType.STRING, example = "svc-bot@corp.example.com", description = "User to sign in as (UPN or distinguished name).") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Password from the vault.") SecureString password,
            @Input(name = "base_dn", type = OoaType.STRING, example = "DC=corp,DC=example,DC=com", description = "Where searches start.") String baseDn,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, user);
        env.put(Context.SECURITY_CREDENTIALS, password.reveal());
        env.put("com.sun.jndi.ldap.connect.timeout", "10000");
        env.put("com.sun.jndi.ldap.read.timeout", "30000");
        env.put(Context.REFERRAL, "follow");
        try { ctx.sessions().put(session, new AdSession(new InitialLdapContext(env, null), baseDn)); }
        catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    private SearchResult findUser(AdSession s, String username) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(2);
        String f = "(&(objectCategory=person)(objectClass=user)(|(sAMAccountName=" + escape(username) + ")(userPrincipalName=" + escape(username) + ")))";
        NamingEnumeration<SearchResult> r = s.ctx.search(s.baseDn, f, sc);
        if (!r.hasMore()) throw new ActionException("AD.USER_NOT_FOUND", "User '" + username + "' not found", "Check the user name (sAMAccountName or UPN).");
        return r.next();
    }

    static Object attrValue(Attribute a) throws NamingException {
        if (a == null) return null;
        if (a.size() == 1) { Object v = a.get(); return v instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) v) : String.valueOf(v); }
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) { Object v = a.get(i); l.add(v instanceof byte[] ? Base64.getEncoder().encodeToString((byte[]) v) : String.valueOf(v)); }
        return l;
    }

    @Action(id = "search", name = "Search", category = "Directory", idempotent = true, capabilities = "network", description = "Run an LDAP filter and return matching entries as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per entry; columns are dn plus the requested attributes.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of entries.")
    public Map<String, Object> search(ActionContext ctx,
            @Input(name = "filter", type = OoaType.STRING, example = "(&(objectClass=user)(department=Finance))", description = "LDAP filter.") String filter,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to return, e.g. [\"cn\",\"mail\"].") List<Object> attrs,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Safety limit.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        AdSession s = s(ctx, session);
        List<String> names = new ArrayList<>();
        if (attrs != null) for (Object a : attrs) names.add(Values.text(a));
        if (names.isEmpty()) names.addAll(List.of("cn", "sAMAccountName", "mail"));
        SearchControls sc = new SearchControls(SearchControls.SUBTREE_SCOPE, max, 30000, names.toArray(new String[0]), false, false);
        List<String> cols = new ArrayList<>(List.of("dn"));
        cols.addAll(names);
        List<Object> rows = new ArrayList<>();
        try {
            NamingEnumeration<SearchResult> r = s.ctx.search(s.baseDn, filter, sc);
            while (r.hasMore()) {
                SearchResult e = r.next();
                List<Object> row = new ArrayList<>();
                row.add(e.getNameInNamespace());
                for (String n : names) row.add(attrValue(e.getAttributes().get(n)));
                rows.add(row);
            }
        } catch (SizeLimitExceededException e) { ctx.warn("Search stopped at " + max + " results");
        } catch (InvalidSearchFilterException e) { throw new ActionException("AD.BAD_FILTER", "Invalid LDAP filter: " + filter, "Example: (&(objectClass=user)(sAMAccountName=jdoe))");
        } catch (NamingException e) { throw ad(e); }
        return Out.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network", description = "Read a user's properties by sAMAccountName or UPN.")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "dn, sAMAccountName, displayName, mail, enabled, locked and more.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String username,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        try {
            SearchResult r = findUser(s(ctx, session), username);
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("dn", r.getNameInNamespace());
            for (String a : List.of("sAMAccountName", "userPrincipalName", "displayName", "givenName", "sn", "mail", "department", "title", "manager", "memberOf", "telephoneNumber"))
                u.put(a, attrValue(r.getAttributes().get(a)));
            Object uac = attrValue(r.getAttributes().get("userAccountControl"));
            if (uac != null) u.put("enabled", (Integer.parseInt(String.valueOf(uac)) & ACCOUNTDISABLE) == 0);
            Object lock = attrValue(r.getAttributes().get("lockoutTime"));
            u.put("locked", lock != null && !"0".equals(String.valueOf(lock)));
            return Map.of("user", u);
        } catch (NamingException e) { throw ad(e); }
    }

    private Map<String, Object> modify(ActionContext ctx, String session, String username, ModificationItem... items) {
        AdSession s = s(ctx, session);
        try { s.ctx.modifyAttributes(new LdapNameHolder(findUser(s, username).getNameInNamespace()).name, items); }
        catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    private record LdapNameHolder(String name) {}

    private int uac(ActionContext ctx, String session, String username) {
        try { return Integer.parseInt(String.valueOf(attrValue(findUser(s(ctx, session), username).getAttributes().get("userAccountControl")))); }
        catch (NamingException e) { throw ad(e); }
    }

    @Action(id = "enable_user", name = "Enable user", category = "Users", capabilities = "network", description = "Enable a disabled user account.")
    public Map<String, Object> enable(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(uac(ctx, session, u) & ~ACCOUNTDISABLE))));
    }

    @Action(id = "disable_user", name = "Disable user", category = "Users", capabilities = "network", description = "Disable a user account (for example when someone leaves).")
    public Map<String, Object> disable(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(uac(ctx, session, u) | ACCOUNTDISABLE))));
    }

    @Action(id = "unlock_user", name = "Unlock user", category = "Users", capabilities = "network", description = "Unlock an account locked by failed sign-ins.")
    public Map<String, Object> unlock(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return modify(ctx, session, u, new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("lockoutTime", "0")));
    }

    @Action(id = "reset_password", name = "Reset password", category = "Users", capabilities = "network",
            description = "Set a new password (needs an ldaps:// connection), optionally forcing a change at next sign-in.")
    public Map<String, Object> resetPassword(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_password", type = OoaType.CREDENTIAL, description = "New password from the vault or a Credential variable.") SecureString pw,
            @Input(name = "must_change", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Require a change at next sign-in.") boolean mustChange,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        byte[] encoded = ("\"" + pw.reveal() + "\"").getBytes(StandardCharsets.UTF_16LE);
        List<ModificationItem> items = new ArrayList<>(List.of(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("unicodePwd", encoded))));
        if (mustChange) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        try { return modify(ctx, session, u, items.toArray(new ModificationItem[0])); }
        catch (ActionException e) {
            if ("AD.ERROR".equals(e.code()) || "AD.ACCESS_DENIED".equals(e.code()))
                throw new ActionException(e.code(), e.getMessage(), "Password changes need an ldaps:// connection and a password that meets the domain policy.");
            throw e;
        }
    }

    private Map<String, Object> group(ActionContext ctx, String session, String username, String groupDn, int op) {
        AdSession s = s(ctx, session);
        try {
            String userDn = findUser(s, username).getNameInNamespace();
            s.ctx.modifyAttributes(groupDn, new ModificationItem[]{new ModificationItem(op, new BasicAttribute("member", userDn))});
        } catch (AttributeInUseException e) { ctx.info(username + " is already a member");
        } catch (NoSuchAttributeException e) { ctx.info(username + " was not a member");
        } catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    @Action(id = "add_to_group", name = "Add user to group", category = "Groups", capabilities = "network", description = "Add a user to a group (no error if already a member).")
    public Map<String, Object> addToGroup(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "group_dn", type = OoaType.STRING, example = "CN=Finance,OU=Groups,DC=corp,DC=example,DC=com", description = "Group distinguished name.") String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return group(ctx, session, u, g, DirContext.ADD_ATTRIBUTE);
    }

    @Action(id = "remove_from_group", name = "Remove user from group", category = "Groups", capabilities = "network", description = "Remove a user from a group (no error if not a member).")
    public Map<String, Object> removeFromGroup(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "group_dn", type = OoaType.STRING, description = "Group distinguished name.") String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        return group(ctx, session, u, g, DirContext.REMOVE_ATTRIBUTE);
    }

    @Action(id = "set_attribute", name = "Set user property", category = "Users", capabilities = "network", description = "Change one attribute of a user, e.g. title or telephoneNumber.")
    public Map<String, Object> setAttribute(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "New value; empty clears it.") String value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        if (attr.equalsIgnoreCase("unicodePwd") || attr.equalsIgnoreCase("userPassword"))
            throw new ActionException("AD.USE_RESET_PASSWORD", "Use Reset password to change passwords");
        ModificationItem m = value == null || value.isEmpty() ? new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(attr))
                : new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(attr, value));
        return modify(ctx, session, u, m);
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Directory", description = "Close the directory session.")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof AdSession a) try { a.close(); } catch (NamingException ignored) { }
        return Map.of();
    }
}
