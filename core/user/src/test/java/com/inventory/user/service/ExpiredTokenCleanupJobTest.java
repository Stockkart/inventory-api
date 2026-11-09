package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.UserAccount;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The nightly job pulls only tokens that expired before the cutoff, in one update. */
class ExpiredTokenCleanupJobTest {

  @Test
  void pullsTokensExpiredBeforeTheCutoffFromUsersThatHaveThem() {
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.updateMulti(any(Query.class), any(Update.class), eq(UserAccount.class)))
        .thenReturn(UpdateResult.acknowledged(4, 3L, null));
    Instant cutoff = Instant.parse("2026-10-19T22:00:00Z");

    long changed = new ExpiredTokenCleanupJob(template).removeExpiredTokens(cutoff);

    assertThat(changed).isEqualTo(3);
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
    verify(template).updateMulti(q.capture(), u.capture(), eq(UserAccount.class));
    assertThat(q.getValue().getQueryObject())
        .isEqualTo(new Document("tokens.expiresAt", new Document("$lt", Date.from(cutoff))));
    assertThat(u.getValue().getUpdateObject())
        .isEqualTo(new Document("$pull",
            new Document("tokens", new Document("expiresAt", new Document("$lt", Date.from(cutoff))))));
  }
}
