package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.vault.VaultAdmin;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

/**
 * Device credentials (13 Nov 2026 round 3, item 9 B4), as AA's My settings > Devices > Credentials: the Windows user
 * name and password a person's unattended runs sign in with. Kept encrypted as that person's own vault values (bound to
 * them, in a system vault folder the Credential vault page does not list), never shown back, and sent only to that
 * person's own runners when they ask to check them or to set them as the runner's Windows sign-in. One set for every
 * runner (as AA's Community Edition), or one for a single runner (as AA Cloud). Setting, checking and applying are in
 * the Audit Trail; the values never are.
 *
 * The request and the runner's answer use runner_files (kind 'login'): content 'check' or 'apply' while waiting,
 * 'sent:...' once given to the runner with its heartbeat, then 'ok' or 'applied', or an error.
 */
final class DeviceLogin {
    static final String LOCKER = "_device_login";
    private DeviceLogin() {}

    private static String name(String deviceId) { return deviceId == null || deviceId.isBlank() ? "windows" : "windows@" + deviceId; }
    private static String ref(String name, String attribute) { return LOCKER + "/" + name + "/" + attribute; }

    static boolean isSet(ControlRoom cr, String user) {
        return cr.db.one("SELECT name FROM credential_user_values WHERE locker = ? AND username = ? AND attribute = 'password'", LOCKER, user) != null;
    }

