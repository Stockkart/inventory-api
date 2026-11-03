package com.inventory.hr.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrNotification;
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

    // 21 + hr_punches: unique (shopId, dedupeKey) and (shopId, employeeId, localDate)
    assertEquals(23, specs.size());
    assertEquals(specs.size(), keys.size());
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
