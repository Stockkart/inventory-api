package com.inventory.common.tx;

import com.mongodb.MongoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * {@link MongoTransactionManager} that retries the commit when the driver reports {@code
 * UnknownTransactionCommitResult} (the commit may or may not have been applied, e.g. the primary
 * stepped down). Retrying the commit is safe: MongoDB applies a transaction's commit once.
 *
 * <p>Not a Spring bean on purpose: a {@code PlatformTransactionManager} bean would make Spring
 * Boot switch on every {@code @Transactional} in the code base at once (AGENTS.md P8). Only
 * {@link MongoTx} uses it.
 */
@Slf4j
class RetryingCommitMongoTransactionManager extends MongoTransactionManager {

  static final String UNKNOWN_COMMIT_RESULT = MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL;

  private final int maxCommitAttempts;

  RetryingCommitMongoTransactionManager(MongoDatabaseFactory factory, int maxCommitAttempts) {
    super(factory);
    this.maxCommitAttempts = Math.max(1, maxCommitAttempts);
  }

  @Override
  protected void doCommit(MongoTransactionObject transactionObject) throws Exception {
    for (int attempt = 1; ; attempt++) {
      try {
        transactionObject.commitTransaction();
        return;
      } catch (MongoException e) {
        if (attempt >= maxCommitAttempts || !e.hasErrorLabel(UNKNOWN_COMMIT_RESULT)) {
          throw e;
        }
        log.warn("[tx] commit result unknown (attempt {} of {}), committing again: {}",
            attempt, maxCommitAttempts, e.getMessage());
      }
    }
  }
}
