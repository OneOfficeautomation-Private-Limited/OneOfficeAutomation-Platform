package com.sap.conn.jco;

public class JCoException extends Exception {
    public static final int JCO_ERROR_LOGON_FAILURE = 103, JCO_ERROR_COMMUNICATION = 102, JCO_ERROR_ABAP_EXCEPTION = 123;
    private final int group; private final String key;
    public JCoException(int group, String key, String message) { super(message); this.group = group; this.key = key; }
    public int getGroup() { return group; }
    public String getKey() { return key; }
}
