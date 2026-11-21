package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.utils.AppSignatures;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Shape checks for an Attendance app punch (design: "Validation (400) is for malformed input
 * only"). A request that passes here can be turned into the canonical payload bytes; whether the
 * signature then verifies is a business check ({@code HR_APP_NOT_VERIFIED}), not validation.
 *
 * <p>Ids and the punch id must be lowercase, because the canonical payload is signed byte for
 * byte and the server rebuilds it from these fields; an uppercase letter could never verify.
 * Messages never repeat the signature.
 */
@Component
public class AppPunchValidator {

  /** The app sends ids exactly as the server gave them: 24 lowercase hex characters. */
  static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-f]{24}$");
  /** UUID v4 as the app makes it: lowercase, with hyphens. */
  static final Pattern UUID_LOWER =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  /** A DER P-256 ECDSA signature is at most 72 bytes, 96 Base64 characters. */
  static final int MAX_SIGNATURE_CHARS = 128;
  /** Larger than any distance on Earth, so a garbage number is refused as malformed. */
  static final int MAX_METRES = 50_000_000;

  static final String PHONE_TIME_EXAMPLE = "2025-04-08T03:42:00Z";

  private static final DateTimeFormatter PHONE_TIME = DateTimeFormatter
      .ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT)
      .withZone(ZoneOffset.UTC);

  /**
   * Checks every field and returns the punch fields in payload order.
   *
   * @throws ValidationException (400) on the first malformed field
   */
  public AppSignatures.PunchFields validate(AppPunchRequest r) {
    if (r == null) {
      throw new ValidationException("A punch body is required");
    }
    requireObjectId(r.getPairingId(), "pairingId");
    requireObjectId(r.getShopId(), "shopId");
    requireObjectId(r.getEmployeeId(), "employeeId");
    if (!StringUtils.hasText(r.getPunchId()) || !UUID_LOWER.matcher(r.getPunchId()).matches()) {
      throw new ValidationException("punchId must be a lowercase UUID");
    }
    Instant phoneTime = parsePhoneTime(r.getPhoneTime());
    requireFlag(r.getOffline(), "offline");
    requireFlag(r.getLocationChecked(), "locationChecked");
    requireFlag(r.getMock(), "mock");
    requireMetres(r.getDistanceMetres(), "distanceMetres");
    requireMetres(r.getAccuracyMetres(), "accuracyMetres");
    requireSignature(r.getSignature());
    return new AppSignatures.PunchFields(r.getPairingId(), r.getShopId(), r.getEmployeeId(),
        r.getPunchId(), phoneTime, r.getOffline(), r.getLocationChecked(), r.getDistanceMetres(),
        r.getAccuracyMetres(), r.getMock());
  }

  /** {@code yyyy-MM-ddTHH:mm:ssZ}, UTC, whole seconds, exactly as the payload carries it. */
  public static Instant parsePhoneTime(String value) {
    if (!StringUtils.hasText(value)) {
      throw new ValidationException("phoneTime is required, like " + PHONE_TIME_EXAMPLE);
    }
    try {
      return Instant.from(PHONE_TIME.parse(value));
    } catch (DateTimeParseException e) {
      throw new ValidationException("phoneTime must be a UTC time like " + PHONE_TIME_EXAMPLE);
    }
  }

  private static void requireObjectId(String value, String field) {
    if (!StringUtils.hasText(value) || !OBJECT_ID.matcher(value).matches()) {
      throw new ValidationException(field + " must be a 24-character lowercase id");
    }
  }

  private static void requireFlag(Boolean value, String field) {
    if (value == null) {
      throw new ValidationException(field + " must be true or false");
    }
  }

  private static void requireMetres(Integer value, String field) {
    if (value != null && (value < 0 || value > MAX_METRES)) {
      throw new ValidationException(field + " must be a whole number of metres, 0 or more");
    }
  }

  private static void requireSignature(String signature) {
    if (!StringUtils.hasText(signature)) {
      throw new ValidationException("signature is required");
    }
    if (signature.length() > MAX_SIGNATURE_CHARS) {
      throw new ValidationException("signature is too long");
    }
    try {
      Base64.getDecoder().decode(signature.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("signature must be Base64");
    }
  }
}
