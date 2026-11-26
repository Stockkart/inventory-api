package com.inventory.hr.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.migration.HrIndexMigration.IndexSpec;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

@ExtendWith(MockitoExtension.class)
class HrIndexMigrationTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private IndexOperations indexOps;
  @InjectMocks private HrIndexMigration migration;

  private static IndexSpec spec(String name, Class<?> type) {
    return HrIndexMigration.specs().stream()
        .filter(s -> s.name().equals(name) && s.type() == type)
        .findFirst().orElseThrow();
  }

  @Test
  void everyDesignIndexIsListedOnce() {
    List<IndexSpec> specs = HrIndexMigration.specs();
    Set<String> keys = specs.stream()
        .map(s -> s.type().getSimpleName() + "." + s.name()).collect(Collectors.toSet());

    // 21 + hr_punches (2) + attendance app: hr_device_pairings (5), hr_pairing_codes (3),
    // hr_app_nonces (2), hr_app_alerts (1) + Stockkart Team: hr_join_requests (4),
    // hr_employees (3), hr_notifications (1). No hr_pairing_proofs and no link-request index (D11).
    assertEquals(42, specs.size());
    assertEquals(specs.size(), keys.size());
  }

  // ---- Stockkart Team (task 3.5) ----------------------------------------------------------------

  @Test
  void joinRequestInboxAndOutcomeReadsHaveNewestFirstIndexes() {
    IndexSpec inbox = spec("shop_status_requested", WorkerJoinRequest.class);
    assertEquals(new Document("shopId", 1).append("status", 1).append("requestedAt", -1),
        inbox.index().getIndexKeys());
    assertNull(inbox.index().getIndexOptions().get("unique"));
    assertNull(inbox.index().getIndexOptions().get("partialFilterExpression"));

    assertEquals(new Document("userId", 1).append("decidedAt", -1),
        spec("user_decided", WorkerJoinRequest.class).index().getIndexKeys());
  }

  @Test
  void oneOpenJoinRequestPerAccountIsEnforcedByAPartialUniqueIndex() {
    IndexSpec open = spec("user_open_unique", WorkerJoinRequest.class);
    Document options = open.index().getIndexOptions();

    assertEquals(new Document("userId", 1), open.index().getIndexKeys());
    assertEquals(true, options.get("unique"));
    assertEquals(new Document("status", new Document("$in", List.of("PENDING", "APPROVING"))),
        options.get("partialFilterExpression"));
  }

  @Test
  void decidedJoinRequestsExpireAtTheirOwnTimestamp() {
    assertEquals(0L, ttlSeconds(spec("expires_ttl", WorkerJoinRequest.class)));
    assertEquals(new Document("expiresAt", 1),
        spec("expires_ttl", WorkerJoinRequest.class).index().getIndexKeys());
  }

  /** Worker-app D11: the legacy pairing indexes are not ensured and are dropped when present. */
  @Test
  void theLegacyPairingIndexesAreNoLongerEnsuredAndAreListedForDropping() {
    assertTrue(HrIndexMigration.specs().stream().noneMatch(s ->
        s.name().equals("pairing_link_pending") || s.type().getSimpleName().equals("PairingProof")));
    assertEquals(List.of("token_hash_unique", "expires_ttl"),
        HrIndexMigration.DROPPED.get("hr_pairing_proofs"));
    assertEquals(List.of("pairing_link_pending"), HrIndexMigration.DROPPED.get("hr_join_requests"));
    assertEquals(2, HrIndexMigration.DROPPED.size());
  }

  @Test
  void dropRemovedTouchesOnlyCollectionsAndIndexesThatExist() {
    when(mongoTemplate.collectionExists("hr_pairing_proofs")).thenReturn(false);
    when(mongoTemplate.collectionExists("hr_join_requests")).thenReturn(true);
    when(mongoTemplate.indexOps("hr_join_requests")).thenReturn(indexOps);
    when(indexOps.getIndexInfo()).thenReturn(List.of(
        new IndexInfo(List.of(IndexField.create("userId", Sort.Direction.ASC)), "user_open_unique",
            true, false, null),
        new IndexInfo(List.of(IndexField.create("pairingId", Sort.Direction.ASC)),
            "pairing_link_pending", false, false, null)));

    migration.dropRemoved();

    verify(indexOps).dropIndex("pairing_link_pending");
    verify(indexOps, never()).dropIndex("user_open_unique");
    verify(mongoTemplate, never()).indexOps("hr_pairing_proofs");
  }

  @Test
  void dropRemovedIsANoOpWhenNothingIsLeftAndSwallowsAFailure() {
    when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
    when(mongoTemplate.indexOps(anyString())).thenReturn(indexOps);
    when(indexOps.getIndexInfo()).thenReturn(List.of())
        .thenThrow(new RuntimeException("listIndexes failed"));

    migration.dropRemoved();
    migration.dropRemoved();

    verify(indexOps, never()).dropIndex(anyString());
  }

  @Test
  void theExitJobScansOnlyLinkedEmployeesWhoseMembershipIsStillOpen() {
    IndexSpec exit = spec("exit_membership_pending", Employee.class);
    Document options = exit.index().getIndexOptions();

    assertEquals(new Document("status", 1).append("exitDate", 1), exit.index().getIndexKeys());
    assertNull(options.get("unique"));
    Document filter = new Document("userId", new Document("$type", 2))
        .append("membershipEndedAt", null);
    assertEquals(filter, options.get("partialFilterExpression"));
    // null must be in the filter as an explicit key, not dropped: that is the "not yet ended" part
    assertTrue(((Document) options.get("partialFilterExpression")).containsKey("membershipEndedAt"));
  }

  @Test
  void possibleMatchIndexesCoverOnlyEmployeesWithTheOptionalField() {
    IndexSpec email = spec("shop_email", Employee.class);
    assertEquals(new Document("shopId", 1).append("email", 1), email.index().getIndexKeys());
    assertEquals(new Document("email", new Document("$exists", true)),
        email.index().getIndexOptions().get("partialFilterExpression"));
    assertNull(email.index().getIndexOptions().get("unique"));

    IndexSpec phone = spec("shop_phone", Employee.class);
    assertEquals(new Document("shopId", 1).append("phone", 1), phone.index().getIndexKeys());
    assertEquals(new Document("phone", new Document("$exists", true)),
        phone.index().getIndexOptions().get("partialFilterExpression"));
    assertNull(phone.index().getIndexOptions().get("unique"));
  }

  @Test
  void workerNotificationsAreReadPerAccountNewestFirst() {
    IndexSpec byAccount = spec("recipient_created", HrNotification.class);
    assertEquals(new Document("recipientUserId", 1).append("createdAt", -1),
        byAccount.index().getIndexKeys());
    assertNull(byAccount.index().getIndexOptions().get("expireAfterSeconds"));
  }

  @Test
  void oneActivePairingPerEmployeeAndPerInstallationIsEnforcedByPartialIndexes() {
    Document activeOnly = new Document("status", "ACTIVE");

    Document perEmployee = spec("shop_employee_active_unique", DevicePairing.class).index()
        .getIndexOptions();
    assertEquals(new Document("shopId", 1).append("employeeId", 1),
        spec("shop_employee_active_unique", DevicePairing.class).index().getIndexKeys());
    assertEquals(true, perEmployee.get("unique"));
    assertEquals(activeOnly, perEmployee.get("partialFilterExpression"));

    Document perInstallation = spec("installation_active", DevicePairing.class).index()
        .getIndexOptions();
    assertEquals(new Document("installationId", 1),
        spec("installation_active", DevicePairing.class).index().getIndexKeys());
    assertEquals(activeOnly, perInstallation.get("partialFilterExpression"));
    assertNull(perInstallation.get("unique"));

    assertEquals(activeOnly, spec("scheduled_unpair_active", DevicePairing.class).index()
        .getIndexOptions().get("partialFilterExpression"));
    assertEquals(new Document("shopId", 1).append("employeeId", 1).append("status", 1),
        spec("shop_employee_status", DevicePairing.class).index().getIndexKeys());
  }

  @Test
  void unpairedPairingsAndCodesExpireAtTheirOwnTimestamps() {
    assertEquals(0L, ttlSeconds(spec("purge_ttl", DevicePairing.class)));
    assertEquals(new Document("purgeAt", 1),
        spec("purge_ttl", DevicePairing.class).index().getIndexKeys());
    assertEquals(0L, ttlSeconds(spec("expires_ttl", PairingCode.class)));
    assertEquals(new Document("expiresAt", 1),
        spec("expires_ttl", PairingCode.class).index().getIndexKeys());
  }

  @Test
  void pairingCodesAreUniqueByHashAndListedByEmployeeStatus() {
    IndexSpec unique = spec("code_hash_unique", PairingCode.class);
    assertEquals(new Document("codeHash", 1), unique.index().getIndexKeys());
    assertEquals(true, unique.index().getIndexOptions().get("unique"));
    assertEquals(new Document("shopId", 1).append("employeeId", 1).append("status", 1),
        spec("shop_employee_status", PairingCode.class).index().getIndexKeys());
  }

  @Test
  void noncesAreUniquePerPairingAndForgottenAfterFifteenMinutes() {
    IndexSpec unique = spec("pairing_nonce_unique", AppNonce.class);
    assertEquals(new Document("pairingId", 1).append("nonce", 1), unique.index().getIndexKeys());
    assertEquals(true, unique.index().getIndexOptions().get("unique"));
    assertEquals(900L, ttlSeconds(spec("seen_ttl", AppNonce.class)));
    assertEquals(new Document("seenAt", 1), spec("seen_ttl", AppNonce.class).index().getIndexKeys());
  }

  @Test
  void alertsAreReadByShopAndOpenState() {
    assertEquals(new Document("shopId", 1).append("clearedAt", 1),
        spec("shop_cleared", AppHealthAlert.class).index().getIndexKeys());
  }

  private static long ttlSeconds(IndexSpec spec) {
    return ((Number) spec.index().getIndexOptions().get("expireAfterSeconds")).longValue();
  }

  @Test
  void theLoginLinkIndexIsUniqueAndOnlyForLinkedRows() {
    Document options = spec("shop_user_unique", Employee.class).index().getIndexOptions();

    assertEquals(true, options.get("unique"));
    assertEquals(new Document("userId", new Document("$type", 2)),
        options.get("partialFilterExpression"));
  }

  @Test
  void punchesAreUniqueByShopAndDedupeKeyAndReadByEmployeeDay() {
    IndexSpec unique = spec("shop_dedupe_unique", com.inventory.hr.domain.model.Punch.class);
    assertEquals(new Document("shopId", 1).append("dedupeKey", 1), unique.index().getIndexKeys());
    assertEquals(true, unique.index().getIndexOptions().get("unique"));
    assertEquals(new Document("shopId", 1).append("employeeId", 1).append("localDate", 1),
        spec("shop_employee_date", com.inventory.hr.domain.model.Punch.class).index()
            .getIndexKeys());
  }

  @Test
  void theAdvanceListHasAShopDateIndexMatchingItsSort() {
    assertEquals(new Document("shopId", 1).append("date", 1).append("createdAt", 1),
        spec("shop_date", com.inventory.hr.domain.model.Advance.class).index().getIndexKeys());
  }

  @Test
  void notificationsExpireAfterNinetyDays() {
    Document options = spec("created_ttl", HrNotification.class).index().getIndexOptions();

    assertEquals(7_776_000L, ((Number) options.get("expireAfterSeconds")).longValue());
  }

  @Test
  void oneFailingIndexDoesNotStopTheOthers() {
    when(mongoTemplate.collectionExists(anyString())).thenReturn(false);
    when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
    doThrow(new RuntimeException("conflict")).doReturn("ok")
        .when(indexOps).ensureIndex(any(IndexDefinition.class));

    migration.run();

    verify(indexOps, times(HrIndexMigration.specs().size())).ensureIndex(any(IndexDefinition.class));
    assertTrue(HrIndexMigration.NOTIFICATION_TTL_SECONDS > 0);
  }
}
