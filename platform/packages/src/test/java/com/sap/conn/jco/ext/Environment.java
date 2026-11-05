package com.sap.conn.jco.ext;

public final class Environment {
    private Environment() {}
    private static DestinationDataProvider provider;
    public static synchronized void registerDestinationDataProvider(DestinationDataProvider p) {
        if (provider != null) throw new IllegalStateException("DestinationDataProvider already registered");
        provider = p;
    }
    public static synchronized boolean isDestinationDataProviderRegistered() { return provider != null; }
    public static synchronized DestinationDataProvider provider() { return provider; }
}
