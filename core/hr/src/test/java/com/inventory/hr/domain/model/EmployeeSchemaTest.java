package com.inventory.hr.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Worker-app R8.9 and R11.2: this version adds only {@code membershipEndedAt} and {@code
 * profileRequestedAt} to the employee record; it gains no field for an Aadhaar number, a PAN, an
 * address, a document image or a photo (those wait for the profile-completion version, R8.8).
 */
class EmployeeSchemaTest {

  /** Field-name fragments no employee field may carry (case-insensitive). */
  private static final List<String> FORBIDDEN_FRAGMENTS =
      List.of("aadhaar", "aadhar", "address", "image", "photo", "document", "token", "secret");

  /** Exact names (or prefixes) that would hold a PAN; "pan" alone is too short to search for. */
  private static final List<String> FORBIDDEN_PREFIXES = List.of("pan", "virtualid", "vid");

  private static final Set<String> EMPLOYEE_FIELDS = Set.of(
      "id", "shopId", "code", "name", "phone", "email", "designation", "userId", "joinDate",
      "status", "exitDate", "membershipEndedAt", "profileRequestedAt", "workStart", "workEnd",
      "salaryRevisions", "paymentMethod", "bankAccountNumber", "ifsc", "upiId", "revision",
      "createdAt", "createdByUserId", "updatedAt", "updatedByUserId");

  @Test
  void employeeHasExactlyTheKnownFields() {
    assertEquals(new TreeSet<>(EMPLOYEE_FIELDS), fieldNames(Employee.class));
  }

  @Test
  void theTwoWorkerAppFieldsAreTimestamps() {
    assertEquals(Instant.class, type("membershipEndedAt"));
    assertEquals(Instant.class, type("profileRequestedAt"));
  }

  @Test
  void noFieldCanHoldAadhaarPanAddressOrAnImage() {
    for (Class<?> c : List.of(Employee.class, Employee.SalaryRevision.class)) {
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
    for (Field f : instanceFields(Employee.class)) {
      assertFalse(f.getType().isArray(), "Employee." + f.getName() + " is an array");
      assertFalse(org.bson.types.Binary.class.isAssignableFrom(f.getType()),
          "Employee." + f.getName() + " is binary");
    }
  }

  @Test
  void toStringLeavesContactAndPayDataOut() {
    Employee e = new Employee();
    e.setName("Priya");
    e.setPhone("9876543210");
    e.setEmail("priya@example.com");
    e.setBankAccountNumber("123456789012");
    e.setUpiId("priya@upi");
    String text = e.toString();
    assertTrue(text.contains("Priya"), text);
    for (String secret : List.of("9876543210", "priya@example.com", "123456789012", "priya@upi")) {
      assertFalse(text.contains(secret), text);
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

  private static Class<?> type(String field) {
    try {
      return Employee.class.getDeclaredField(field).getType();
    } catch (NoSuchFieldException e) {
      throw new AssertionError("Employee has no field " + field, e);
    }
  }
}