    /** Sets the person's device credentials, for every runner (deviceId null) or one runner. */
    static void set(ControlRoom cr, ControlRoom.User u, String deviceId, String username, String password, String ip) {
        if (username == null || username.isBlank() || username.length() > 200) throw new OoaException("CR.BAD_INPUT", "Enter your Windows user name", "For example DOMAIN\\name, .\\name or AzureAD\\name@company.com.");
        if (password == null || password.isEmpty() || password.length() > 500) throw new OoaException("CR.BAD_INPUT", "Enter your Windows password");
        if (deviceId != null && !deviceId.isBlank()) mine(cr, u, deviceId);
        String n = name(deviceId);
        synchronized (cr.vault) {
            Map<String, String> values = Map.of("username", username.strip(), "password", password);
            cr.db.tx(() -> {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    String ct = cr.vault.encrypt(e.getValue(), VaultAdmin.userBound(ref(n, e.getKey()), u.username()));
                    if (cr.db.update("UPDATE credential_user_values SET ciphertext = ?, updated = ? WHERE locker = ? AND name = ? AND attribute = ? AND username = ?",
                            ct, ControlRoom.now(), LOCKER, n, e.getKey(), u.username()) == 0)
                        cr.db.update("INSERT INTO credential_user_values (locker, name, attribute, username, ciphertext, updated) VALUES (?,?,?,?,?,?)",
                                LOCKER, n, e.getKey(), u.username(), ct, ControlRoom.now());
                }
                return null;
            });
        }
        cr.audit.record(u.username(), "user.device_credentials", "user", u.username(), Map.of("runner", deviceId == null || deviceId.isBlank() ? "every runner" : deviceId), "ok", ip);
    }

    /** Removes the person's device credentials (for every runner, or one runner's own). */
    static void clear(ControlRoom cr, ControlRoom.User u, String deviceId, String ip) {
        cr.db.update("DELETE FROM credential_user_values WHERE locker = ? AND name = ? AND username = ?", LOCKER, name(deviceId), u.username());
        cr.audit.record(u.username(), "user.device_credentials_removed", "user", u.username(), Map.of("runner", deviceId == null ? "every runner" : deviceId), "ok", ip);
    }

    /** The person's user name and password for a runner: that runner's own first, else the one for every runner; null when none. */
    static String[] get(ControlRoom cr, String user, String deviceId) {
        for (String n : List.of(name(deviceId), name(null))) {
            Map<String, Object> un = cr.db.one("SELECT ciphertext FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = 'username' AND username = ?", LOCKER, n, user);
            Map<String, Object> pw = cr.db.one("SELECT ciphertext FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = 'password' AND username = ?", LOCKER, n, user);
            if (un != null && pw != null) synchronized (cr.vault) {
                return new String[] { cr.vault.decrypt((String) un.get("ciphertext"), VaultAdmin.userBound(ref(n, "username"), user)),
                    cr.vault.decrypt((String) pw.get("ciphertext"), VaultAdmin.userBound(ref(n, "password"), user)) };
            }
        }
        return null;
    }

    /** A runner the person works with: their default runner, one they are a default user of, or one they connected. */
    static void mine(ControlRoom cr, ControlRoom.User u, String deviceId) {
        boolean ok = cr.db.one("SELECT device_id FROM user_devices WHERE username = ? AND device_id = ?", u.username(), deviceId) != null
            || cr.db.one("SELECT id FROM devices WHERE id = ? AND registered_by = ?", deviceId, MyAccount.SELF + u.username()) != null;
        if (!ok) throw new OoaException("CR.FORBIDDEN", "This is not one of your runners", "Choose it as your default runner first, or ask an administrator.");
    }

    /**
     * Asks the runner to check the person's credentials (Windows signs them in once, then out) or, with {@code apply},
     * to set them as its Windows sign-in after a restart (the runner needs Windows administrator rights for that).
     */
    static void request(ControlRoom cr, ControlRoom.User u, String deviceId, boolean apply, String ip) {
        mine(cr, u, deviceId);
        if (get(cr, u.username(), deviceId) == null) throw new OoaException("CR.BAD_INPUT", "Set your device credentials first");
        cr.db.tx(() -> {
            cr.db.update("DELETE FROM runner_files WHERE device_id = ? AND kind = 'login'", deviceId);
            cr.db.update("INSERT INTO runner_files (device_id, kind, taken, requested_by, error, content) VALUES (?,?,?,?,?,?)",
                    deviceId, "login", ControlRoom.now(), u.username(), null, apply ? "apply" : "check");
            return null;
        });
        cr.wake(deviceId, true);
        cr.audit.record(u.username(), apply ? "device.login_apply_request" : "device.login_check_request", "device", deviceId, null, "ok", ip);
    }

    /** With a runner's heartbeat: a waiting check or apply goes out with the credentials, once. */
    static void heartbeat(ControlRoom cr, Map<String, Object> d, Map<String, Object> out) {
        String id = (String) d.get("id");
        Map<String, Object> f = cr.db.one("SELECT requested_by, content FROM runner_files WHERE device_id = ? AND kind = 'login'", id);
        if (f == null || !("check".equals(f.get("content")) || "apply".equals(f.get("content")))) return;
        String[] c = get(cr, (String) f.get("requested_by"), id);
        if (c == null) { cr.db.update("UPDATE runner_files SET content = ?, error = ? WHERE device_id = ? AND kind = 'login'", "failed", "The device credentials were removed", id); return; }
        out.put("device_login", Map.of("username", c[0], "password", c[1], "apply", "apply".equals(f.get("content"))));
        cr.db.update("UPDATE runner_files SET content = ? WHERE device_id = ? AND kind = 'login'", "sent:" + f.get("content"), id);
    }

    /** The runner's answer: {ok: true} or {error: "..."}. */
    static void receive(ControlRoom cr, Map<String, Object> d, Map<String, Object> b) {
        String id = (String) d.get("id");
        Map<String, Object> f = cr.db.one("SELECT content FROM runner_files WHERE device_id = ? AND kind = 'login'", id);
        if (f == null || !String.valueOf(f.get("content")).startsWith("sent:")) throw new OoaException("CR.NOT_ASKED", "Nothing was asked of this runner", "Press Check in My devices first, then let the runner answer.");
        boolean apply = String.valueOf(f.get("content")).equals("sent:apply");
        String error = b.get("error") instanceof String e && !e.isBlank() ? e.substring(0, Math.min(e.length(), 1000)) : null;
        cr.db.update("UPDATE runner_files SET content = ?, error = ?, taken = ? WHERE device_id = ? AND kind = 'login'",
                error != null ? "failed" : apply ? "applied" : "ok", error, ControlRoom.now(), id);
        cr.audit.record("agent:" + d.get("name"), apply ? "device.login_apply" : "device.login_check", "device", id, error == null ? null : Map.of("error", error), error == null ? "ok" : "failed", null);
    }

    /** Where a runner's check stands, for My devices: {state: waiting | ok | applied | failed, error, at}. */
    static Map<String, Object> state(ControlRoom cr, String deviceId) {
        Map<String, Object> f = cr.db.one("SELECT content, error, taken FROM runner_files WHERE device_id = ? AND kind = 'login'", deviceId);
        if (f == null) return null;
        String c = String.valueOf(f.get("content"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", c.equals("ok") || c.equals("applied") || c.equals("failed") ? c : "waiting");
        if (f.get("error") != null) m.put("error", f.get("error"));
        m.put("at", String.valueOf(f.get("taken")));
        return m;
    }
}
