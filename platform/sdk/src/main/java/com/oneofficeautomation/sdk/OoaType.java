package com.oneofficeautomation.sdk;

/** OOA data types, identical to the "type" enum in OOA Flow v1. */
public enum OoaType {
    STRING("String"), NUMBER("Number"), BOOLEAN("Boolean"), DATETIME("DateTime"), LIST("List"),
    DICTIONARY("Dictionary"), TABLE("Table"), RECORD("Record"), FILE("File"), WINDOW("Window"),
    UIELEMENT("UIElement"), CREDENTIAL("Credential"), ANY("Any");

    private final String flowName;

    OoaType(String flowName) { this.flowName = flowName; }

    public String flowName() { return flowName; }

    public static OoaType fromFlowName(String name) {
        for (OoaType t : values()) if (t.flowName.equals(name)) return t;
        throw new IllegalArgumentException("Unknown OOA type: " + name);
    }
}
