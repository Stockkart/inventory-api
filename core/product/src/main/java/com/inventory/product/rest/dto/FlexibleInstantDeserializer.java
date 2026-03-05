package com.inventory.product.rest.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Deserializes Instant from ISO-8601 or date-only strings.
 * Accepts: "2027-07-01T00:00:00Z", "2027-07-01T00:00:00.000Z", "2027-07-01"
 */
public class FlexibleInstantDeserializer extends JsonDeserializer<Instant> {

  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ISO_LOCAL_DATE;

  @Override
  public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    String value = p.getText();
    if (value == null || value.isBlank()) {
      return null;
    }
    value = value.trim();
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      // Try date-only format (yyyy-MM-dd) as midnight UTC
      try {
        LocalDate date = LocalDate.parse(value, DATE_ONLY);
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
      } catch (Exception e2) {
        throw new IOException("Cannot parse Instant from '" + value + "'. Use ISO-8601 (e.g. 2027-07-01T00:00:00Z) or date-only (2027-07-01)", e);
      }
    }
  }
}
