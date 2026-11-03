package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.UsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A completed bill is counted with one atomic increment (no read, no lost update), and old months
 * are purged only when a month record is created, not on every bill.
 */
class UsageServiceRecordTest {

  private MongoTemplate template;
  private UsageRepository usageRepository;
  private UsageService service;

  @BeforeEach
  void setUp() {
    template = mock(MongoTemplate.class);
    usageRepository = mock(UsageRepository.class);
    service = new UsageService();
    ReflectionTestUtils.setField(service, "mongoTemplate", template);
    ReflectionTestUtils.setField(service, "usageRepository", usageRepository);
  }

  private static Usage existing() {
    Usage u = new Usage();
    u.setShopId("shop-1");
    u.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
    return u;
  }

  @Test
  void completedBillIsOneAtomicIncrementOnThisMonth() {
    when(template.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Usage.class)))
        .thenReturn(existing());

    service.recordCompletedBill("shop-1", new BigDecimal("118.50"));

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<FindAndModifyOptions> o = ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(template).findAndModify(q.capture(), u.capture(), o.capture(), eq(Usage.class));
    assertThat(q.getValue().getQueryObject())
        .isEqualTo(new Document("shopId", "shop-1").append("month", service.getCurrentMonthKey()));
    Document inc = (Document) u.getValue().getUpdateObject().get("$inc");
    assertThat(inc.get("billCountUsed")).isEqualTo(1);
    assertThat(inc.get("billingAmountUsed")).isEqualTo(new Decimal128(new BigDecimal("118.50")));
    assertThat(inc.get("smsUsed")).isEqualTo(0);
    assertThat(o.getValue().isUpsert()).isTrue();
    // no read of the usage record and no purge on an ordinary bill
    verify(usageRepository, never()).findByShopIdAndMonth(anyString(), anyString());
    verify(usageRepository, never()).findByShopIdOrderByMonthDesc(anyString(), any(Pageable.class));
  }

  @Test
  void firstBillOfANewMonthPurgesMonthsBeyondTheRetainedWindow() {
    when(template.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Usage.class)))
        .thenAnswer(inv -> {
          Update update = inv.getArgument(1);
          Document onInsert = (Document) update.getUpdateObject().get("$setOnInsert");
          Usage created = new Usage();
          created.setCreatedAt((Instant) onInsert.get("createdAt"));
          return created;
        });
    when(usageRepository.findByShopIdOrderByMonthDesc(eq("shop-1"), any(Pageable.class))).thenReturn(List.of());

    service.recordCompletedBill("shop-1", BigDecimal.TEN);

    verify(usageRepository, times(1)).findByShopIdOrderByMonthDesc(eq("shop-1"), any(Pageable.class));
  }
}
