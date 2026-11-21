package com.inventory.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.domain.model.LedgerEntry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

class LedgerIndexMigrationTest {

  @Test
  void ensuresTheNewestRowPerAccountIndexWithItsExistingName() {
    MongoTemplate template = mock(MongoTemplate.class);
    IndexOperations ops = mock(IndexOperations.class);
    when(template.indexOps(LedgerEntry.class)).thenReturn(ops);
    LedgerIndexMigration runner = new LedgerIndexMigration();
    ReflectionTestUtils.setField(runner, "mongoTemplate", template);

    runner.run();

    ArgumentCaptor<IndexDefinition> index = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(ops).ensureIndex(index.capture());
    assertThat(index.getValue().getIndexKeys())
        .isEqualTo(new Document("shopId", 1).append("accountId", 1).append("postedAt", -1));
    assertThat(index.getValue().getIndexOptions().get("name")).isEqualTo("shop_account_time");
  }

  @Test
  void aFailureIsLoggedAndBootContinues() {
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.indexOps(LedgerEntry.class)).thenThrow(new IllegalStateException("down"));
    LedgerIndexMigration runner = new LedgerIndexMigration();
    ReflectionTestUtils.setField(runner, "mongoTemplate", template);

    runner.run(); // no exception
  }
}
