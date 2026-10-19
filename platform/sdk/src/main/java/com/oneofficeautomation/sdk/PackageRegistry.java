package com.oneofficeautomation.sdk;

import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installed packages: signature verification (FR-PKG-003), semantic versions with a tenant default and
 * pinned ranges (FR-PKG-004), publisher allow-list (FR-PKG-007) and a usage index (FR-PKG-006).
 * In the Agent, each package version loads in its own PF4J classloader (FR-PKG-005).
 */
public final class PackageRegistry {

    public record LoadedPackage(Class<?> cls, Object instance, PackageIntrospector.PackageInfo info,
                                Map<String, Object> manifest, PackageSigning.Signature signature) {
        public String id() { return info.meta().id(); }
        public String version() { return info.meta().version(); }
        public ActionSpec spec(String kind, String id) {
            return switch (kind) {
                case "action" -> info.actions().get(id);
                case "condition" -> info.conditions().get(id);
                case "iterator" -> info.iterators().get(id);
                default -> null;
            };
        }
    }

    private final Map<String, PublicKey> trusted = new ConcurrentHashMap<>();
    private final Set<String> allowedPublishers;
    private final Map<String, Map<String, LoadedPackage>> packages = new ConcurrentHashMap<>();
    private final Map<String, String> defaults = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> usage = new ConcurrentHashMap<>();

    public PackageRegistry() { this(null); }

    /** @param allowedPublishers null = any trusted publisher */
    public PackageRegistry(Set<String> allowedPublishers) { this.allowedPublishers = allowedPublishers; }

    public void trust(PackageSigning.PublisherKey key) { trusted.put(key.publisher(), key.publicKey()); }

    public LoadedPackage install(Class<?> cls, PackageSigning.Signature sig) {
        PackageIntrospector.PackageInfo info = PackageIntrospector.inspect(cls);
        String pid = info.meta().id(), ver = info.meta().version();
        if (sig == null) throw new OoaException("PKG.UNSIGNED", "Package " + pid + "@" + ver + " is unsigned",
                "Build the package with the OOA SDK so it is signed.");
        if (allowedPublishers != null && !allowedPublishers.contains(sig.publisher()))
            throw new OoaException("PKG.PUBLISHER_NOT_ALLOWED", "Publisher '" + sig.publisher() + "' is not on this tenant's allow-list",
                    "Ask a tenant admin to approve the publisher.");
        PublicKey key = trusted.get(sig.publisher());
        if (key == null) throw new OoaException("PKG.UNTRUSTED", "No trusted key for publisher '" + sig.publisher() + "'");
        if (!PackageSigning.digest(cls).equals(sig.digest()))
            throw new OoaException("PKG.TAMPERED", "Package " + pid + "@" + ver + " was modified after signing");
        if (!PackageSigning.verify(sig.digest(), sig.signature(), key))
            throw new OoaException("PKG.BAD_SIGNATURE", "Signature check failed for " + pid + "@" + ver);
        Object instance;
        try { instance = cls.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw new OoaException("PKG.ERROR", "Cannot instantiate " + cls.getName() + ": " + e); }
        LoadedPackage lp = new LoadedPackage(cls, instance, info, PackageIntrospector.manifest(cls), sig);
        packages.computeIfAbsent(pid, k -> new ConcurrentHashMap<>()).put(ver, lp);
        defaults.merge(pid, ver, (old, nw) -> compare(nw, old) > 0 ? nw : old);
        return lp;
    }

    public void setDefault(String pid, String version) {
        if (!packages.getOrDefault(pid, Map.of()).containsKey(version))
            throw new OoaException("PKG.NOT_FOUND", pid + "@" + version + " is not installed");
        defaults.put(pid, version);
    }

    public LoadedPackage resolve(String pid, String range) {
        Map<String, LoadedPackage> versions = packages.get(pid);
        if (versions == null || versions.isEmpty())
            throw new OoaException("PKG.NOT_FOUND", "Package '" + pid + "' is not installed", "Install " + pid + " in Resources > Packages.");
        String def = defaults.get(pid);
        if (range == null || satisfies(def, range)) return versions.get(def);
        return versions.keySet().stream().filter(v -> satisfies(v, range)).max(PackageRegistry::compare).map(versions::get)
                .orElseThrow(() -> new OoaException("PKG.VERSION_MISMATCH",
                        "No installed version of '" + pid + "' satisfies '" + range + "'", "Installed: " + new TreeSet<>(versions.keySet())));
    }

    public void recordUsage(String pid, String version, String automationId) {
        usage.computeIfAbsent(pid + "@" + version, k -> ConcurrentHashMap.newKeySet()).add(automationId);
    }

    public Set<String> usage(String pid, String version) { return usage.getOrDefault(pid + "@" + version, Set.of()); }

    public List<Map<String, Object>> allManifests() {
        return new TreeMap<>(defaults).entrySet().stream().map(e -> packages.get(e.getKey()).get(e.getValue()).manifest()).toList();
    }

    // ---------- semantic versions ----------
    private static final Pattern V = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    static int[] parse(String v) {
        Matcher m = V.matcher(v.trim());
        if (!m.matches()) throw new OoaException("PKG.ERROR", "Invalid version '" + v + "'");
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    static int compare(String a, String b) { return Arrays.compare(parse(a), parse(b)); }

    public static boolean satisfies(String version, String range) {
        String r = range.trim();
        if (r.isEmpty() || r.equals("*") || r.equals("latest")) return true;
        int[] v = parse(version);
        if (r.startsWith("^")) {
            int[] b = parse(r.substring(1));
            int[] upper = b[0] > 0 ? new int[]{b[0] + 1, 0, 0} : new int[]{0, b[1] + 1, 0};
            return Arrays.compare(v, b) >= 0 && Arrays.compare(v, upper) < 0;
        }
        if (r.startsWith("~")) {
            int[] b = parse(r.substring(1));
            return Arrays.compare(v, b) >= 0 && Arrays.compare(v, new int[]{b[0], b[1] + 1, 0}) < 0;
        }
        if (r.startsWith(">=")) return Arrays.compare(v, parse(r.substring(2))) >= 0;
        return Arrays.compare(v, parse(r)) == 0;
    }
}
