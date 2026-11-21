package com.inventory.product.rest.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * Holder for an optional boolean request option that must survive lenient JSON parsing.
 *
 * <p>Design choice: instead of letting Jackson fail the whole request body (HTTP 400 with a generic
 * parse error) when a client sends {@code "yes"} or {@code 1} for a boolean option, the field is
 * bound to this holder. The attached {@link Deserializer} accepts:
 *
 * <ul>
 *   <li>JSON {@code true}/{@code false} → {@link #value()} set, {@link #invalidRaw()} null
 *   <li>JSON strings {@code "true"}/{@code "false"} (case-insensitive, trimmed) → same as above
 *   <li>JSON {@code null} or a missing property → the holder itself is {@code null}
 *   <li>anything else (e.g. {@code "yes"}, {@code 1}, {@code []}) → {@link #value()} null and
 *       {@link #invalidRaw()} carrying the offending raw JSON text
 * </ul>
 *
 * <p>The validator can then emit a field-specific message such as {@code "showBarcodeText must be
 * true or false"} alongside the other validation errors instead of a bare parse failure.
 *
 * @param value the parsed boolean, or {@code null} when the raw input was not a boolean
 * @param invalidRaw the raw JSON text when it could not be interpreted as a boolean, else {@code
 *     null}
 */
@JsonDeserialize(using = LenientBoolean.Deserializer.class)
public record LenientBoolean(Boolean value, String invalidRaw) {

  /** Wraps an already-parsed boolean; {@code null} yields {@code null}. */
  public static LenientBoolean of(Boolean value) {
    return value == null ? null : new LenientBoolean(value, null);
  }

  /** Marks raw input that could not be interpreted as a boolean. */
  public static LenientBoolean invalid(String raw) {
    return new LenientBoolean(null, raw == null ? "" : raw);
  }

  /** True when the raw input was present but not a recognised boolean. */
  public boolean isInvalid() {
    return invalidRaw != null;
  }

  /** Null-safe accessor for the parsed value of a possibly-null holder. */
  public static Boolean valueOf(LenientBoolean holder) {
    return holder == null ? null : holder.value();
  }

  /** Null-safe accessor for the invalid flag of a possibly-null holder. */
  public static boolean isInvalid(LenientBoolean holder) {
    return holder != null && holder.isInvalid();
  }

  /** Jackson deserializer implementing the lenient rules documented on {@link LenientBoolean}. */
  public static class Deserializer extends JsonDeserializer<LenientBoolean> {
    @Override
    public LenientBoolean deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      JsonToken token = p.currentToken();
      if (token == JsonToken.VALUE_TRUE) {
        return LenientBoolean.of(true);
      }
      if (token == JsonToken.VALUE_FALSE) {
        return LenientBoolean.of(false);
      }
      if (token == JsonToken.VALUE_NULL) {
        return null;
      }
      if (token == JsonToken.VALUE_STRING) {
        String raw = p.getText();
        String normalised = raw == null ? "" : raw.trim();
        if ("true".equalsIgnoreCase(normalised)) {
          return LenientBoolean.of(true);
        }
        if ("false".equalsIgnoreCase(normalised)) {
          return LenientBoolean.of(false);
        }
        return LenientBoolean.invalid(raw);
      }
      // Numbers, arrays, objects: consume the whole value so the parser stays in sync.
      JsonNode node = p.readValueAsTree();
      return LenientBoolean.invalid(node == null ? "" : node.toString());
    }

    @Override
    public LenientBoolean getNullValue(DeserializationContext ctxt) {
      return null;
    }
  }
}
