package com.inventory.credit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.api.CreditBalanceStatus;
import com.inventory.credit.api.CreditPartyType;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A party's due against a real MongoDB, with many entries at once (including the party's very
 * first ones, which create the account).
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/credit -am test -Dtest='CreditBalancesIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class CreditBalancesIT {

  private static final String SHOP = "credit-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static CreditBalances balances;

  @BeforeAll
  static void connect() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = appLikeTemplate(client, db);
    balances = new CreditBalances(template);
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

  /** The unique index the app declares on credit_accounts (what makes a second first-entry fail). */
  @BeforeEach
  void freshCollection() {
    template.getDb().drop();
    template.getCollection("credit_accounts").createIndex(
        new Document("shopId", 1).append("partyType", 1).append("partyRefId", 1),
        new IndexOptions().unique(true).name("shop_party_unique"));
  }

  static class ToDecimal128 implements Converter<BigDecimal, Decimal128> {
    @Override
    public Decimal128 convert(BigDecimal source) {
      return Decimal128.parse(source.toString());
    }
  }

  static class FromDecimal128 implements Converter<Decimal128, BigDecimal> {
    @Override
    public BigDecimal convert(Decimal128 source) {
      return source.bigDecimalValue();
    }
  }

  private static MongoTemplate appLikeTemplate(MongoClient client, String db) {
    SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, db);
    MongoCustomConversions conversions = new MongoCustomConversions(List.of(new ToDecimal128(), new FromDecimal128()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return new MongoTemplate(factory, converter);
  }

  private List<CreditAccount> accounts(String party) {
    return template.find(
        Query.query(Criteria.where("shopId").is(SHOP).and("partyRefId").is(party)), CreditAccount.class);
  }

  /** {@code n} entries at once for one party; {@code mixed}: every third one a settlement of i/2. */
  private record Run(BigDecimal expected, Set<String> accountIds, List<BigDecimal> balances) {}

  private Run concurrently(String party, int n, boolean mixed) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(24);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<CreditBalances.Applied>> results = new ArrayList<>();
    BigDecimal expected = BigDecimal.ZERO;
    for (int i = 1; i <= n; i++) {
      BigDecimal delta = mixed && i % 3 == 0 ? new BigDecimal(i).negate().divide(new BigDecimal(2)) : new BigDecimal(i);
      expected = expected.add(delta);
      results.add(pool.submit(() -> {
        go.await();
        return balances.add(SHOP, CreditPartyType.CUSTOMER, party, "Ravi", "9999", delta, Instant.now());
      }));
    }
    go.countDown();
    Set<String> ids = new HashSet<>();
    List<BigDecimal> seen = new ArrayList<>();
    for (Future<CreditBalances.Applied> f : results) {
      CreditBalances.Applied a = f.get();
      ids.add(a.accountId());
      seen.add(a.balance().stripTrailingZeros());
    }
    pool.shutdown();
    return new Run(expected, ids, seen);
  }

  @Test
  void manyChargesAtOnceForANewPartyMakeOneAccountAndEachGetsItsOwnBalance() throws Exception {
    Run run = concurrently("c-1", 60, false);

    List<CreditAccount> stored = accounts("c-1");
    assertEquals(1, stored.size(), "one account, however many first entries raced");
    assertEquals(1, run.accountIds().size());
    assertEquals(0, run.expected().compareTo(stored.get(0).getCurrentBalance()), "nothing lost");
    assertEquals(60, new HashSet<>(run.balances()).size(), "no two charges got the same balance");
    assertEquals(CreditBalanceStatus.DUE, stored.get(0).getStatus());
    assertEquals("Ravi", stored.get(0).getPartyDisplayName());
    assertEquals(CreditPartyType.CUSTOMER, stored.get(0).getPartyType());
    assertEquals(stored.get(0).getCreatedAt() != null, true);
  }

  @Test
  void chargesAndSettlementsAtOnceLoseNothing() throws Exception {
    Run run = concurrently("c-mixed", 60, true);
    List<CreditAccount> stored = accounts("c-mixed");
    assertEquals(1, stored.size());
    assertEquals(0, run.expected().compareTo(stored.get(0).getCurrentBalance()));
  }

  @Test
  void statusFollowsTheNewBalance() {
    assertEquals(CreditBalanceStatus.DUE, status(balances.add(SHOP, CreditPartyType.VENDOR, "v-1", "Acme", null, new BigDecimal("100"), Instant.now())));
    assertEquals(CreditBalanceStatus.CLEAR, status(balances.add(SHOP, CreditPartyType.VENDOR, "v-1", "Acme", null, new BigDecimal("-100"), Instant.now())));
    assertEquals(CreditBalanceStatus.ADVANCE, status(balances.add(SHOP, CreditPartyType.VENDOR, "v-1", "Acme", null, new BigDecimal("-5"), Instant.now())));
  }

  private CreditBalanceStatus status(CreditBalances.Applied a) {
    return template.findById(a.accountId(), CreditAccount.class).getStatus();
  }

  @Test
  void anExistingAccountKeepsItsHistoryAndCreatedAt() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    template.getCollection("credit_accounts").insertOne(new Document("shopId", SHOP).append("partyType", "CUSTOMER")
        .append("partyRefId", "c-old").append("partyDisplayName", "Old name").append("currentBalance", new Decimal128(new BigDecimal("250.0000")))
        .append("status", "DUE").append("createdAt", java.util.Date.from(created)));

    CreditBalances.Applied a = balances.add(SHOP, CreditPartyType.CUSTOMER, "c-old", "New name", "8888", new BigDecimal("-50"), Instant.now());

    CreditAccount acc = template.findById(a.accountId(), CreditAccount.class);
    assertEquals(0, new BigDecimal("200").compareTo(acc.getCurrentBalance()));
    assertEquals(created, acc.getCreatedAt());
    assertEquals("New name", acc.getPartyDisplayName());
    assertEquals("8888", acc.getPartyPhone());
  }

  @Test
  void namesAreStoredAsTypedEvenWhenTheyLookLikeFieldPaths() {
    CreditBalances.Applied a = balances.add(SHOP, CreditPartyType.CUSTOMER, "c-2", "$currentBalance", null, BigDecimal.TEN, Instant.now());
    assertEquals("$currentBalance", template.findById(a.accountId(), CreditAccount.class).getPartyDisplayName());
  }

  @Test
  void undoTakesTheAmountBackAndResetsTheStatus() {
    balances.add(SHOP, CreditPartyType.CUSTOMER, "c-3", "Asha", null, new BigDecimal("75"), Instant.now());
    balances.undo(SHOP, CreditPartyType.CUSTOMER, "c-3", new BigDecimal("75"), Instant.now());
    CreditAccount acc = accounts("c-3").get(0);
    assertEquals(0, BigDecimal.ZERO.compareTo(acc.getCurrentBalance()));
    assertEquals(CreditBalanceStatus.CLEAR, acc.getStatus());
  }
}
