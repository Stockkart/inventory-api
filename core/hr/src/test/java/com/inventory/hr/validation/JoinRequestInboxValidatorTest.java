package com.inventory.hr.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.request.ApproveJoinRequestRequest;
import com.inventory.hr.rest.dto.request.BulkJoinRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Each shape rule of the Join requests inbox (worker-app R4.1, R4.4, R4.6, R4.7, R11.3). */
class JoinRequestInboxValidatorTest {

  private static final String OBJECT_ID = "6630a1b2c3d4e5f60718293a";
  private static final String EMPLOYEE_ID = "6630a1b2c3d4e5f60718293b";

  private final JoinRequestInboxValidator validator = new JoinRequestInboxValidator();

  // ---- ids -------------------------------------------------------------------------------------

  @Test
  void anIdIs24HexCharactersWithSpaceAndCaseIgnored() {
    assertDoesNotThrow(() -> validator.requireObjectId(OBJECT_ID, "id"));
    assertDoesNotThrow(() -> validator.requireObjectId(" " + OBJECT_ID.toUpperCase() + " ", "id"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "req-1", "6630a1b2c3d4e5f60718293", "6630a1b2c3d4e5f60718293ab",
      "6630a1b2c3d4e5f60718293g"})
  void anythingElseIsNotAnIdAndTheMessageNamesTheField(String value) {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireObjectId(value, "employeeId"));
    assertEquals("employeeId must be a 24-character id", e.getMessage());
  }

  // ---- status ----------------------------------------------------------------------------------

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void aMissingStatusMeansPending(String status) {
    assertEquals(WorkerJoinStatus.PENDING, validator.requireInboxStatus(status));
  }

  @Test
  void statusIsPendingOrRejectedInAnyCase() {
    assertEquals(WorkerJoinStatus.PENDING, validator.requireInboxStatus("PENDING"));
    assertEquals(WorkerJoinStatus.REJECTED, validator.requireInboxStatus("rejected"));
    assertEquals(WorkerJoinStatus.REJECTED, validator.requireInboxStatus(" Rejected "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"APPROVED", "WITHDRAWN", "APPROVING", "all", "pending,rejected"})
  void otherStatusesAreRefused(String status) {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireInboxStatus(status));
    assertEquals("status must be PENDING or REJECTED", e.getMessage());
  }

  // ---- page ------------------------------------------------------------------------------------

  @Test
  void pageAndSizeAcceptNullsAndTheBounds() {
    assertDoesNotThrow(() -> validator.requirePage(null, null));
    assertDoesNotThrow(() -> validator.requirePage(0, 1));
    assertDoesNotThrow(() -> validator.requirePage(7, JoinRequestInboxValidator.MAX_PAGE_SIZE));
  }

