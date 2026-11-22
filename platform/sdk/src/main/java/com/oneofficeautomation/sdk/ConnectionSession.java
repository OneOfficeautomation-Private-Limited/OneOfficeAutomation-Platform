package com.oneofficeautomation.sdk;

/**
 * A toolkit's Connect step that names a saved connection (Slack, Jira, Google...): kept in the run's sessions, so the
 * later steps of that toolkit which choose no connection of their own use this one (the runner fills their empty
 * inputs and sign-in token from it, just in time). The latest Connect of a toolkit wins; Disconnect removes it.
 *
 * @param packageId  the toolkit the connection is for, e.g. ooa.slack
 * @param connection the saved connection's name
 * @param openedAt   System.nanoTime() when Connect ran (the latest wins when several are open)
 */
public record ConnectionSession(String packageId, String connection, long openedAt) {
    public ConnectionSession(String packageId, String connection) { this(packageId, connection, System.nanoTime()); }
}
