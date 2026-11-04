package com.sap.conn.jco;

public interface JCoMetaData {
    int getFieldCount();
    String getName(int i);
    String getTypeAsString(int i);
    String getDescription(int i);
    boolean isStructure(int i);
    boolean isTable(int i);
    JCoRecordMetaData getRecordMetaData(int i);
    int getLength(int i);
}
