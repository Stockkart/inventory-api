package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.utils.AppSignatures;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Shape checks for the pairing endpoints (design: "Validation (400) is for malformed input only").
 * Ids are 24 hex characters, installation ids are UUIDs, public keys must parse as P-256
 * SubjectPublicKeyInfo and free text from the phone is bounded. Messages never repeat a key or an
 * installation id, because validation messages are logged.
 */
@Component
public class DevicePairingValidator {

  public static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
  public static final Pattern UUID = Pattern.compile(
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  /** As reported by the phone's operating system; "Redmi Note 11" is 13 characters (R2.15). */
  public static final int MAX_PHONE_MODEL = 80;
  public static final int MAX_OS_VERSION = 40;
  public static final int MAX_APP_VERSION = 40;
  /** 32 random bytes, Base64url without padding, is 43 characters. */
  static final int MAX_CLAIM_TOKEN = 128;

  /** A Mongo ObjectId as the app sends it (pairing id, shop id, employee id). */
  public void requireObjectId(String value, String field) {
    if (!StringUtils.hasText(value) || !OBJECT_ID.matcher(value.trim()).matches()) {
      throw new ValidationException(field + " must be a 24-character id");
    }
  }

  /** The app's installation id: a UUID it made on first launch (R2.11). */
  public void requireInstallationId(String value) {
    requireUuid(value, "installationId");
  }

  public void requireUuid(String value, String field) {
    if (!StringUtils.hasText(value) || !UUID.matcher(value.trim()).matches()) {
      throw new ValidationException(field + " must be a UUID");
    }
  }

  /** Base64 DER SubjectPublicKeyInfo of a key on secp256r1 (R5.1, DD3). */
  public void requireP256PublicKey(String spkiBase64, String field) {
    if (!StringUtils.hasText(spkiBase64)) {
      throw new ValidationException(field + " is required");
    }
    try {
      AppSignatures.parseP256PublicKey(spkiBase64);
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " is not a P-256 public key");
    }
  }

  /** Phone model as the operating system reports it: optional, single line, at most 80 chars. */
  public void requirePhoneModel(String phoneModel) {
    requireShortText(phoneModel, "phoneModel", MAX_PHONE_MODEL);
  }

  public void requireOsVersion(String osVersion) {
    requireShortText(osVersion, "osVersion", MAX_OS_VERSION);
  }

  public void requireAppVersion(String appVersion) {
    requireShortText(appVersion, "appVersion", MAX_APP_VERSION);
  }

  public void requireOs(DeviceOs os) {
    if (os == null) {
      throw new ValidationException("os must be ANDROID or IOS");
    }
  }

  /** The claim token from {@code claim}; its value is opaque, only its size is bounded here. */
  public void requireClaimToken(String claimToken) {
    if (!StringUtils.hasText(claimToken)) {
      throw new ValidationException("claimToken is required");
    }
    if (claimToken.trim().length() > MAX_CLAIM_TOKEN) {
      throw new ValidationException("claimToken is too long");
    }
  }

  /** The consent version the worker agreed to must be a positive number (R9.13). */
  public void requireConsentVersion(Integer version) {
    if (version == null || version < 1) {
      throw new ValidationException("consentVersion must be 1 or more");
    }
  }

  private static void requireShortText(String value, String field, int max) {
    if (value == null) {
      return;
    }
    if (value.length() > max) {
      throw new ValidationException(field + " may have at most " + max + " characters");
    }
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new ValidationException(field + " must be a single line");
    }
  }
}
