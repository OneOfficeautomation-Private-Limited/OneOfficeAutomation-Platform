package com.oneofficeautomation.runner;

/** Reads and writes Flow JSON into the runtime value model. Production adapter: Jackson. */
public interface JsonCodec {
    Object parse(String json);
    String write(Object value, boolean pretty);

    /** JDK-only codec built on the SDK's JsonLite (tests, tools, no-dependency contexts). */
    JsonCodec LITE = new JsonCodec() {
        @Override public Object parse(String json) { return com.oneofficeautomation.sdk.JsonLite.parse(json); }
        @Override public String write(Object value, boolean pretty) { return com.oneofficeautomation.sdk.JsonLite.write(Values.toJsonable(value)); }
    };
}
