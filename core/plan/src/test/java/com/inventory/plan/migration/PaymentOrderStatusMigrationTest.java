package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** Plan order and transaction indexes; the unique paymentOrderId index only when no duplicates exist. */
class PaymentOrderStatusMigrationTest {

  private final MongoTemplate template = mock(MongoTemplate.class);
  private final IndexOperations orders = mock(IndexOperations.class);
  private final IndexOperations transactions = mock(IndexOperations.class);
  private final PaymentOrderStatusMigration migration = new PaymentOrderStatusMigration();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    when(template.indexOps(PlanPaymentOrder.class)).thenReturn(orders);
    when(template.indexOps(PlanTransaction.class)).thenReturn(transactions);
    IndexInfo old = mock(IndexInfo.class);
    when(old.getName()).thenReturn("paymentOrderId");
    when(transactions.getIndexInfo()).thenReturn(List.of(old));
  }

  private void duplicates(List<Document> rows) {
    @SuppressWarnings("unchecked")
    AggregationResults<Document> results = mock(AggregationResults.class);
    when(results.getMappedResults()).thenReturn(rows);
    when(template.aggregate(any(Aggregation.class), eq(PlanTransaction.class), eq(Document.class))).thenReturn(results);
  }

  @Test
  void createsTheOrderAndTransactionIndexesAndReplacesThePaymentOrderIndex() {
    duplicates(List.of());

    migration.ensureIndexes();

    assertThat(names(orders)).contains("provider_providerOrderId_unique", "shop_status_paidAt",
        "shop_status_fulfilledAt", "shop_requestHash_status", "status_expiresAt");
    assertThat(names(transactions)).contains("shop_createdAt", "paymentOrderId_unique");
    verify(transactions).dropIndex("paymentOrderId");
  }

  @Test
  void duplicatePaymentOrderRowsKeepTheOldIndex() {
    duplicates(List.of(new Document("_id", "order-1").append("count", 2)));

    migration.ensureIndexes();

    assertThat(names(transactions)).doesNotContain("paymentOrderId_unique").contains("paymentOrderId");
    verify(transactions, never()).dropIndex("paymentOrderId");
  }

  @Test
  void aFailedUniqueIndexPutsTheOldOneBack() {
    duplicates(List.of());
    doThrow(new IllegalStateException("E11000")).when(transactions).ensureIndex(org.mockito.ArgumentMatchers.argThat(
        (org.springframework.data.mongodb.core.index.IndexDefinition i) ->
            "paymentOrderId_unique".equals(i.getIndexOptions().get("name"))));

    migration.ensureIndexes();

    assertThat(names(transactions)).contains("paymentOrderId");
  }

  private static List<String> names(IndexOperations ops) {
    ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
    verify(ops, org.mockito.Mockito.atLeastOnce()).ensureIndex(captor.capture());
    List<String> names = new ArrayList<>();
    captor.getAllValues().forEach(i -> names.add(String.valueOf(i.getIndexOptions().get("name"))));
    return names;
  }
}
