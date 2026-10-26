package com.inventory.accounting.migration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.accounting.service.AccountLedgerBalances;
import com.inventory.accounting.service.AccountService;
import com.inventory.accounting.service.AccountingPostingService;
import com.inventory.accounting.service.ChartOfAccountsSeeder;
import com.inventory.accounting.service.JournalEntrySequenceService;
import com.inventory.accounting.utils.constants.AccountingMetricsConstants;
import com.inventory.common.tx.MongoTx;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.Assumptions;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link JournalSourceIndexMigration} against a real MongoDB: the partial unique index lets
 * any number of entries without a source exist, makes concurrent posts of one source end in one
 * entry, and never stops start-up when old data already has duplicates.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class JournalSourceIndexIT {

  private static final String SHOP = "journal-index-shop";

  private static MongoClient client;
  private static MongoTemplate template;
  private static AccountingPostingService posting;
  private static ChartOfAccountsSeeder seeder;
  private MetricsWrapper metrics;
  private JournalSourceIndexMigration migration;

  @BeforeAll
  static void connect() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_journal_index";
    template = appLikeTemplate(client, db);
    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    AccountRepository accounts = repos.getRepository(AccountRepository.class);
    LedgerEntryRepository ledger = repos.getRepository(LedgerEntryRepository.class);
    seeder = new ChartOfAccountsSeeder(accounts, ledger);
    MetricsWrapper postingMetrics = mock(MetricsWrapper.class);
    AccountService accountService = new AccountService(accounts, seeder, postingMetrics, template);
    posting = new AccountingPostingService(repos.getRepository(JournalEntryRepository.class),
        ledger, accountService, new JournalEntrySequenceService(template), postingMetrics,
        new AccountLedgerBalances(template, ledger));
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
  void fresh() {
    template.getDb().drop();
    metrics = mock(MetricsWrapper.class);
    migration = new JournalSourceIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    ReflectionTestUtils.setField(migration, "metrics", metrics);
    // seeded once up front: concurrent first seeds of a shop are a separate (known) race
    seeder.seedShop(SHOP);
  }

  private static PostJournalRequest request(JournalSource type, String sourceId) {
    PostJournalLine dr = new PostJournalLine();
    dr.setAccountCode("1100");
    dr.setDebit(new BigDecimal("10"));
    PostJournalLine cr = new PostJournalLine();
    cr.setAccountCode("4100");
    cr.setCredit(new BigDecimal("10"));
    PostJournalRequest r = new PostJournalRequest();
    r.setSourceType(type);
    r.setSourceId(sourceId);
    r.setLines(List.of(dr, cr));
    return r;
  }

  private static long journals(Document filter) {
    return template.getCollection("journal_entries").countDocuments(filter);
  }

  @Test
  void theIndexIsUniqueForSourcedEntriesOnlyAndRepeatable() {
    migration.run();
    migration.run();

    Document index = new ArrayList<>(template.getCollection("journal_entries").listIndexes()
        .into(new ArrayList<>())).stream()
        .filter(i -> JournalSourceIndexMigration.INDEX.equals(i.getString("name")))
        .findFirst().orElseThrow();
    assertEquals(true, index.getBoolean("unique"));
    assertEquals(new Document("sourceId", new Document("$type", 2)),
        index.get("partialFilterExpression"));
    // entries without a source never collide
    posting.post(SHOP, "user-1", request(JournalSource.MANUAL, null));
    posting.post(SHOP, "user-1", request(JournalSource.MANUAL, null));
    assertEquals(2, journals(new Document("shopId", SHOP).append("sourceType", "MANUAL")));
    verify(metrics, never()).increment(any(), anyDouble(), any(String[].class));
    // the repository's source lookup (equality + $type) may use the partial index
    Document plan = template.getCollection("journal_entries").find(new Document("shopId", SHOP)
        .append("sourceType", "PAYROLL_RUN").append("sourceId",
            new Document("$eq", "run-1:0").append("$type", "string")))
        .explain(com.mongodb.ExplainVerbosity.QUERY_PLANNER);
    String winning = plan.get("queryPlanner", Document.class).get("winningPlan").toString();
    assertTrue(winning.contains(JournalSourceIndexMigration.INDEX), winning);
  }

  @Test
  void concurrentPostsOfOneSourceLeaveOneEntry() throws Exception {
    migration.run();
    for (int round = 0; round < 5; round++) {
      String sourceId = "run-race:" + round;
      int threads = 6;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<JournalEntry>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(pool.submit(() -> {
          go.await();
          return posting.post(SHOP, "user-1", request(JournalSource.PAYROLL_RUN, sourceId));
        }));
      }
      go.countDown();
      Set<String> ids = new HashSet<>();
      for (Future<JournalEntry> f : results) {
        ids.add(f.get().getId());
      }
      pool.shutdown();

      assertEquals(1, ids.size(), "every caller gets the same entry");
      assertEquals(1, journals(new Document("shopId", SHOP).append("sourceId", sourceId)));
      // only the winner wrote ledger rows (one per line)
      assertEquals(2, template.getCollection("ledger_entries")
          .countDocuments(new Document("shopId", SHOP).append("sourceId", sourceId)));
    }
  }

  @Test
  void concurrentPostsOfOneSourceInTransactionsFromTwoContainersLeaveOneEntry() throws Exception {
    // transactions need a replica set (single-node rs0 is enough); skipped on a standalone
    Document hello = template.getDb().runCommand(new Document("hello", 1));
    Assumptions.assumeTrue(hello.containsKey("setName"), "MongoDB is not a replica set");
    migration.run();
    // collections and the entry counter exist before the transactions, as in the app
    posting.post(SHOP, "user-1", request(JournalSource.MANUAL, null));
    // two MongoTx = two containers: their per-shop turns do not see each other
    MongoTx[] containers = {
        new MongoTx(template.getMongoDatabaseFactory(), true, 8, 3, 15000),
        new MongoTx(template.getMongoDatabaseFactory(), true, 8, 3, 15000)};
    for (int round = 0; round < 5; round++) {
      String sourceId = "run-tx-race:" + round;
      int threads = 6;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<JournalEntry>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        MongoTx tx = containers[i % 2];
        results.add(pool.submit(() -> {
          go.await();
          return tx.run("journal_post_it", SHOP,
              () -> posting.post(SHOP, "user-1", request(JournalSource.PAYROLL_RUN, sourceId)));
        }));
      }
      go.countDown();
      Set<String> ids = new HashSet<>();
      for (Future<JournalEntry> f : results) {
        ids.add(f.get().getId());
      }
      pool.shutdown();

      assertEquals(1, ids.size(), "every caller gets the same entry");
      assertEquals(1, journals(new Document("shopId", SHOP).append("sourceId", sourceId)));
      assertEquals(2, template.getCollection("ledger_entries")
          .countDocuments(new Document("shopId", SHOP).append("sourceId", sourceId)));
    }
  }

  @Test
  void duplicatesInOldDataAreReportedAndStartUpGoesOn() {
    for (int i = 0; i < 2; i++) {
      template.getCollection("journal_entries").insertOne(new Document("shopId", SHOP)
          .append("sourceType", "SALE").append("sourceId", "inv-dup").append("status", "POSTED"));
    }

    assertDoesNotThrow(() -> migration.run());

    assertTrue(template.getCollection("journal_entries").listIndexes().into(new ArrayList<>())
        .stream().noneMatch(i -> JournalSourceIndexMigration.INDEX.equals(i.getString("name"))));
    verify(metrics).increment(eq(AccountingMetricsConstants.INDEX_FAILURES_TOTAL), eq(1.0),
        any(String[].class));
    assertEquals("1 duplicated source(s), 1 extra entr(y/ies); clean them up and restart to"
        + " create the index.", migration.duplicateSummary());
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
    MongoCustomConversions conversions =
        new MongoCustomConversions(List.of(new ToDecimal128(), new FromDecimal128()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter converter =
        new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return new MongoTemplate(factory, converter);
  }
}
