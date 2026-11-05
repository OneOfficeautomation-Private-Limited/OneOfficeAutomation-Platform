package com.sap.conn.jco;

/** Test stand-in for SAP JCo (the real library is licensed by SAP and not in the build); only what OOA uses. */
public interface JCoRecord {
    Object getValue(String name);
    Object getValue(int index);
    void setValue(String name, Object value);
    void setValue(String name, String value);
    void setValue(int index, Object value);
    JCoMetaData getMetaData();
    JCoStructure getStructure(String name);
    JCoTable getTable(String name);
}
