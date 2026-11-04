package com.sap.conn.jco;

public interface JCoFunction {
    String getName();
    JCoParameterList getImportParameterList();
    JCoParameterList getExportParameterList();
    JCoParameterList getChangingParameterList();
    JCoParameterList getTableParameterList();
    void execute(JCoDestination destination) throws JCoException;
}
