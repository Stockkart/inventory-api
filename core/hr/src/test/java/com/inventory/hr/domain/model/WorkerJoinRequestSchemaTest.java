package com.inventory.hr.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Pins the shape of the worker-app join request document (R3.9, R8.9, R11.3). A new field must be
 * added to the allow-list here on purpose, and no field may ever be able to hold a token, photo
 * bytes, an Aadhaar or PAN number, or an address. There is no pairing-proof document and no
 * {@code kind} / pairing field on a request: the legacy pairing flows were removed (D11).
 */
class WorkerJoinRequestSchemaTest {

  /** Field-name fragments that must not appear on any of these documents (case-insensitive). */
  private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
      "token", "proof", "secret", "password", "photo", "image", "bytes", "aadhaar", "aadhar",
      "vid", "pan", "address", "salary", "bank", "ifsc", "upi", "message");

  private static final Set<String> JOIN_REQUEST_FIELDS = Set.of(
      "id", "shopId", "userId", "status", "snapshot", "requestedAt", "decidedAt",
      "decidedByUserId", "outcome", "employeeId", "rejectionReason", "expiresAt", "updatedAt");

  private static final Set<String> SNAPSHOT_FIELDS = Set.of("name", "phone", "email", "hasPhoto");

  @Test
  void joinRequestHasExactlyTheDesignedFields() {
    assertEquals(new TreeSet<>(JOIN_REQUEST_FIELDS), fieldNames(WorkerJoinRequest.class));
    assertEquals(new TreeSet<>(SNAPSHOT_FIELDS), fieldNames(WorkerJoinRequest.Snapshot.class));
    assertEquals("hr_join_requests",
        WorkerJoinRequest.class.getAnnotation(Document.class).collection());
  }

  @Test
  void joinRequestFieldTypesMatchTheErDiagram() {
    assertEquals(WorkerJoinStatus.class, type(WorkerJoinRequest.class, "status"));
    assertEquals(JoinOutcome.class, type(WorkerJoinRequest.class, "outcome"));
    assertEquals(WorkerJoinRequest.Snapshot.class, type(WorkerJoinRequest.class, "snapshot"));
    for (String instant : List.of("requestedAt", "decidedAt", "expiresAt", "updatedAt")) {
      assertEquals(Instant.class, type(WorkerJoinRequest.class, instant), instant);
    }
    // hasPhoto is a flag, not the picture: the bytes stay in user_photos (R11.5)
    assertEquals(boolean.class, type(WorkerJoinRequest.Snapshot.class, "hasPhoto"));
    // the snapshot's contact fields are plain strings, so the frozen copy cannot grow a message
    assertEquals(String.class, type(WorkerJoinRequest.Snapshot.class, "phone"));
    assertEquals(String.class, type(WorkerJoinRequest.Snapshot.class, "email"));
  }

  @Test
  void noFieldCanHoldATokenProofPhotoAadhaarPanOrAddress() {
    for (Class<?> c : List.of(WorkerJoinRequest.class, WorkerJoinRequest.Snapshot.class)) {
      for (String name : fieldNames(c)) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_FRAGMENTS) {
          if ("hasPhoto".equals(name) && "photo".equals(fragment)) {
            continue; // a boolean flag, pinned to boolean above
          }
          assertFalse(lower.contains(fragment),
              c.getSimpleName() + "." + name + " looks like it could hold " + fragment);
        }
      }
    }
  }

  @Test
  void noFieldIsABinaryOrCollectionType() {
    for (Class<?> c : List.of(WorkerJoinRequest.class, WorkerJoinRequest.Snapshot.class)) {
      for (Field f : instanceFields(c)) {
        Class<?> t = f.getType();
        assertFalse(t.isArray(), c.getSimpleName() + "." + f.getName() + " is an array");
        assertFalse(Iterable.class.isAssignableFrom(t) || java.util.Map.class.isAssignableFrom(t),
            c.getSimpleName() + "." + f.getName() + " is a collection");
        assertFalse(org.bson.types.Binary.class.isAssignableFrom(t),
            c.getSimpleName() + "." + f.getName() + " is binary");
      }
    }
  }

  @Test
  void toStringLeavesContactDataOut() {
    WorkerJoinRequest.Snapshot snapshot =
        new WorkerJoinRequest.Snapshot("Priya", "9876543210", "priya@example.com", true);
    WorkerJoinRequest request = new WorkerJoinRequest();
    request.setSnapshot(snapshot);
    String text = request.toString();
    assertTrue(text.contains("Priya"), text);
    assertFalse(text.contains("9876543210"), text);
    assertFalse(text.contains("priya@example.com"), text);
  }

  private static Set<String> fieldNames(Class<?> c) {
    return instanceFields(c).stream().map(Field::getName).collect(Collectors.toCollection(
        TreeSet::new));
  }

  private static List<Field> instanceFields(Class<?> c) {
    return Arrays.stream(c.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .toList();
  }

  private static Class<?> type(Class<?> c, String field) {
    try {
      return c.getDeclaredField(field).getType();
    } catch (NoSuchFieldException e) {
      throw new AssertionError(c.getSimpleName() + " has no field " + field, e);
    }
  }
}
