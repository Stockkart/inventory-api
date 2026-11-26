package com.inventory.user.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Worker-app R11.1 and R8.9: a worker account stores name, e-mail, password hash, phone, role,
 * shop, memberships (own collection), timestamps and its session tokens; the photo lives in {@code
 * user_photos}. The record gains no Aadhaar, PAN, address, document-image, contact-list,
 * advertising-id or location field. A new field is added to the allow-list here on purpose.
 */
class UserAccountSchemaTest {

  private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
      "aadhaar", "aadhar", "address", "image", "photo", "document", "contact", "advertising",
      "location", "latitude", "longitude", "imei", "androidid");

  private static final List<String> FORBIDDEN_PREFIXES = List.of("pan", "virtualid", "vid");

  private static final Set<String> ACCOUNT_FIELDS = Set.of(
      "userId", "name", "role", "shopId", "email", "phone", "password", "active",
      "inviteAccepted", "createdAt", "updatedAt", "tokens", "passwordResetToken",
      "passwordResetTokenExpiresAt");

  /** {@code source} ("web" / "worker-app", R1.8) is the one field the Stockkart Team app added. */
  private static final Set<String> TOKEN_FIELDS =
      Set.of("accessToken", "refreshToken", "deviceId", "createdAt", "expiresAt", "source");

  @Test
  void accountHasExactlyTheKnownFields() {
    assertEquals(new TreeSet<>(ACCOUNT_FIELDS), fieldNames(UserAccount.class));
    assertEquals(new TreeSet<>(TOKEN_FIELDS), fieldNames(UserToken.class));
  }

  @Test
  void noFieldCanHoldAadhaarPanAddressAnImageOrADeviceProfile() {
    for (Class<?> c : List.of(UserAccount.class, UserToken.class)) {
      for (String name : fieldNames(c)) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
          assertFalse(lower.contains(fragment),
              c.getSimpleName() + "." + name + " looks like it could hold " + fragment);
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
          assertFalse(lower.startsWith(prefix),
              c.getSimpleName() + "." + name + " looks like it could hold a " + prefix);
        }
      }
    }
  }

  @Test
  void noFieldIsBinary() {
    for (Class<?> c : List.of(UserAccount.class, UserToken.class, UserPhoto.class)) {
      for (Field f : instanceFields(c)) {
        boolean binary = f.getType().isArray()
            || org.bson.types.Binary.class.isAssignableFrom(f.getType());
        // the photo bytes are the one binary field, and they live in user_photos, not on the account
        if (c == UserPhoto.class && "bytes".equals(f.getName())) {
          continue;
        }
        assertFalse(binary, c.getSimpleName() + "." + f.getName() + " is binary");
      }
    }
  }

  private static Set<String> fieldNames(Class<?> c) {
    return instanceFields(c).stream().map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<Field> instanceFields(Class<?> c) {
    return Arrays.stream(c.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .toList();
  }
}
