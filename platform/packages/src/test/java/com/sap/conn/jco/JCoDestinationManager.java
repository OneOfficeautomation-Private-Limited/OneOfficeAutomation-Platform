package com.sap.conn.jco;

public final class JCoDestinationManager {
    private JCoDestinationManager() {}
    public static JCoDestination getDestination(String name) throws JCoException {
        java.util.Properties p = com.sap.conn.jco.ext.Environment.provider().getDestinationProperties(name);
        if (p == null) throw new JCoException(106, "JCO_ERROR_RESOURCE", "Destination " + name + " does not exist");
        return new FakeSap.Destination(p);
    }
}
