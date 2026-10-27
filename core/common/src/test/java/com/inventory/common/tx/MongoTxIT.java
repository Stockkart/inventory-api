package com.inventory.common.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * {@link MongoTx} against a real MongoDB replica set (transactions need one).
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo-rs -p 127.0.0.1:27019:27017 --tmpfs /data/db mongo:7 --replSet rs0
 *   docker exec stockkart-test-mongo-rs mongosh --quiet --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"127.0.0.1:27017"}]})'
 *   mvn -pl core/common test -Dtest='MongoTxIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.rs.uri='mongodb://127.0.0.1:27019/stockkart_tx?directConnection=true'
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.rs.uri", matches = ".+")
class MongoTxIT {

  private static MongoClient client;
  private static MongoTemplate template;
  private static MongoTx tx;

  @BeforeAll
  static void connect() {
    String uri = System.getProperty("stockkart.test.mongo.rs.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, db);
    template = new MongoTemplate(factory);
    tx = new MongoTx(factory, true, 8, 3, 15000);
  }

  @AfterAll
  static void close() {
    if (template != null) {
      template.getDb().drop();
    }
    if (client != null) {
      client.close();
    }
  }

  @BeforeEach
  void clean() {
    template.getDb().drop();
    // collections exist before the tests, as in the app
    template.createCollection("lots");
    template.createCollection("bills");
    template.createCollection("accounts");
    template.getCollection("lots").insertOne(new Document("_id", "lot-1").append("stock", 10));
    template.getCollection("accounts").insertOne(new Document("_id", "cash").append("balance", 0));
  }

  private int stock() {
    return template.getCollection("lots").find(Filters.eq("_id", "lot-1")).first().getInteger("stock");
  }

  private int cash() {
    return template.getCollection("accounts").find(Filters.eq("_id", "cash")).first().getInteger("balance");
  }

  private long bills() {
    return template.getCollection("bills").countDocuments();
  }

  /** One "bill": take stock (template), save the bill (template), move cash (raw collection). */
  private void bill(int qty, int amount) {
    template.updateFirst(Query.query(Criteria.where("_id").is("lot-1")), new Update().inc("stock", -qty), "lots");
    template.insert(new Document("qty", qty).append("amount", amount), "bills");
    template.getCollection("accounts").updateOne(Filters.eq("_id", "cash"), Updates.inc("balance", amount));
  }

  @Test
  void everythingIsSavedTogether() {
    tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> bill(2, 100));

    assertThat(stock()).isEqualTo(8);
    assertThat(bills()).isEqualTo(1);
    assertThat(cash()).isEqualTo(100);
  }

  @Test
  void aFailureAfterSomeWritesLeavesNothing() {
    assertThatThrownBy(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      bill(2, 100);
      throw new IllegalStateException("posting the journal failed");
    })).isInstanceOf(IllegalStateException.class);

    // stock, bill and the raw-collection cash move are all undone
    assertThat(stock()).isEqualTo(10);
    assertThat(bills()).isZero();
    assertThat(cash()).isZero();
  }

  @Test
  void aNestedRunIsPartOfTheOuterTransaction() {
    assertThatThrownBy(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      tx.run(MongoTx.CREDIT_CHARGE, "shop-1", () -> bill(1, 50)); // e.g. the due charged inside a bill
      throw new IllegalStateException("later step failed");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(stock()).isEqualTo(10);
    assertThat(cash()).isZero();
  }

  @Test
  void afterCommitRunsOnlyWhenSaved() {
    List<String> pushed = new CopyOnWriteArrayList<>();

    tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      bill(1, 10);
      MongoTx.afterCommit(() -> pushed.add("low stock: saved bill"));
      assertThat(pushed).isEmpty(); // not before the commit
    });
    assertThatThrownBy(() -> tx.run(MongoTx.BILL_COMPLETE, "shop-1", () -> {
      bill(1, 10);
      MongoTx.afterCommit(() -> pushed.add("low stock: failed bill"));
      throw new IllegalStateException("x");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(pushed).containsExactly("low stock: saved bill");
  }

  /** {@code n} bills at once on the same lot and Cash account; bill {@code i} is for shop {@code shopOf(i)}. */
  private void concurrentBills(int n, java.util.function.IntFunction<String> shopOf) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        String shop = shopOf.apply(i);
        done.add(pool.submit(() -> {
          go.await();
          tx.run(MongoTx.BILL_COMPLETE, shop, () -> bill(0, 1));
          return null;
        }));
      }
      go.countDown();
      for (Future<?> f : done) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void oneShopsBillsTakeTurnsAndAllCommit() throws Exception {
    MongoTx.Counts c = tx.counts().computeIfAbsent(MongoTx.BILL_COMPLETE, k -> new MongoTx.Counts());
    long retriedBefore = c.retried.get();

    concurrentBills(20, i -> "shop-1");

    assertThat(bills()).isEqualTo(20);
    assertThat(cash()).isEqualTo(20);
    assertThat(c.retried.get() - retriedBefore).as("turns, not conflicts").isZero();
  }

  @Test
  void conflictsAcrossTurnsAreRetriedUntilAllCommit() throws Exception {
    // different shop keys on the same documents: no shared turn (as two containers), so they conflict
    MongoTx.Counts c = tx.counts().computeIfAbsent(MongoTx.BILL_COMPLETE, k -> new MongoTx.Counts());
    long retriedBefore = c.retried.get();
    long failedBefore = c.failed.get();

    concurrentBills(8, i -> "shop-" + i);

    assertThat(bills()).isEqualTo(8);
    assertThat(cash()).isEqualTo(8);
    assertThat(c.failed.get() - failedBefore).isZero();
    assertThat(c.retried.get() - retriedBefore).as("some lost a write conflict and ran again").isPositive();
  }

  @Test
  void twoRefundsOfTheSameLineCannotBothPass() throws Exception {
    // a refund checks what was already refunded, then writes; without a transaction both read 0
    template.getCollection("lots").updateOne(Filters.eq("_id", "lot-1"), Updates.set("refunded", 0));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        results.add(pool.submit(() -> {
          go.await();
          try {
            tx.run(MongoTx.REFUND, "shop-1", () -> {
              int refunded = template.getCollection("lots").find(Filters.eq("_id", "lot-1")).first().getInteger("refunded");
              if (refunded + 5 > 5) {
                throw new IllegalStateException("already refunded");
              }
              sleep(200); // both have read before either writes
              template.getCollection("lots").updateOne(Filters.eq("_id", "lot-1"), Updates.inc("refunded", 5));
            });
            return true;
          } catch (IllegalStateException e) {
            return false;
          }
        }));
      }
      go.countDown();
      int passed = 0;
      for (Future<Boolean> f : results) {
        passed += f.get() ? 1 : 0;
      }
      assertThat(passed).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(template.getCollection("lots").find(Filters.eq("_id", "lot-1")).first().getInteger("refunded")).isEqualTo(5);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
