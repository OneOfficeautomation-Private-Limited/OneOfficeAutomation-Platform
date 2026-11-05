package com.sap.conn.jco.ext;

public interface DestinationDataProvider {
    java.util.Properties getDestinationProperties(String destinationName);
    boolean supportsEvents();
    void setDestinationDataEventListener(DestinationDataEventListener listener);
}
