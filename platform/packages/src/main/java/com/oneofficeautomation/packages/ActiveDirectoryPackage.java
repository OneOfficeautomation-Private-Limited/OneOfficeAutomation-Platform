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
        try { ctx.sessions().put(session, new AdSession(new JndiDirectory(new InitialLdapContext(env, null)), baseDn)); }
        catch (NamingException e) { throw ad(e); }
        return Map.of();
    }

    private Entry findUser(AdSession s, String username) throws NamingException {
        String f = "(&(objectCategory=person)(objectClass=user)(|(sAMAccountName=" + escape(username) + ")(userPrincipalName=" + escape(username) + ")))";
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

    @Action(id = "search", name = "Search", category = "Directory", idempotent = true, capabilities = "network", description = "Run an LDAP filter and return matching entries as a table.")
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

    @Action(id = "get_user", name = "Get user", category = "Users", idempotent = true, capabilities = "network", description = "Read a user's properties by sAMAccountName or UPN.")
    @Output(name = "user", type = OoaType.DICTIONARY, description = "dn, sAMAccountName, displayName, mail, enabled, locked and more.")
    public Map<String, Object> getUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String username,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        try {
            Entry r = findUser(s(ctx, session), username);
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("dn", r.dn());
            for (String a : List.of("sAMAccountName", "userPrincipalName", "displayName", "givenName", "sn", "mail", "department", "title", "manager", "memberOf", "telephoneNumber"))
                u.put(a, attrValue(r.attributes().get(a)));
            Object uac = attrValue(r.attributes().get("userAccountControl"));
            if (uac != null) u.put("enabled", (Integer.parseInt(String.valueOf(uac)) & ACCOUNTDISABLE) == 0);
            Object lock = attrValue(r.attributes().get("lockoutTime"));
            u.put("locked", lock != null && !"0".equals(String.valueOf(lock)));
            return Map.of("user", u);
        } catch (NamingException e) { throw ad(e); }
    }

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

    private static ModificationItem passwordItem(SecureString pw) {
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("unicodePwd", ("\"" + pw.reveal() + "\"").getBytes(StandardCharsets.UTF_16LE)));
    }

    private static ActionException passwordHint(ActionException e) {
        if ("AD.ERROR".equals(e.code()) || "AD.ACCESS_DENIED".equals(e.code()))
            return new ActionException(e.code(), e.getMessage(), "Password changes need an ldaps:// connection and a password that meets the domain policy.");
        return e;
    }

    @Action(id = "reset_password", name = "Reset password", category = "Users", capabilities = "network",
            description = "Set a new password (needs an ldaps:// connection), optionally forcing a change at next sign-in.")
    public Map<String, Object> resetPassword(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_password", type = OoaType.CREDENTIAL, description = "New password from the vault or a Credential variable.") SecureString pw,
            @Input(name = "must_change", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Require a change at next sign-in.") boolean mustChange,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = "Session name.") String session) {
        List<ModificationItem> items = new ArrayList<>(List.of(passwordItem(pw)));
        if (mustChange) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        try { return modify(ctx, session, u, items.toArray(new ModificationItem[0])); }
        catch (ActionException e) { throw passwordHint(e); }
    }

    private Map<String, Object> group(ActionContext ctx, String session, String username, String groupDn, int op) {
        AdSession s = s(ctx, session);
        try {
            String userDn = findUser(s, username).dn();
            s.dir.modify(groupDn, new ModificationItem[]{new ModificationItem(op, new BasicAttribute("member", userDn))});
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
        return modify(ctx, session, u, setItem(attr, value));
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Directory", description = "Close the directory session.")
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
            description = "Create a user account in an organizational unit, optionally with a first password, and enable it.")
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
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
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
        a.put("userAccountControl", String.valueOf(NORMAL_ACCOUNT | ACCOUNTDISABLE));
        AdSession s = s(ctx, session);
        try { s.dir.create(dn, a); } catch (NamingException e) { throw ad(e); }
        List<ModificationItem> after = new ArrayList<>();
        if (password != null) after.add(passwordItem(password));
        if (mustChange) after.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", "0")));
        if (enabled && password != null) after.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(NORMAL_ACCOUNT))));
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

    @Action(id = "delete_user", name = "Delete user", category = "Users", capabilities = "network", description = "Delete a user account.")
    public Map<String, Object> deleteUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(findUser(s, u).dn()); return Map.of(); });
    }

    @Action(id = "rename_user", name = "Rename user", category = "Users", capabilities = "network", description = "Change the object name of a user, optionally also its display name; the sign-in name is kept.")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "new_name", type = OoaType.STRING, description = "New object name (common name), e.g. Jane Doe.") String newName,
            @Input(name = "update_display_name", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also set the display name to the new name.") boolean display,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            Map<String, Object> r = rename(s, findUser(s, u).dn(), newName, "user");
            if (display) s.dir.modify((String) r.get("dn"), new ModificationItem[]{new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("displayName", newName.strip()))});
            return r;
        });
    }

    @Action(id = "move_user", name = "Move user", category = "Users", capabilities = "network", description = "Move a user to another organizational unit.")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveUser(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "target_ou_dn", type = OoaType.STRING, description = "Distinguished name of the destination organizational unit.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, findUser(s, u).dn(), target));
    }

    @Action(id = "set_password", name = "Set password", category = "Users", capabilities = "network",
            description = "Set a user's password as an administrator (needs an ldaps:// connection), optionally unlocking the account.")
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
            description = "Turn account options on or off: enabled, password never expires, and must change password at next sign-in; empty options stay as they are.")
    @Output(name = "user_account_control", type = OoaType.NUMBER, description = "The account control flags after the change.")
    public Map<String, Object> accountOptions(ActionContext ctx,
            @Input(name = "username", type = OoaType.STRING, description = "sAMAccountName or UPN.") String u,
            @Input(name = "enabled", type = OoaType.BOOLEAN, required = false, description = "Enable (true) or disable (false) the account.") Boolean enabled,
            @Input(name = "password_never_expires", type = OoaType.BOOLEAN, required = false, description = "Whether the password never expires.") Boolean neverExpires,
            @Input(name = "must_change_password", type = OoaType.BOOLEAN, required = false, description = "Whether a password change is required at next sign-in.") Boolean mustChange,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            Entry e = findUser(s, u);
            Object raw = attrValue(e.attributes().get("userAccountControl"));
            int before = raw == null ? NORMAL_ACCOUNT : Integer.parseInt(String.valueOf(raw)), flags = before;
            if (enabled != null) flags = enabled ? flags & ~ACCOUNTDISABLE : flags | ACCOUNTDISABLE;
            if (neverExpires != null) flags = neverExpires ? flags | DONT_EXPIRE_PASSWORD : flags & ~DONT_EXPIRE_PASSWORD;
            List<ModificationItem> items = new ArrayList<>();
            if (flags != before) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("userAccountControl", String.valueOf(flags))));
            if (mustChange != null) items.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("pwdLastSet", mustChange ? "0" : "-1")));
            if (!items.isEmpty()) s.dir.modify(e.dn(), items.toArray(new ModificationItem[0]));
            return Map.of("user_account_control", BigDecimal.valueOf(flags));
        });
    }

    // ---- Groups ----

    @Action(id = "get_group_members", name = "Get group members", category = "Groups", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List the members of a group, optionally including members of nested groups.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: dn, name, sAMAccountName, type, mail.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of members.")
    public Map<String, Object> groupMembers(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Include members of nested groups (Active Directory only).") boolean recursive,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> {
            String dn = groupDn(s, g);
            String filter = "(memberOf" + (recursive ? ":1.2.840.113556.1.4.1941:" : "") + "=" + escape(dn) + ")";
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
            return Map.of("table", Values.table(List.of("dn", "name", "sAMAccountName", "type", "mail"), rows), "count", BigDecimal.valueOf(rows.size()));
        });
    }

    @Action(id = "create_group", name = "Create group", category = "Groups", capabilities = "network", description = "Create a security or distribution group.")
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

    @Action(id = "delete_group", name = "Delete group", category = "Groups", capabilities = "network", description = "Delete a group (its members are not deleted).")
    public Map<String, Object> deleteGroup(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(groupDn(s, g)); return Map.of(); });
    }

    @Action(id = "rename_group", name = "Rename group", category = "Groups", capabilities = "network", description = "Change a group's name, optionally also its pre-Windows 2000 name.")
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

    @Action(id = "get_group_property", name = "Get group properties", category = "Groups", idempotent = true, capabilities = "network", description = "Read attributes of a group.")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getGroupProperty(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"description\",\"managedBy\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, groupDn(s, g), attrs)));
    }

    @Action(id = "set_group_property", name = "Set group property", category = "Groups", capabilities = "network", description = "Change one attribute of a group, e.g. description or managedBy.")
    public Map<String, Object> setGroupProperty(ActionContext ctx,
            @Input(name = "group", type = OoaType.STRING, description = D_GROUP) String g,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, groupDn(s, g), attr, value));
    }

    // ---- Computers ----

    @Action(id = "create_computer", name = "Create computer", category = "Computers", capabilities = "network", description = "Create a computer account so a machine can join the domain.")
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

    @Action(id = "delete_computer", name = "Delete computer", category = "Computers", capabilities = "network", description = "Delete a computer account.")
    public Map<String, Object> deleteComputer(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> { s.dir.delete(computerDn(s, c)); return Map.of(); });
    }

    @Action(id = "rename_computer", name = "Rename computer", category = "Computers", capabilities = "network",
            description = "Rename a computer account (object name and sAMAccountName); the machine itself is not renamed.")
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

    @Action(id = "move_computer", name = "Move computer", category = "Computers", capabilities = "network", description = "Move a computer account to another organizational unit.")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveComputer(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "target_ou_dn", type = OoaType.STRING, description = "Distinguished name of the destination organizational unit.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, computerDn(s, c), target));
    }

    @Action(id = "get_computer_property", name = "Get computer properties", category = "Computers", idempotent = true, capabilities = "network", description = "Read attributes of a computer account.")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getComputerProperty(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"operatingSystem\",\"dNSHostName\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, computerDn(s, c), attrs)));
    }

    @Action(id = "set_computer_property", name = "Set computer property", category = "Computers", capabilities = "network", description = "Change one attribute of a computer account, e.g. description or location.")
    public Map<String, Object> setComputerProperty(ActionContext ctx,
            @Input(name = "computer", type = OoaType.STRING, description = D_COMPUTER) String c,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, computerDn(s, c), attr, value));
    }

    // ---- Organizational units ----

    @Action(id = "create_ou", name = "Create organizational unit", category = "Organizational units", capabilities = "network", description = "Create an organizational unit.")
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
            description = "Delete an organizational unit; with recursive, everything inside it is deleted too.")
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

    @Action(id = "rename_ou", name = "Rename organizational unit", category = "Organizational units", capabilities = "network", description = "Change the name of an organizational unit.")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> renameOu(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "new_name", type = OoaType.STRING, description = "New name.") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> rename(s, ouDn(s, ou), newName, "organizational unit"));
    }

    @Action(id = "move_ou", name = "Move organizational unit", category = "Organizational units", capabilities = "network", description = "Move an organizational unit, with everything in it, under another parent.")
    @Output(name = "dn", type = OoaType.STRING, description = "New distinguished name.")
    public Map<String, Object> moveOu(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "target_parent_dn", type = OoaType.STRING, description = "Distinguished name of the new parent.") String target,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> move(s, ouDn(s, ou), target));
    }

    @Action(id = "get_ou_property", name = "Get organizational unit properties", category = "Organizational units", idempotent = true, capabilities = "network", description = "Read attributes of an organizational unit.")
    @Output(name = "properties", type = OoaType.DICTIONARY, description = "dn plus the requested attributes (all readable ones when none are listed).")
    public Map<String, Object> getOuProperty(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "attributes", type = OoaType.LIST, required = false, description = "Attributes to read, e.g. [\"description\",\"managedBy\"]; all when empty.") List<Object> attrs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> Map.of("properties", properties(s, ouDn(s, ou), attrs)));
    }

    @Action(id = "set_ou_property", name = "Set organizational unit property", category = "Organizational units", capabilities = "network", description = "Change one attribute of an organizational unit, e.g. description or managedBy.")
    public Map<String, Object> setOuProperty(ActionContext ctx,
            @Input(name = "ou", type = OoaType.STRING, description = D_OU) String ou,
            @Input(name = "attribute", type = OoaType.STRING, description = "LDAP attribute name.") String attr,
            @Input(name = "value", required = false, description = "New value, or a list for several values; empty clears it.") Object value,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"ad\"", description = D_SESSION) String session) {
        return work(ctx, session, s -> set(s, ouDn(s, ou), attr, value));
    }

    // ---- Queries ----

    @Action(id = "run_query", name = "Run LDAP query", category = "Directory", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Run a raw LDAP filter from any starting point and scope and return the entries as a table.")
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
