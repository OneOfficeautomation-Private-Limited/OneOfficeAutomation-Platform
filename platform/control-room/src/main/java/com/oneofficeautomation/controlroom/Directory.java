package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.net.URI;
import java.util.*;

/**
 * Sign-in with Active Directory or another LDAP directory (Settings item 4f, 1 Nov 2026), as AA's Control Room does
 * on premises: passwords stay in the directory. A service account (ldap_bind_dn, its password from the vault in
 * ldap_bind_password_ref) finds the person under ldap_base_dn with ldap_user_filter ({0} is what they typed); their
 * password is checked by binding as them. Their groups (memberOf, or groups listing them as member; with
 * ldap_nested_groups, AD's nested groups too) give the role through the groups-to-roles list shared with single sign-on.
 * Several ldap_urls give failover, in order; ldaps:// is required except for a directory on this computer (tests).
 * Uses the JDK's own LDAP client: no third-party library.
 */
final class Directory {
    /** A person in the directory: their entry, the user name for One CommandCenter, details and group names (CN and DN). */
    record Person(String dn, String username, String email, String displayName, String firstName, String lastName, List<String> groups) {}

    private final ControlRoom cr;

    Directory(ControlRoom cr) { this.cr = cr; }

    private String str(String k) { Object v = cr.platform.setting(k); return v == null ? "" : String.valueOf(v).trim(); }

    boolean enabled() { return !str("ldap_urls").isEmpty(); }

    List<String> urls() {
        List<String> out = new ArrayList<>();
        for (String u : str("ldap_urls").split("[,;\\s]+")) if (!u.isBlank()) out.add(u.trim());
        return out;
    }

    /** ldaps://host[:port], or ldap:// only to this computer (a test directory); anything else is refused. */
    static void checkUrl(String url) {
        if (url.matches("(?i)^ldaps://[A-Za-z0-9.\\-]+(:\\d{1,5})?/?$")) return;
        if (url.matches("(?i)^ldap://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d{1,5})?/?$")) return;
        throw new OoaException("CR.BAD_INPUT", "A directory address is ldaps://host:636 (plain ldap:// only to this computer, for tests): " + url,
            "Use the domain controller's LDAPS address; its certificate must be trusted by Java's trust store.");
    }

    /** RFC 4515: what someone typed, safe inside a search filter. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) switch (c) {
            case '\\' -> b.append("\\5c"); case '*' -> b.append("\\2a"); case '(' -> b.append("\\28"); case ')' -> b.append("\\29"); case '\0' -> b.append("\\00");
            default -> b.append(c);
        }
        return b.toString();
    }

    /** Checks the person's password against the directory; returns who they are, or throws CR.LOGIN_FAILED or LDAP.UNREACHABLE. */
    Person authenticate(String login, String password) {
        String who = login == null ? "" : login.trim();
        // an empty password would be an anonymous bind, which many directories accept: never a sign-in
        if (who.isEmpty() || password == null || password.isEmpty()) throw new OoaException("CR.LOGIN_FAILED", "Wrong user name or password");
        return withService(ctx -> {
            Person p = find(ctx, who);
            if (p == null) throw new OoaException("CR.LOGIN_FAILED", "Wrong user name or password");
            LdapContext as = null;
            try { as = open(ctx.getEnvironment().get(Context.PROVIDER_URL).toString(), p.dn(), password); }
            catch (AuthenticationException e) { throw new OoaException("CR.LOGIN_FAILED", "Wrong user name or password"); }
            finally { close(as); }
            return p;
        });
    }

