import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A local in-memory LDAP directory for the Active Directory unit-test agent ("UT Active Directory"): DC=ut,DC=test with
 * OU=Staff (user Ann Lee, sAMAccountName ann.lee; group Finance with Ann; computer PC-SEED) and the bind user
 * CN=admin,DC=ut,DC=test. Schema checks are off, so the toolkit's Active Directory attributes are accepted. Made-up data.
 *
 *   java -cp unboundid-ldapsdk-7.0.1.jar LdapStandin.java [ldap port, default 8895] [admin password]
 *
 * POST http://127.0.0.1:(ldap port + 1)/reset puts the directory back as seeded (the agent does this first).
 * The unboundid jar is the one the platform's own tests use (Gradle cache: com.unboundid/unboundid-ldapsdk/7.0.1).
 */
public class LdapStandin {
    static InMemoryDirectoryServer ldap;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8895;
        String password = args.length > 1 ? args[1] : "ut-made-up-ldap-password";
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("DC=ut,DC=test");
        cfg.setSchema(null);
        cfg.addAdditionalBindCredentials("CN=admin,DC=ut,DC=test", password);
        cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", InetAddress.getLoopbackAddress(), port, null));
        ldap = new InMemoryDirectoryServer(cfg);
        seed();
        ldap.startListening();
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port + 1), 0);
        http.createContext("/reset", ex -> {
            byte[] out;
            try { seed(); out = "{\"reset\": \"ldap\"}".getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(200, out.length); }
            catch (Exception e) { out = e.toString().getBytes(StandardCharsets.UTF_8); ex.sendResponseHeaders(500, out.length); }
            ex.getResponseBody().write(out);
            ex.close();
        });
        http.start();
        System.out.println("LDAP stand-in on ldap://127.0.0.1:" + port + " (reset: http://127.0.0.1:" + (port + 1) + "/reset)");
    }

    static synchronized void seed() throws Exception {
        ldap.clear();
        ldap.add("dn: DC=ut,DC=test", "objectClass: top", "objectClass: domain", "dc: ut");
        ldap.add("dn: OU=Staff,DC=ut,DC=test", "objectClass: top", "objectClass: organizationalUnit", "ou: Staff", "description: People");
        ldap.add("dn: OU=Archive,DC=ut,DC=test", "objectClass: top", "objectClass: organizationalUnit", "ou: Archive");
        ldap.add("dn: CN=Ann Lee,OU=Staff,DC=ut,DC=test", "objectClass: top", "objectClass: person", "objectClass: organizationalPerson", "objectClass: user",
                "cn: Ann Lee", "sAMAccountName: ann.lee", "userPrincipalName: ann.lee@ut.test", "givenName: Ann", "sn: Lee", "displayName: Ann Lee",
                "mail: ann.lee@ut.test", "department: Finance", "title: Analyst", "userAccountControl: 512");
        ldap.add("dn: CN=Bob Ray,OU=Staff,DC=ut,DC=test", "objectClass: top", "objectClass: person", "objectClass: organizationalPerson", "objectClass: user",
                "cn: Bob Ray", "sAMAccountName: bob.ray", "userPrincipalName: bob.ray@ut.test", "displayName: Bob Ray", "department: Finance", "userAccountControl: 514");
        ldap.add("dn: CN=Finance,OU=Staff,DC=ut,DC=test", "objectClass: top", "objectClass: group", "cn: Finance", "sAMAccountName: Finance",
                "groupType: -2147483646", "description: Finance team", "member: CN=Ann Lee,OU=Staff,DC=ut,DC=test");
        ldap.add("dn: CN=PC-SEED,OU=Staff,DC=ut,DC=test", "objectClass: top", "objectClass: person", "objectClass: organizationalPerson", "objectClass: user",
                "objectClass: computer", "cn: PC-SEED", "sAMAccountName: PC-SEED$", "description: Seeded computer", "userAccountControl: 4096");
    }
}
