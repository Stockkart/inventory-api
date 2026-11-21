package com.inventory.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.UserAccount;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

class UserTokenIndexMigrationTest {

  @Test
  void ensuresTheTokenLookupIndexWithItsExistingName() {
    MongoTemplate template = mock(MongoTemplate.class);
    IndexOperations ops = mock(IndexOperations.class);
    when(template.indexOps(UserAccount.class)).thenReturn(ops);
    UserTokenIndexMigration runner = new UserTokenIndexMigration();
    ReflectionTestUtils.setField(runner, "mongoTemplate", template);

    runner.run();

    ArgumentCaptor<IndexDefinition> index = ArgumentCaptor.forClass(IndexDefinition.class);
    verify(ops).ensureIndex(index.capture());
    assertThat(index.getValue().getIndexKeys()).isEqualTo(new Document("tokens.accessToken", 1));
    assertThat(index.getValue().getIndexOptions().get("name")).isEqualTo("tokens_accessToken");
  }
}
