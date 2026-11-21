package com.inventory.hr.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.PairingCode;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
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
    // hr_app_nonces (2), hr_app_alerts (1)
    assertEquals(34, specs.size());
    assertEquals(specs.size(), keys.size());
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
    when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
    doThrow(new RuntimeException("conflict")).doReturn("ok")
        .when(indexOps).ensureIndex(any(IndexDefinition.class));

    migration.run();

    verify(indexOps, times(HrIndexMigration.specs().size())).ensureIndex(any(IndexDefinition.class));
    assertTrue(HrIndexMigration.NOTIFICATION_TTL_SECONDS > 0);
  }
}
