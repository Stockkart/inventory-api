package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.Usage;
import java.math.BigDecimal;
import java.util.List;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/** Duplicate month records are merged into the oldest one before the unique index is created. */
class UsageIndexMigrationTest {

  private final MongoTemplate template = mock(MongoTemplate.class);
  private final IndexOperations indexOps = mock(IndexOperations.class);
  private final UsageIndexMigration migration = new UsageIndexMigration();

  UsageIndexMigrationTest() {
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    when(template.indexOps(Usage.class)).thenReturn(indexOps);
  }

  private void groups(Document... groups) {
    when(template.aggregate(any(Aggregation.class), eq("usage"), eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(groups), new Document()));
  }

  @Test
  void anExtraRecordIsRemovedAndItsCountsAddedToTheOldest() {
    groups(new Document("ids", List.of("keep", "extra")).append("n", 2));
    when(template.findAndRemove(any(Query.class), eq(Document.class), eq("usage")))
        .thenReturn(new Document("_id", "extra").append("billCountUsed", 3).append("ocrUsed", 2)
            .append("billingAmountUsed", new Decimal128(new BigDecimal("150.50"))));

    migration.run();

    ArgumentCaptor<Query> target = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(template).updateFirst(target.capture(), update.capture(), eq("usage"));
    assertThat(target.getValue().getQueryObject().get("_id")).isEqualTo("keep");
    Document inc = (Document) update.getValue().getUpdateObject().get("$inc");
    assertThat(inc.get("billCountUsed")).isEqualTo(3);
    assertThat(inc.get("ocrUsed")).isEqualTo(2);
    assertThat(inc.get("billingAmountUsed")).isEqualTo(new Decimal128(new BigDecimal("150.50")));
    assertThat(inc).doesNotContainKey("smsUsed");
  }

  @Test
  void aRecordAnotherRunAlreadyRemovedAddsNothing() {
    groups(new Document("ids", List.of("keep", "gone")).append("n", 2));

    migration.run();

    verify(template, never()).updateFirst(any(Query.class), any(Update.class), eq("usage"));
  }

  @Test
  void theUniqueIndexIsEnsured() {
    groups();

    migration.run();

    ArgumentCaptor<Index> index = ArgumentCaptor.forClass(Index.class);
    verify(indexOps).ensureIndex(index.capture());
    Document options = index.getValue().getIndexOptions();
    assertThat(options.get("name")).isEqualTo(UsageIndexMigration.INDEX);
    assertThat(options.get("unique")).isEqualTo(true);
    assertThat(index.getValue().getIndexKeys()).containsEntry("shopId", 1).containsEntry("month", 1);
  }
}