  @Test
  void aNegativePageIsRefused() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requirePage(-1, 20));
    assertEquals("page must be 0 or more", e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -5, 201, 1000})
  void aSizeOutsideOneTo200IsRefused(int size) {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requirePage(0, size));
    assertEquals("size must be between 1 and 200", e.getMessage());
  }

  // ---- approve body ----------------------------------------------------------------------------

  @Test
  void modeIsParsedInAnyCase() {
    assertEquals(JoinApproveMode.CREATE, validator.requireMode("CREATE"));
    assertEquals(JoinApproveMode.LINK, validator.requireMode(" link "));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "MERGE", "create-new", "0"})
  void anUnknownModeIsRefused(String mode) {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireMode(mode));
    assertEquals("mode must be CREATE or LINK", e.getMessage());
  }

  @Test
  void aMissingBodyIsRefusedAsAMissingMode() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireApprove(null));
    assertEquals("mode must be CREATE or LINK", e.getMessage());
  }

  @Test
  void createIgnoresTheEmployeeId() {
    assertEquals(JoinApproveMode.CREATE,
        validator.requireApprove(new ApproveJoinRequestRequest("CREATE", null)));
    assertEquals(JoinApproveMode.CREATE,
        validator.requireApprove(new ApproveJoinRequestRequest("CREATE", "not-an-id")));
  }

  @Test
  void linkNeedsAnEmployeeId() {
    assertEquals(JoinApproveMode.LINK,
        validator.requireApprove(new ApproveJoinRequestRequest("LINK", EMPLOYEE_ID)));
    ValidationException missing = assertThrows(ValidationException.class,
        () -> validator.requireApprove(new ApproveJoinRequestRequest("LINK", null)));
    assertEquals("employeeId is required to link", missing.getMessage());
    ValidationException blank = assertThrows(ValidationException.class,
        () -> validator.requireApprove(new ApproveJoinRequestRequest("LINK", "  ")));
    assertEquals("employeeId is required to link", blank.getMessage());
  }

  @Test
  void linkRefusesAnEmployeeIdThatIsNotAnId() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireApprove(new ApproveJoinRequestRequest("LINK", "EMP-014")));
    assertEquals("employeeId must be a 24-character id", e.getMessage());
  }

  // ---- reason ----------------------------------------------------------------------------------

  @Test
  void aReasonUpTo200CharactersAfterTrimmingIsFine() {
    assertDoesNotThrow(() -> validator.requireReason(null));
    assertDoesNotThrow(() -> validator.requireReason(""));
    assertDoesNotThrow(() -> validator.requireReason("   "));
    assertDoesNotThrow(() -> validator.requireReason("x".repeat(200)));
    assertDoesNotThrow(() -> validator.requireReason("  " + "x".repeat(200) + "  "));
  }

  @Test
  void aReasonOver200CharactersIsRefusedWithTheServiceText() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireReason("x".repeat(201)));
    assertEquals("The reason must be at most 200 characters", e.getMessage());
  }

  // ---- bulk ids --------------------------------------------------------------------------------

  @Test
  void bulkIdsAreTrimmedDedupedAndKeptInOrder() {
    List<String> ids = validator.requireBulkIds(new BulkJoinRequest(
        Arrays.asList(" " + OBJECT_ID + " ", EMPLOYEE_ID, OBJECT_ID, null, "  "), null));
    assertEquals(List.of(OBJECT_ID, EMPLOYEE_ID), ids);
  }

  @Test
  void aBulkBodyWithoutIdsIsRefused() {
    assertEquals("Select at least one request",
        assertThrows(ValidationException.class, () -> validator.requireBulkIds(null))
            .getMessage());
    assertEquals("Select at least one request",
        assertThrows(ValidationException.class,
            () -> validator.requireBulkIds(new BulkJoinRequest(null, null))).getMessage());
    assertEquals("Select at least one request",
        assertThrows(ValidationException.class,
            () -> validator.requireBulkIds(new BulkJoinRequest(List.of(), null))).getMessage());
    assertEquals("Select at least one request",
        assertThrows(ValidationException.class,
            () -> validator.requireBulkIds(new BulkJoinRequest(Arrays.asList(null, " "), null)))
            .getMessage());
  }

  @Test
  void fiftyDistinctIdsAreFineAndFiftyOneAreNot() {
    List<String> fifty = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      fifty.add(String.format("%024x", i + 1));
    }
    assertEquals(50, validator.requireBulkIds(new BulkJoinRequest(fifty, null)).size());

    List<String> fiftyOne = new ArrayList<>(fifty);
    fiftyOne.add(String.format("%024x", 51));
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireBulkIds(new BulkJoinRequest(fiftyOne, null)));
    assertEquals("Select at most 50 requests at a time", e.getMessage());
  }

  @Test
  void repeatsDoNotCountTowardsTheFifty() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      ids.add(OBJECT_ID);
    }
    assertEquals(List.of(OBJECT_ID), validator.requireBulkIds(new BulkJoinRequest(ids, null)));
  }

  @Test
  void aBulkIdThatIsNotAnIdIsRefused() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.requireBulkIds(new BulkJoinRequest(List.of(OBJECT_ID, "req-2"), null)));
    assertTrue(e.getMessage().startsWith("ids"));
  }
}
