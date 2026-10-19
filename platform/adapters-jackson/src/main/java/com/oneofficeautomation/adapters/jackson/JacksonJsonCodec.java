package com.oneofficeautomation.adapters.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.oneofficeautomation.runner.JsonCodec;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Production Flow JSON codec. Numbers become BigDecimal (never double), objects keep key order. */
public final class JacksonJsonCodec implements JsonCodec {
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    @Override public Object parse(String json) {
        try { return normalise(MAPPER.readValue(json, Object.class)); }
        catch (JsonProcessingException e) {
            throw new OoaException("JSON.INVALID", "Invalid JSON at line " + e.getLocation().getLineNr() + ", column "
                    + e.getLocation().getColumnNr() + ": " + e.getOriginalMessage(), "Check commas, quotes and brackets.");
        }
    }

    @Override public String write(Object value, boolean pretty) {
        try {
            Object v = Values.toJsonable(value);
            return pretty ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(v) : MAPPER.writeValueAsString(v);
        } catch (JsonProcessingException e) { throw new OoaException("JSON.WRITE_FAILED", e.getOriginalMessage()); }
    }

    static Object normalise(Object v) {
        if (v instanceof BigInteger i) return new BigDecimal(i);
        if (v instanceof Integer || v instanceof Long) return BigDecimal.valueOf(((Number) v).longValue());
        if (v instanceof Double || v instanceof Float) return new BigDecimal(v.toString());
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(String.valueOf(k), normalise(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(normalise(x)); return o; }
        return v;
    }
}
