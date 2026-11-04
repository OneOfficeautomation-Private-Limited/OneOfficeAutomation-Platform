package com.sap.conn.jco;

public final class JCoContext {
    private JCoContext() {}
    public static void begin(JCoDestination d) { ((FakeSap.Destination) d).stateful = true; }
    public static void end(JCoDestination d) throws JCoException { ((FakeSap.Destination) d).stateful = false; FakeSap.PENDING.clear(); }
}
