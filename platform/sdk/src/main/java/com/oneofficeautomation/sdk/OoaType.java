package com.oneofficeautomation.sdk;

/** OOA data types, identical to the "type" enum in OOA Flow v1. */
public enum OoaType {
    STRING("String"), NUMBER("Number"), BOOLEAN("Boolean"), DATETIME("DateTime"), LIST("List"),
    DICTIONARY("Dictionary"), TABLE("Table"), RECORD("Record"), FILE("File"), WINDOW("Window"),
    UIELEMENT("UIElement"), CREDENTIAL("Credential"), ANY("Any"),
    /** A time of day without a date or zone, e.g. 14:30:05 (java.time.LocalTime at run time). */
    TIME("Time"),
    /** A length of time, possibly negative, e.g. PT2H15M (java.time.Duration at run time). */
    TIMESPAN("TimeSpan"),
    /** A picture held in memory as PNG bytes with its width and height ({@link ImageValue}). */
    IMAGE("Image"),
    /** Bytes held in memory, e.g. a file's contents ({@link BinaryValue}). */
    BINARY("Binary");

    private final String flowName;

    OoaType(String flowName) { this.flowName = flowName; }

    public String flowName() { return flowName; }

    public static OoaType fromFlowName(String name) {
        for (OoaType t : values()) if (t.flowName.equals(name)) return t;
        throw new IllegalArgumentException("Unknown OOA type: " + name);
    }
}
