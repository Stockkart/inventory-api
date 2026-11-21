package com.inventory.accounting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
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
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * The per-account ledger balance against a real MongoDB, under many concurrent postings.
 *
 * <pre>
 *   docker run -d --rm --name stockkart-test-mongo -p 27018:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/accounting -am test -Dtest='AccountLedgerBalancesIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AccountLedgerBalancesIT {

  private static final String SHOP = "ledger-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static AccountLedgerBalances balances;
  private static AccountService accountService;
  private ObjectId accountId;

  @BeforeAll
  static void connect() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = appLikeTemplate(client, db);
    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    balances = new AccountLedgerBalances(template, repos.getRepository(LedgerEntryRepository.class));
    accountService =
        new AccountService(
            repos.getRepository(AccountRepository.class), mock(ChartOfAccountsSeeder.class), mock(MetricsWrapper.class), template);
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

  /** An account posted to before this change: no ledgerBalance yet, newest ledger row at 150. */
  @BeforeEach
  void seed() {
    template.getDb().drop();
    accountId = new ObjectId();
    template.getCollection("coa_accounts").insertOne(
        new Document("_id", accountId).append("shopId", SHOP).append("code", "1100").append("name", "Cash in Hand")
            .append("type", "ASSET").append("normalBalance", "DEBIT").append("system", true).append("active", true));
    template.getCollection("ledger_entries").insertMany(List.of(
        row(Instant.parse("2026-10-01T10:00:00Z"), "100.0000"),
        row(Instant.parse("2026-10-02T10:00:00Z"), "150.0000")));
  }

  private Document row(Instant postedAt, String balanceAfter) {
    return new Document("shopId", SHOP).append("accountId", accountId.toHexString())
        .append("postedAt", Date.from(postedAt)).append("balanceAfter", new Decimal128(new BigDecimal(balanceAfter)));
  }

  /** As the app's MongoConfig: BigDecimal is stored as Decimal128. */
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

  private BigDecimal stored() {
    Document d = template.getCollection("coa_accounts").find(new Document("_id", accountId)).first();
    Object v = d.get(AccountLedgerBalances.FIELD);
    return v == null ? null : ((Decimal128) v).bigDecimalValue();
  }

  /** {@code n} postings at once, amounts 1, 2, … n (in rupees and paise), from {@code threads} threads. */
  private List<AccountLedgerBalances.Moved> concurrently(int n, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<AccountLedgerBalances.Moved>> results = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      BigDecimal amount = new BigDecimal(i).add(new BigDecimal("0.25"));
      results.add(pool.submit(() -> {
        go.await();
        return balances.add(SHOP, accountId.toHexString(), amount);
      }));
    }
    go.countDown();
    List<AccountLedgerBalances.Moved> out = new ArrayList<>();
    for (Future<AccountLedgerBalances.Moved> f : results) {
      out.add(f.get());
    }
    pool.shutdown();
    return out;
  }

  @Test
  void manyPostingsAtOnceAreAllCountedAndEachGetsItsOwnBalance() throws Exception {
    int n = 60;
    List<AccountLedgerBalances.Moved> moves = concurrently(n, 24);
    List<BigDecimal> after = moves.stream().map(AccountLedgerBalances.Moved::balance).toList();

    // 150 (newest row) + Σ (i + 0.25)
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 1; i <= n; i++) {
      sum = sum.add(new BigDecimal(i)).add(new BigDecimal("0.25"));
    }
    BigDecimal expected = new BigDecimal("150").add(sum);
    assertEquals(0, expected.compareTo(stored()), "nothing lost: " + stored() + " vs " + expected);
    Set<BigDecimal> distinct = new HashSet<>();
    for (BigDecimal b : after) {
      distinct.add(b.stripTrailingZeros());
    }
    assertEquals(n, distinct.size(), "no two postings got the same balance");
    assertEquals(0, expected.compareTo(Collections.max(after)), "the last one ends at the account's balance");

    // in time order the balances chain: each is the previous one plus its own amount
    List<AccountLedgerBalances.Moved> byTime = new ArrayList<>(moves);
    byTime.sort(java.util.Comparator.comparing(AccountLedgerBalances.Moved::at));
    BigDecimal prev = new BigDecimal("150");
    Instant prevAt = Instant.parse("2026-10-02T10:00:00Z"); // the newest row before
    for (AccountLedgerBalances.Moved m : byTime) {
      assertTrue(m.at().isAfter(prevAt), "times strictly increase: " + m.at() + " after " + prevAt);
      assertTrue(m.balance().compareTo(prev) > 0, "every amount here is positive");
      prev = m.balance();
      prevAt = m.at();
    }
    // amounts are distinct (i + 0.25), so each step must be exactly one of them
    Set<BigDecimal> amounts = new HashSet<>();
    for (int i = 1; i <= n; i++) {
      amounts.add(new BigDecimal(i).add(new BigDecimal("0.25")).stripTrailingZeros());
    }
    prev = new BigDecimal("150");
    for (AccountLedgerBalances.Moved m : byTime) {
      assertTrue(amounts.remove(m.balance().subtract(prev).stripTrailingZeros()), "step " + m.balance().subtract(prev));
      prev = m.balance();
    }
    assertTrue(amounts.isEmpty());
  }

  @Test
  void theFirstPostingAfterTheChangeStartsFromTheNewestRowOnlyOnce() throws Exception {
    assertNull(stored());
    // every one of these arrives while the field is missing
    concurrently(20, 20);
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 1; i <= 20; i++) {
      sum = sum.add(new BigDecimal(i)).add(new BigDecimal("0.25"));
    }
    assertEquals(0, new BigDecimal("150").add(sum).compareTo(stored()));
  }

  @Test
  void anAccountNeverPostedToStartsFromZero() {
    template.getCollection("ledger_entries").deleteMany(new Document());
    assertEquals(0, new BigDecimal("12.5000").compareTo(balances.add(SHOP, accountId.toHexString(), new BigDecimal("12.5")).balance()));
  }

  @Test
  void undoTakesTheAmountBack() {
    balances.add(SHOP, accountId.toHexString(), new BigDecimal("40"));
    balances.undo(SHOP, accountId.toHexString(), new BigDecimal("40"));
    assertEquals(0, new BigDecimal("150").compareTo(stored()));
  }

  @Test
  void anotherShopsAccountIsNotTouched() {
    assertThrows(IllegalStateException.class, () -> balances.add("other-shop", accountId.toHexString(), BigDecimal.ONE));
    assertNull(stored());
  }

  @Test
  void renamingAnAccountKeepsItsBalance() {
    balances.add(SHOP, accountId.toHexString(), new BigDecimal("50"));
    template.getCollection("coa_accounts").updateOne(new Document("_id", accountId), new Document("$set", new Document("system", false)));

    accountService.update(SHOP, accountId.toHexString(), "Cash box", false);

    Document d = template.getCollection("coa_accounts").find(new Document("_id", accountId)).first();
    assertEquals("Cash box", d.getString("name"));
    assertEquals(false, d.getBoolean("active"));
    assertEquals(0, new BigDecimal("200").compareTo(stored()), "a rename must not drop or rewind the balance");
    assertTrue(d.get("updatedAt") instanceof Date);
  }
}
