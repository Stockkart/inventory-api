package com.inventory.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.UserAccount;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

class UserTokenIndexMigrationTest {

  private static List<IndexDefinition> runAndCapture() {
    MongoTemplate template = mock(MongoTemplate.class);
    IndexOperations ops = mock(IndexOperations.class);
    when(template.indexOps(UserAccount.class)).thenReturn(ops);
    UserTokenIndexMigration runner = new UserTokenIndexMigration();
    ReflectionTestUtils.setField(runner, "mongoTemplate", template);

    runner.run();

    ArgumentCaptor<IndexDefinition> index = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(ops, times(2)).ensureIndex(index.capture());
    return index.getAllValues();
  }

  @Test
  void ensuresTheTokenLookupIndexWithItsExistingName() {
    IndexDefinition token = runAndCapture().get(0);
    assertThat(token.getIndexKeys()).isEqualTo(new Document("tokens.accessToken", 1));
    assertThat(token.getIndexOptions().get("name")).isEqualTo("tokens_accessToken");
    assertThat(token.getIndexOptions().containsKey("partialFilterExpression")).isFalse();
  }

  /** worker-app R2.5, DD1: workers by shop, with staff and legacy owners kept out of the index. */
  @Test
  void ensuresThePartialWorkerShopIndex() {
    IndexDefinition worker = runAndCapture().get(1);
    assertThat(worker.getIndexKeys()).isEqualTo(new Document("shopId", 1));
    assertThat(worker.getIndexOptions().get("name")).isEqualTo("worker_shopId");
    assertThat(worker.getIndexOptions().get("partialFilterExpression"))
        .isEqualTo(new Document("role", "WORKER"));
  }
}
