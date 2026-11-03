package com.inventory.common.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** The retry rules of {@link MongoTx}, without a database (the transaction itself is MongoTxIT). */
class MongoTxTest {

  /** Runs the callback as a "transaction" and counts how often. */
  static final class CountingTemplate extends TransactionTemplate {
    int executions;

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      executions++;
      return action.doInTransaction(null);
    }
  }

  /** What the driver throws for a write conflict inside a transaction. */
  static MongoCommandException writeConflict() {
    BsonDocument reply = new BsonDocument("ok", new BsonInt32(0))
        .append("code", new BsonInt32(112))
        .append("codeName", new BsonString("WriteConflict"))
        .append("errmsg", new BsonString("Write conflict during plan execution"))
        .append("errorLabels", new BsonArray(List.of(new BsonString("TransientTransactionError"))));
    return new MongoCommandException(reply, new ServerAddress());
  }

  private final CountingTemplate template = new CountingTemplate();

  @Test
  void aWriteConflictRunsTheWholeFlowAgain() {
    MongoTx tx = new MongoTx(template, true, 5, 1000);
    AtomicInteger calls = new AtomicInteger();

    String result = tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      if (calls.incrementAndGet() < 3) {
        throw new RuntimeException("Error updating purchase status", writeConflict()); // as services wrap it
      }
      return "saved";
    });

    assertThat(result).isEqualTo("saved");
    assertThat(template.executions).isEqualTo(3);
    MongoTx.Counts c = tx.counts().get(MongoTx.BILL_COMPLETE);
    assertThat(c.committed.get()).isEqualTo(1);
    assertThat(c.retried.get()).isEqualTo(2);
    assertThat(c.failed.get()).isZero();
  }

  @Test
  void otherErrorsAreNotRetried() {
    MongoTx tx = new MongoTx(template, true, 5, 1000);

    assertThatThrownBy(() -> tx.run(MongoTx.REFUND, "shop-1", () -> {
      throw new IllegalStateException("Can only refund completed purchases");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(template.executions).isEqualTo(1);
    assertThat(tx.counts().get(MongoTx.REFUND).failed.get()).isEqualTo(1);
  }

  @Test
  void givesUpAfterMaxAttemptsWithTheLastError() {
    MongoTx tx = new MongoTx(template, true, 3, 1000);

    assertThatThrownBy(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      throw new RuntimeException(writeConflict());
    })).satisfies(e -> assertThat(MongoTx.isWriteConflict(e)).isTrue());

    assertThat(template.executions).isEqualTo(3);
    MongoTx.Counts c = tx.counts().get(MongoTx.BILL_COMPLETE);
    assertThat(c.retried.get()).isEqualTo(2);
    assertThat(c.failed.get()).isEqualTo(1);
  }

  @Test
  void aConflictAServiceRethrewWithoutItsCauseIsRetriedToo() {
    MongoTx tx = new MongoTx(template, true, 5, 1000);
    AtomicInteger calls = new AtomicInteger();

    String result = tx.run(MongoTx.VENDOR_INVOICE, "shop-1", () -> {
      if (calls.incrementAndGet() == 1) {
        MongoTx.noteCommandFailure(writeConflict()); // the driver listener saw it
        throw new RuntimeException("Error processing inventory"); // cause dropped by the service
      }
      return "saved";
    });

    assertThat(result).isEqualTo("saved");
    assertThat(template.executions).isEqualTo(2);
  }

  @Test
  void aConflictNotedInAnEarlierFlowDoesNotLeakIntoTheNext() {
    MongoTx tx = new MongoTx(template, true, 5, 1000);
    MongoTx.noteCommandFailure(writeConflict()); // e.g. a failed command outside any flow

    assertThatThrownBy(() -> tx.run(MongoTx.REFUND, "shop-1", () -> {
      throw new IllegalStateException("validation");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(template.executions).isEqualTo(1);
  }

  @Test
  void switchedOffRunsTheWorkWithoutATransaction() {
    MongoTx tx = new MongoTx(template, false, 5, 1000);

    assertThat(tx.run(MongoTx.REFUND, "shop-1", () -> "done")).isEqualTo("done");
    assertThat(template.executions).isZero();
  }

  @Test
  void aShopWhoseTurnDoesNotComeIsBusyAndNothingRuns() throws Exception {
    MongoTx tx = new MongoTx(new TransactionTemplate() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(null);
      }
    }, true, 5, 50);
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread first = new Thread(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      inside.countDown();
      await(release);
    }));
    first.start();
    inside.await();
    AtomicInteger ran = new AtomicInteger();
    try {
      assertThatThrownBy(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", ran::incrementAndGet))
          .isInstanceOf(BaseException.class)
          .satisfies(e -> assertThat(((BaseException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_BUSY));
      // another shop does not wait for shop-1
      assertThat(tx.run(MongoTx.BILL_COMPLETE, "shop-2", () -> "other shop")).isEqualTo("other shop");
    } finally {
      release.countDown();
      first.join();
    }
    assertThat(ran.get()).isZero();
    assertThat(tx.counts().get(MongoTx.BILL_COMPLETE).busy.get()).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void afterCommitRunsAtOnceWhenNoTransactionIsOpen() {
    List<String> done = new ArrayList<>();

    MongoTx.afterCommit(() -> done.add("pushed"));

    assertThat(done).containsExactly("pushed");
  }
}