    /** Settings' test: the service account connects; with a name, the person it finds, their groups and the role they get. */
    Map<String, Object> test(String login) {
        return withService(ctx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("server", ctx.getEnvironment().get(Context.PROVIDER_URL));
            if (login != null && !login.isBlank()) {
                Person p = find(ctx, login.trim());
                m.put("found", p != null);
                if (p != null) {
                    m.put("dn", p.dn()); m.put("username", p.username()); m.put("email", p.email()); m.put("display_name", p.displayName());
                    m.put("groups", p.groups().stream().filter(g -> !g.contains("=")).toList());
                    m.put("role", cr.roleFromGroups(p.groups()));
                }
            }
            return m;
        });
    }

    private interface Work<T> { T run(LdapContext ctx) throws NamingException; }

    /** Binds as the service account to the first directory that answers (failover), and runs the work there. */
    private <T> T withService(Work<T> work) {
        if (!enabled()) throw new OoaException("LDAP.NOT_SET_UP", "Sign-in with Active Directory or LDAP is not set up", "An administrator sets it in Settings > Single sign-on.");
        String bindDn = str("ldap_bind_dn"), ref = str("ldap_bind_password_ref");
        if (bindDn.isEmpty() || ref.isEmpty() || str("ldap_base_dn").isEmpty())
            throw new OoaException("LDAP.NOT_SET_UP", "The directory's base, service account and its password are needed", "Fill them in Settings > Single sign-on.");
        String secret = cr.systemCredential(ref);
        String last = null;
        for (String url : urls()) {
            checkUrl(url);
            if (cr.tenant != null) NetGuard.check(cr, URI.create(url.replaceFirst("(?i)^ldaps?", "https")).getHost(), "The directory");
            LdapContext ctx = null;
            try {
                ctx = open(url, bindDn, secret);
                return work.run(ctx);
            } catch (AuthenticationException e) {
                throw new OoaException("LDAP.SERVICE_ACCOUNT", "The directory refused the service account " + bindDn, "Check ldap_bind_dn and its password in the vault.");
            } catch (CommunicationException | ServiceUnavailableException e) {
                last = url + ": " + e.getMessage();   // the next directory, if any
            } catch (NamingException e) {
                throw new OoaException("LDAP.FAILED", "The directory answered with an error: " + e.getMessage());
            } finally { close(ctx); }
        }
        throw new OoaException("LDAP.UNREACHABLE", "The directory could not be reached" + (last == null ? "" : " (" + last + ")"), "Check the addresses, the network and the certificate.");
    }

    private static LdapContext open(String url, String principal, String password) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put(Context.REFERRAL, "ignore");
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "15000");
        env.put("java.naming.ldap.attributes.binary", "objectGUID");
        return new InitialLdapContext(env, null);
    }

    private static void close(LdapContext ctx) { if (ctx != null) try { ctx.close(); } catch (NamingException ignored) { } }

    /** The one entry the filter finds for what was typed, with its groups; null when none (or more than one). */
    private Person find(LdapContext ctx, String login) throws NamingException {
        String filter = str("ldap_user_filter").isEmpty() ? "(|(sAMAccountName={0})(userPrincipalName={0}))" : str("ldap_user_filter");
        String nameAttr = str("ldap_name_attribute").isEmpty() ? "sAMAccountName" : str("ldap_name_attribute");
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(2);
        sc.setReturningAttributes(new String[] { nameAttr, "mail", "displayName", "cn", "givenName", "sn", "memberOf" });
        List<SearchResult> found = new ArrayList<>();
        NamingEnumeration<SearchResult> it = ctx.search(str("ldap_base_dn"), filter.replace("{0}", escape(login)), sc);
        try { while (it.hasMore()) found.add(it.next()); } catch (javax.naming.SizeLimitExceededException e) { return null; }
        if (found.size() != 1) return null;
        SearchResult r = found.get(0);
        Attributes a = r.getAttributes();
        String dn = r.getNameInNamespace();
        List<String> groups = new ArrayList<>();
        for (String g : all(a.get("memberOf"))) addGroup(groups, g);
        if (cr.platform.intSetting("ldap_nested_groups") == 1)   // AD's LDAP_MATCHING_RULE_IN_CHAIN: every group the person is in, nested ones too
            for (String g : groupsWhere(ctx, "(member:1.2.840.113556.1.4.1941:=" + escape(dn) + ")")) addGroup(groups, g);
        if (groups.isEmpty())   // directories without memberOf (OpenLDAP groupOfNames, groupOfUniqueNames)
            for (String g : groupsWhere(ctx, "(|(member=" + escape(dn) + ")(uniqueMember=" + escape(dn) + "))")) addGroup(groups, g);
        String name = one(a.get(nameAttr));
        String username = (name == null ? login : name).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.@-]", "_");
        String display = one(a.get("displayName")) != null ? one(a.get("displayName")) : one(a.get("cn"));
        return new Person(dn, username, one(a.get("mail")), display, one(a.get("givenName")), one(a.get("sn")), groups);
    }

    private List<String> groupsWhere(LdapContext ctx, String filter) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(500);
        sc.setReturningAttributes(new String[] { "cn" });
        List<String> out = new ArrayList<>();
        NamingEnumeration<SearchResult> it = ctx.search(str("ldap_base_dn"), filter, sc);
        try { while (it.hasMore()) out.add(it.next().getNameInNamespace()); } catch (javax.naming.SizeLimitExceededException ignored) { }
        return out;
    }

    /** A group by its common name (as the groups-to-roles list names it) and by its full DN. */
    private static void addGroup(List<String> groups, String dn) {
        if (!groups.contains(dn)) groups.add(dn);
        try {
            LdapName name = new LdapName(dn);
            Rdn first = name.size() == 0 ? null : name.getRdn(name.size() - 1);   // the leftmost part: CN=Finance in CN=Finance,OU=Groups,DC=...
            if (first != null && first.getType().equalsIgnoreCase("cn") && !groups.contains(String.valueOf(first.getValue()))) groups.add(String.valueOf(first.getValue()));
        } catch (javax.naming.InvalidNameException ignored) { }
    }

    private static String one(Attribute a) throws NamingException { return a == null || a.size() == 0 ? null : String.valueOf(a.get()); }

    private static List<String> all(Attribute a) throws NamingException {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        return out;
    }
}
