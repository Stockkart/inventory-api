package com.inventory.plugins.medical.search;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.util.StringUtils;

/** Opaque cursor for medical extension search sorted by (expiryDate, inventoryId). */
final class MedicalInventorySearchCursorCodec {

  private static final String NULL_EXPIRY = "none";
  private static final Instant NULL_EXPIRY_SORT_SENTINEL =
      Instant.parse("9999-12-31T23:59:59.999Z");

  private MedicalInventorySearchCursorCodec() {}

  record Decoded(long expiryEpochMillis, String inventoryId) {}

  static Instant nullExpirySortSentinel() {
    return NULL_EXPIRY_SORT_SENTINEL;
  }

  static String encode(Instant expiryDate, String inventoryId) {
    if (!StringUtils.hasText(inventoryId)) {
      return null;
    }
    String expiryToken =
        expiryDate != null ? String.valueOf(expiryDate.toEpochMilli()) : NULL_EXPIRY;
    String raw = expiryToken + "|" + inventoryId.trim();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  static Decoded decode(String cursor) {
    if (!StringUtils.hasText(cursor)) {
      return null;
    }
    try {
      String raw =
          new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
      int sep = raw.indexOf('|');
      if (sep <= 0 || sep >= raw.length() - 1) {
        return null;
      }
      String expiryToken = raw.substring(0, sep);
      String inventoryId = raw.substring(sep + 1);
      if (!StringUtils.hasText(inventoryId)) {
        return null;
      }
      long epoch =
          NULL_EXPIRY.equals(expiryToken) ? Long.MAX_VALUE : Long.parseLong(expiryToken);
      return new Decoded(epoch, inventoryId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  static Criteria cursorAfter(String cursor) {
    Decoded decoded = decode(cursor);
    if (decoded == null) {
      return new Criteria();
    }
    if (decoded.expiryEpochMillis() >= Long.MAX_VALUE) {
      return new Criteria()
          .andOperator(
              new Criteria()
                  .orOperator(
                      Criteria.where("expiryDate").exists(false),
                      Criteria.where("expiryDate").is(null)),
              Criteria.where("inventoryId").gt(decoded.inventoryId()));
    }
    Instant expiry = Instant.ofEpochMilli(decoded.expiryEpochMillis());
    return new Criteria()
        .orOperator(
            Criteria.where("expiryDate").gt(expiry),
            new Criteria()
                .andOperator(
                    Criteria.where("expiryDate").is(expiry),
                    Criteria.where("inventoryId").gt(decoded.inventoryId())),
            new Criteria()
                .orOperator(
                    Criteria.where("expiryDate").exists(false),
                    Criteria.where("expiryDate").is(null)));
  }
}
