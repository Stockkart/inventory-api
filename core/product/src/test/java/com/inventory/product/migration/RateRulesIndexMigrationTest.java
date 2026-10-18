package com.inventory.product.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateRulesIndexMigrationTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private IndexOperations indexOps;
  @InjectMocks private RateRulesIndexMigration migration;

  @BeforeEach
  void setUp() {
    when(mongoTemplate.indexOps(ShopRateRulesDocument.class)).thenReturn(indexOps);
  }

  @Test
  void ensuresTheUniqueShopIdIndex() {
    migration.run();

    ArgumentCaptor<IndexDefinition> index = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(indexOps).ensureIndex(index.capture());
    assertEquals(new Document("shopId", 1), index.getValue().getIndexKeys());
    assertEquals(Boolean.TRUE, index.getValue().getIndexOptions().get("unique"));
  }

  /** Documents saved before @Version get version 0; only those still without the field. */
  @Test
  void versionsOnlyDocumentsWithoutAVersion() {
    when(mongoTemplate.count(any(Query.class), eq(ShopRateRulesDocument.class))).thenReturn(2L);
    UpdateResult result = mock(UpdateResult.class);
    when(result.getModifiedCount()).thenReturn(2L);
    when(mongoTemplate.updateMulti(any(Query.class), any(UpdateDefinition.class),
        eq(ShopRateRulesDocument.class))).thenReturn(result);

    migration.run();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongoTemplate).updateMulti(query.capture(), update.capture(), eq(ShopRateRulesDocument.class));
    assertEquals(new Document("version", new Document("$exists", false)), query.getValue().getQueryObject());
    assertEquals(new Document("$set", new Document("version", 0L)), update.getValue().getUpdateObject());
  }

  @Test
  void touchesNothingWhenEveryDocumentHasAVersion() {
    when(mongoTemplate.count(any(Query.class), eq(ShopRateRulesDocument.class))).thenReturn(0L);

    migration.run();

    verify(mongoTemplate, never()).updateMulti(any(Query.class), any(UpdateDefinition.class),
        eq(ShopRateRulesDocument.class));
  }

  /** A failing index step does not stop the version backfill. */
  @Test
  void anIndexFailureStillRunsTheBackfill() {
    when(indexOps.ensureIndex(any())).thenThrow(new IllegalStateException("index build failed"));
    when(mongoTemplate.count(any(Query.class), eq(ShopRateRulesDocument.class))).thenReturn(1L);
    UpdateResult result = mock(UpdateResult.class);
    when(mongoTemplate.updateMulti(any(Query.class), any(UpdateDefinition.class),
        eq(ShopRateRulesDocument.class))).thenReturn(result);

    migration.run();

    verify(mongoTemplate).updateMulti(any(Query.class), any(UpdateDefinition.class),
        eq(ShopRateRulesDocument.class));
  }
}
