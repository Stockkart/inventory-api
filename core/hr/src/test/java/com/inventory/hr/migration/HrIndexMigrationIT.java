package com.inventory.hr.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.AppHealthAlertRepository;
import com.inventory.hr.domain.repository.AppNonceRepository;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link HrIndexMigration} against a real, fresh MongoDB (HR-module R10.11–R10.13): every
 * Attendance app index exists with its unique / partial / TTL options, a second run changes
 * nothing, and the queries the services actually issue — captured from the driver as the real
 * repositories send them and re-run under {@code explain("executionStats")} — walk an index
 * ({@code IXSCAN} / {@code IDHACK}) and never scan a collection ({@code COLLSCAN}). The worker
 * tests at the end do the same for the Stockkart Team indexes (worker-app R14.5): join
 * requests, the exit job's employee read and the account notification inbox, plus the
 * database-enforced one-open-request rule of the partial unique index (R3.5), and check that the
 * indexes of the removed legacy pairing flows (D11) are dropped when a database still has them.
 *
 * <p>Each explain is printed (stage tree, index, keys and documents examined) so the plans can
 * be reviewed from the surefire output before a release (R10.13).
 *
 * <pre>
 *   mvn -pl core/hr -am test -Dtest='HrIndexMigrationIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27018/stockkart_test
 * </pre>
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class HrIndexMigrationIT {

  private static final String SHOP = "shop-1";
  private static final String E1 = "emp-1";
  private static final String E2 = "emp-2";
  private static final String E3 = "emp-3";
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  private static MongoClient client;
  private static MongoTemplate template;
  private static CapturingListener captured;
  private static DevicePairingRepository pairings;
  private static PairingCodeRepository codes;
  private static AppNonceRepository nonces;
  private static PunchRepository punches;
  private static AppHealthAlertRepository alerts;

  /** The ACTIVE pairing of {@link #E1}; its id is the per-request lookup key. */
  private static DevicePairing activePairing;

  @BeforeAll
  static void setUp() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    captured = new CapturingListener();
    client = MongoClients.create(MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(uri))
        .addCommandListener(captured)
        .build());
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_hridx";
    template = new MongoTemplate(client, db);
    template.getDb().drop();
    seedLegacyIndexes();

    runMigration();

    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    pairings = repos.getRepository(DevicePairingRepository.class);
    codes = repos.getRepository(PairingCodeRepository.class);
    nonces = repos.getRepository(AppNonceRepository.class);
    punches = repos.getRepository(PunchRepository.class);
    alerts = repos.getRepository(AppHealthAlertRepository.class);
    seed();
  }

  @AfterAll
  static void tearDown() {
    if (template != null) {
      template.getDb().drop();
    }
    if (client != null) {
      client.close();
    }
  }

  private static void runMigration() {
    HrIndexMigration migration = new HrIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    migration.run();
  }

  /**
   * What an earlier worker-app build left behind (D11): the {@code hr_pairing_proofs} indexes and
   * the link-request index on {@code hr_join_requests}, created exactly as that build created them,
   * so the first {@link #runMigration} has something to drop and the assertions below prove it did.
   */
  private static void seedLegacyIndexes() {
    template.indexOps("hr_pairing_proofs").ensureIndex(
        new Index().on("tokenHash", Sort.Direction.ASC).unique().named("token_hash_unique"));
    template.indexOps("hr_pairing_proofs").ensureIndex(
        new Index().on("expiresAt", Sort.Direction.ASC).expire(0).named("expires_ttl"));
    template.indexOps("hr_join_requests").ensureIndex(
        new Index().on("pairingId", Sort.Direction.ASC).named("pairing_link_pending")
            .partial(PartialIndexFilter.of(Criteria.where("kind").is("LINK")
                .and("status").is("PENDING"))));
    assertEquals(Set.of("_id_", "token_hash_unique", "expires_ttl"),
        indexesByName("hr_pairing_proofs").keySet());
    assertTrue(indexesByName("hr_join_requests").containsKey("pairing_link_pending"));
  }

  /** A handful of rows per collection, so every explain has something to find and to skip. */
  private static void seed() {
    activePairing = template.insert(pairing(E1, PairingStatus.ACTIVE, "inst-1",
        NOW.minus(1, ChronoUnit.HOURS)));
    template.insert(pairing(E1, PairingStatus.UNPAIRED, "inst-0", null));
    template.insert(pairing(E2, PairingStatus.ACTIVE, "inst-2", null));
    template.insert(pairing(E3, PairingStatus.UNPAIRED, "inst-3", null));

    template.insert(code(E1, PairingCodeStatus.UNUSED, "hash-1", null));
    template.insert(code(E1, PairingCodeStatus.CANCELLED, "hash-0", null));
    template.insert(code(E2, PairingCodeStatus.CLAIMED, "hash-2", "claim-2"));

    template.insert(new AppNonce(null, activePairing.getId(), "nonce-1", NOW));
    template.insert(new AppNonce(null, activePairing.getId(), "nonce-2", NOW));

    template.insert(new AppHealthAlert(null, SHOP, 0.05, 5, 100, NOW, null));
    template.insert(new AppHealthAlert(null, SHOP, 0.02, 2, 100, NOW.minus(2, ChronoUnit.DAYS),
        NOW.minus(1, ChronoUnit.DAYS)));
    template.insert(new AppHealthAlert(null, "shop-2", 0.03, 3, 100, NOW, null));

    for (String date : List.of("2026-03-02", "2026-03-03", "2026-03-04")) {
      template.insert(punch(E1, date, PunchDirection.IN, "09:00"));
      template.insert(punch(E1, date, PunchDirection.OUT, "18:00"));
      template.insert(day(E1, date));
    }
    template.insert(punch(E2, "2026-03-03", PunchDirection.IN, "10:00"));
    template.insert(day(E2, "2026-03-03"));
  }

  private static DevicePairing pairing(String employeeId, PairingStatus status,
      String installationId, Instant scheduledUnpairAt) {
    DevicePairing p = new DevicePairing();
    p.setShopId(SHOP);
    p.setEmployeeId(employeeId);
    p.setStatus(status);
    p.setInstallationId(installationId);
    p.setPunchPublicKey("punch-key");
    p.setDevicePublicKey("device-key");
    p.setPairedAt(NOW.minus(10, ChronoUnit.DAYS));
    p.setCreatedAt(NOW.minus(10, ChronoUnit.DAYS));
    p.setScheduledUnpairAt(scheduledUnpairAt);
    if (status == PairingStatus.UNPAIRED) {
      p.setUnpairedAt(NOW.minus(1, ChronoUnit.DAYS));
      p.setPurgeAt(NOW.plus(89, ChronoUnit.DAYS));
    }
    return p;
  }

  private static PairingCode code(String employeeId, PairingCodeStatus status, String codeHash,
      String claimTokenHash) {
    PairingCode c = new PairingCode();
    c.setShopId(SHOP);
    c.setEmployeeId(employeeId);
    c.setStatus(status);
    c.setCodeHash(codeHash);
    c.setClaimTokenHash(claimTokenHash);
    c.setCreatedBy("owner");
    c.setCreatedAt(NOW);
    // in the future, so the TTL monitor cannot remove the row while the test runs
    c.setExpiresAt(NOW.plus(1, ChronoUnit.HOURS));
    c.setAttempts(0);
    return c;
  }

  private static Punch punch(String employeeId, String date, PunchDirection direction,
      String time) {
    Punch p = new Punch();
    p.setShopId(SHOP);
    p.setEmployeeId(employeeId);
    p.setAt(Instant.parse(date + "T" + time + ":00Z"));
    p.setLocalDate(date);
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setDirection(direction);
    p.setDedupeKey("B:" + employeeId + ":" + date + "-" + direction);
    p.setCreatedAt(p.getAt());
    return p;
  }

  private static AttendanceDay day(String employeeId, String date) {
    AttendanceDay d = new AttendanceDay();
    d.setShopId(SHOP);
    d.setEmployeeId(employeeId);
    d.setDate(date);
    d.setPeriod(date.substring(0, 7));
    d.setStatus(AttendanceStatus.PRESENT);
    d.setSource(AttendanceSource.BIOMETRIC_APP);
    d.setMarkedAt(NOW);
    return d;
  }

  // ---- indexes -------------------------------------------------------------------------------

  private static Map<String, Document> indexesByName(String collection) {
    Map<String, Document> byName = new TreeMap<>();
    for (Document d : template.getCollection(collection).listIndexes()) {
      byName.put(d.getString("name"), d);
    }
    return byName;
  }

  private static Document index(Map<String, Document> indexes, String name, Document key) {
    Document index = indexes.get(name);
    assertNotNull(index, "index " + name + " missing; have " + indexes.keySet());
    assertEquals(key, index.get("key", Document.class), name + " key");
    return index;
  }

  private static void plain(Map<String, Document> indexes, String name, Document key) {
    Document index = index(indexes, name, key);
    assertNull(index.get("unique"), name + " must not be unique");
    assertNull(index.get("partialFilterExpression"), name + " must not be partial");
    assertNull(index.get("expireAfterSeconds"), name + " must not be a TTL index");
  }

  private static void ttl(Map<String, Document> indexes, String name, Document key,
      long seconds) {
    Document index = index(indexes, name, key);
    assertEquals(seconds, ((Number) index.get("expireAfterSeconds")).longValue(), name + " TTL");
    assertNull(index.get("unique"), name + " must not be unique");
  }

  private static final Document ACTIVE_ONLY = new Document("status", "ACTIVE");

  @Test
  void devicePairingIndexesHaveTheirUniquePartialAndTtlOptions() {
    Map<String, Document> idx = indexesByName("hr_device_pairings");
    assertEquals(Set.of("_id_", "shop_employee_active_unique", "shop_employee_status",
        "installation_active", "scheduled_unpair_active", "purge_ttl"), idx.keySet());

    Document perEmployee = index(idx, "shop_employee_active_unique",
        new Document("shopId", 1).append("employeeId", 1));
    assertEquals(true, perEmployee.get("unique"));
    assertEquals(ACTIVE_ONLY, perEmployee.get("partialFilterExpression"));

    plain(idx, "shop_employee_status",
        new Document("shopId", 1).append("employeeId", 1).append("status", 1));

    Document perInstallation = index(idx, "installation_active", new Document("installationId", 1));
    assertNull(perInstallation.get("unique"));
    assertEquals(ACTIVE_ONLY, perInstallation.get("partialFilterExpression"));

    Document scheduled = index(idx, "scheduled_unpair_active",
        new Document("scheduledUnpairAt", 1));
    assertNull(scheduled.get("unique"));
    assertEquals(ACTIVE_ONLY, scheduled.get("partialFilterExpression"));

    ttl(idx, "purge_ttl", new Document("purgeAt", 1), 0);
  }

  @Test
  void pairingCodeIndexesAreUniqueByHashListedByEmployeeStatusAndExpire() {
    Map<String, Document> idx = indexesByName("hr_pairing_codes");
    assertEquals(Set.of("_id_", "code_hash_unique", "shop_employee_status", "expires_ttl"),
        idx.keySet());

    Document byHash = index(idx, "code_hash_unique", new Document("codeHash", 1));
    assertEquals(true, byHash.get("unique"));
    assertNull(byHash.get("partialFilterExpression"));

    plain(idx, "shop_employee_status",
        new Document("shopId", 1).append("employeeId", 1).append("status", 1));
    ttl(idx, "expires_ttl", new Document("expiresAt", 1), 0);
  }

  @Test
  void nonceIndexesAreUniquePerPairingAndForgottenAfterFifteenMinutes() {
    Map<String, Document> idx = indexesByName("hr_app_nonces");
    assertEquals(Set.of("_id_", "pairing_nonce_unique", "seen_ttl"), idx.keySet());

    Document unique = index(idx, "pairing_nonce_unique",
        new Document("pairingId", 1).append("nonce", 1));
    assertEquals(true, unique.get("unique"));
    ttl(idx, "seen_ttl", new Document("seenAt", 1), 900);
  }

  @Test
  void alertIndexIsByShopAndClearedState() {
    Map<String, Document> idx = indexesByName("hr_app_alerts");
    assertEquals(Set.of("_id_", "shop_cleared"), idx.keySet());
    plain(idx, "shop_cleared", new Document("shopId", 1).append("clearedAt", 1));
  }

  @Test
  void punchAndAttendanceDayIndexesBackTheAppReads() {
    Map<String, Document> punchIdx = indexesByName("hr_punches");
    assertEquals(Set.of("_id_", "shop_dedupe_unique", "shop_employee_date"), punchIdx.keySet());
    assertEquals(true, index(punchIdx, "shop_dedupe_unique",
        new Document("shopId", 1).append("dedupeKey", 1)).get("unique"));
    plain(punchIdx, "shop_employee_date",
        new Document("shopId", 1).append("employeeId", 1).append("localDate", 1));

    Map<String, Document> dayIdx = indexesByName("hr_attendance_days");
    assertEquals(Set.of("_id_", "shop_employee_date_unique", "shop_date"), dayIdx.keySet());
    assertEquals(true, index(dayIdx, "shop_employee_date_unique",
        new Document("shopId", 1).append("employeeId", 1).append("date", 1)).get("unique"));
    plain(dayIdx, "shop_date", new Document("shopId", 1).append("date", 1));
  }

  @Test
  void runningTheMigrationAgainChangesNoIndex() {
    List<String> collections = List.of("hr_device_pairings", "hr_pairing_codes", "hr_app_nonces",
        "hr_app_alerts", "hr_punches", "hr_attendance_days", "hr_join_requests",
        "hr_employees", "hr_notifications");
    Map<String, Map<String, Document>> before = new TreeMap<>();
    for (String c : collections) {
      before.put(c, indexesByName(c));
    }

    runMigration();
    runMigration();

    for (String c : collections) {
      assertEquals(before.get(c), indexesByName(c), c + " indexes changed on a re-run");
    }
    // the seeded rows survived: ensureIndex never dropped or rebuilt a collection
    assertEquals(4, template.getCollection("hr_device_pairings").countDocuments());
    assertEquals(7, template.getCollection("hr_punches").countDocuments());
  }

  // ---- explain plans -------------------------------------------------------------------------

  /**
   * Keeps the last {@code find} / {@code count} / {@code aggregate} the driver sent, as the
   * repository built it (filter, sort, projection, limit, maxTimeMS; Spring Data's {@code
   * exists*} finders run an aggregate). Cloned inside the callback: the driver reuses the
   * command's buffer once the event returns.
   */
  static final class CapturingListener implements CommandListener {
    private static final Set<String> EXPLAINABLE = Set.of("find", "count", "aggregate",
        "distinct", "update", "delete", "findAndModify");
    volatile BsonDocument last;

    @Override
    public void commandStarted(CommandStartedEvent event) {
      if (EXPLAINABLE.contains(event.getCommandName())) {
        last = event.getCommand().clone();
      }
    }
  }

  /** One reviewed plan: the winning plan's stages (outermost first) and the indexes it uses. */
  record Plan(String label, String collection, List<String> stages, Set<String> indexes,
      Document executionStats) {

    boolean usesIndex() {
      // COUNT_SCAN is a count answered from the index keys alone (no document is fetched)
      return stages.stream().anyMatch(s -> s.equals("IXSCAN") || s.equals("IDHACK")
          || s.equals("COUNT_SCAN") || s.startsWith("EXPRESS_"));
    }

    boolean collectionScan() {
      return stages.contains("COLLSCAN");
    }

    void assertIndexed(Set<String> acceptableIndexes) {
      assertFalse(collectionScan(), label + " scans " + collection + ": " + this);
      assertTrue(usesIndex(), label + " walks no index: " + this);
      if (!acceptableIndexes.isEmpty()) {
        assertTrue(acceptableIndexes.containsAll(indexes),
            label + " uses " + indexes + ", expected one of " + acceptableIndexes + ": " + this);
      }
    }

    @Override
    public String toString() {
      return String.format("%-42s %-20s stages=%s index=%s nReturned=%s keys=%s docs=%s",
          label, collection, stages, indexes, executionStats.get("nReturned"),
          executionStats.get("totalKeysExamined"), executionStats.get("totalDocsExamined"));
    }
  }

  /**
   * Runs {@code query} through the real repository, takes the command the driver sent and asks
   * the server for its {@code executionStats} explain.
   */
  private static Plan explain(String label, String collection, Runnable query) {
    captured.last = null;
    query.run();
    BsonDocument command = captured.last;
    assertNotNull(command, label + " sent no explainable command");
    String commandName = command.getFirstKey();
    assertEquals(collection, command.getString(commandName).getValue(),
        label + " ran against another collection");

    BsonDocument inner = new BsonDocument();
    for (Map.Entry<String, org.bson.BsonValue> e : command.entrySet()) {
      // session, cluster time and $db are the driver's envelope, not part of the query
      if (!e.getKey().startsWith("$") && !e.getKey().equals("lsid")) {
        inner.append(e.getKey(), e.getValue());
      }
    }
    Document result = template.getDb().runCommand(
        new BsonDocument("explain", inner).append("verbosity", new BsonString("executionStats")));

    // find / count explains carry queryPlanner at the top; an aggregate (Spring Data's exists*
    // runs $match → $limit → $count) nests it under stages[0].$cursor
    Document planner = firstDocument(result, "queryPlanner");
    Document stats = firstDocument(result, "executionStats");
    assertNotNull(planner, label + " explain has no queryPlanner: " + result.toJson());
    assertNotNull(stats, label + " explain has no executionStats: " + result.toJson());
    List<String> stages = new ArrayList<>();
    Set<String> indexes = new LinkedHashSet<>();
    collectStages(planner.get("winningPlan"), stages, indexes);
    Plan plan = new Plan(label, collection, stages, indexes, stats);
    System.out.println("[hr-explain] " + plan);
    return plan;
  }

  /** The first sub-document stored under {@code key}, searching depth-first. */
  private static Document firstDocument(Object node, String key) {
    if (node instanceof Document d) {
      if (d.get(key) instanceof Document found) {
        return found;
      }
      for (Object v : d.values()) {
        Document found = firstDocument(v, key);
        if (found != null) {
          return found;
        }
      }
    } else if (node instanceof List<?> l) {
      for (Object v : l) {
        Document found = firstDocument(v, key);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /**
   * Every {@code stage} and {@code indexName} in the winning plan, in document order (so the
   * outermost stage comes first). Recurses through {@code inputStage}, {@code inputStages} and,
   * on servers that run the slot-based engine, the nested {@code queryPlan}.
   */
  private static void collectStages(Object node, List<String> stages, Set<String> indexes) {
    if (node instanceof Document d) {
      if (d.get("stage") instanceof String s) {
        stages.add(s);
      }
      if (d.get("indexName") instanceof String s) {
        indexes.add(s);
      }
      for (Object v : d.values()) {
        collectStages(v, stages, indexes);
      }
    } else if (node instanceof List<?> l) {
      for (Object v : l) {
        collectStages(v, stages, indexes);
      }
    }
  }

  @Test
  void devicePairingReadsUseTheirIndexes() {
    // per-request: the pairing the X-Pairing-Id header names (AttendanceAppAuthInterceptor)
    Plan byId = explain("pairing by id (findById)", "hr_device_pairings",
        () -> assertTrue(pairings.findById(activePairing.getId()).isPresent()));
    assertTrue(byId.stages().contains("IDHACK")
            || byId.stages().stream().anyMatch(s -> s.startsWith("EXPRESS_")),
        "pairing-id lookup must be an _id point read: " + byId);
    assertFalse(byId.collectionScan(), byId.toString());

    // the envelope check variant that pins the status (R5.7)
    explain("pairing by id and status", "hr_device_pairings",
        () -> assertTrue(pairings.findByIdAndStatus(activePairing.getId(),
            PairingStatus.ACTIVE).isPresent()))
        .assertIndexed(Set.of("_id_"));

    // the employee's active pairing (switch check, My HR fallback, manager screens)
    explain("active pairing per employee", "hr_device_pairings",
        () -> assertTrue(pairings.findByShopIdAndEmployeeIdAndStatus(SHOP, E1,
            PairingStatus.ACTIVE).isPresent()))
        .assertIndexed(Set.of("shop_employee_active_unique", "shop_employee_status"));

    // register / employee list: paired state of many employees at once (R8.12)
    explain("active pairings for $in employees", "hr_device_pairings",
        () -> assertEquals(2, pairings.findByShopIdAndEmployeeIdInAndStatus(SHOP,
            List.of(E1, E2, E3), PairingStatus.ACTIVE).size()))
        .assertIndexed(Set.of("shop_employee_active_unique", "shop_employee_status"));

    // settings count of paired employees (R1.9)
    explain("active pairings of a shop", "hr_device_pairings",
        () -> assertEquals(2, pairings.findByShopIdAndStatus(SHOP, PairingStatus.ACTIVE).size()))
        .assertIndexed(Set.of("shop_employee_active_unique", "shop_employee_status"));

    // one phone, one active pairing (R2.11)
    explain("active pairing per installation", "hr_device_pairings",
        () -> assertTrue(pairings.findByInstallationIdAndStatus("inst-1",
            PairingStatus.ACTIVE).isPresent()))
        .assertIndexed(Set.of("installation_active"));

    // PairingExitJob: status ACTIVE and scheduledUnpairAt <= now (R3.4)
    explain("exit job: due scheduled unpairs", "hr_device_pairings",
        () -> assertEquals(1, pairings.findByStatusAndScheduledUnpairAtLessThanEqual(
            PairingStatus.ACTIVE, NOW).size()))
        .assertIndexed(Set.of("scheduled_unpair_active"));
  }

  @Test
  void pairingCodeAndNonceReadsUseTheirIndexes() {
    // claim: the typed code, by hash (R2.5)
    explain("code by hash", "hr_pairing_codes",
        () -> assertTrue(codes.findByCodeHash("hash-1").isPresent()))
        .assertIndexed(Set.of("code_hash_unique"));

    // create: cancel the employee's earlier UNUSED code (R2.4); pending list by status
    explain("codes by employee and status", "hr_pairing_codes",
        () -> assertEquals(1, codes.findByShopIdAndEmployeeIdAndStatus(SHOP, E1,
            PairingCodeStatus.UNUSED).size()))
        .assertIndexed(Set.of("shop_employee_status"));

    // Reviewed, not asserted: complete / abandon find the claimed code by claimTokenHash
    // (DevicePairingService.claimedCode) and no index carries that field, so this is a COLLSCAN
    // over hr_pairing_codes. Flagged for a decision (index, or look the code up by codeHash too).
    explain("code by claim token hash (no index)", "hr_pairing_codes",
        () -> assertTrue(codes.findByClaimTokenHash("claim-2").isPresent()));

    // replay check: has this pairing already sent this nonce?
    explain("nonce existence", "hr_app_nonces",
        () -> assertTrue(nonces.existsByPairingIdAndNonce(activePairing.getId(), "nonce-1")))
        .assertIndexed(Set.of("pairing_nonce_unique"));
  }

  @Test
  void punchAndAttendanceDayReadsUseTheirIndexes() {
    // AppPunchService: idempotency by dedupe key, before and after the insert race (R5.10)
    explain("punch by dedupe key", "hr_punches",
        () -> assertTrue(punches.findByShopIdAndDedupeKey(SHOP,
            "B:" + E1 + ":2026-03-03-IN").isPresent()))
        .assertIndexed(Set.of("shop_dedupe_unique"));

    // PunchDayService: one employee-day, oldest first
    explain("punches of one employee-day", "hr_punches",
        () -> assertEquals(2, punches.findByShopIdAndEmployeeIdAndLocalDateOrderByAtAsc(SHOP, E1,
            "2026-03-03").size()))
        .assertIndexed(Set.of("shop_employee_date"));

    // register flags: several employees, several days (PunchDayService.punchesByEmployeeDate)
    explain("punches of several employee-days", "hr_punches",
        () -> assertEquals(3, template.find(Query.query(Criteria.where("shopId").is(SHOP)
            .and("employeeId").in(List.of(E1, E2)).and("localDate").in(List.of("2026-03-03"))),
            Punch.class).size()))
        .assertIndexed(Set.of("shop_employee_date"));

    // the Attendance app's history screen: 31-day window, newest first (R4.10)
    Plan history = explain("31-day history, newest first", "hr_punches",
        () -> assertEquals(6, punches.findRangeNewestFirst(SHOP, E1, "2026-02-03",
            "2026-03-05").size()));
    history.assertIndexed(Set.of("shop_employee_date"));
    // the sort is on `at`, which the index does not carry, so an in-memory SORT over the
    // employee's ≤ 31 days of punches is the expected shape; what matters is no COLLSCAN
    assertTrue(history.stages().contains("SORT"), "history sorts in memory: " + history);

    // AttendanceService.days: the register / daily screen read (shopId, employeeId $in, date range)
    explain("attendance days of employees in a range", "hr_attendance_days",
        () -> assertEquals(4, template.find(Query.query(Criteria.where("shopId").is(SHOP)
            .and("employeeId").in(List.of(E1, E2)).and("date").gte("2026-03-01")
            .lte("2026-03-31")), AttendanceDay.class).size()))
        .assertIndexed(Set.of("shop_employee_date_unique", "shop_date"));

    // PunchDayService.writeDerived: the upsert filter of one employee-day
    explain("attendance day of one employee-day", "hr_attendance_days",
        () -> assertEquals(1, template.find(Query.query(Criteria.where("shopId").is(SHOP)
            .and("employeeId").is(E1).and("date").is("2026-03-03")), AttendanceDay.class).size()))
        .assertIndexed(Set.of("shop_employee_date_unique"));
  }

  @Test
  void alertReadsAreReviewed() {
    // the shop's open alert (R10.5–R10.6): shop_cleared, clearedAt = null is an index key
    explain("open alert of a shop", "hr_app_alerts",
        () -> assertTrue(alerts.findByShopIdAndClearedAtIsNull(SHOP).isPresent()))
        .assertIndexed(Set.of("shop_cleared"));

    // Known COLLSCAN, deliberately not asserted: the dashboard's "every open alert" read has no
    // shopId, so (shopId, clearedAt) cannot serve it. hr_app_alerts holds at most a few rows per
    // shop and this read runs once per dashboard load, so the scan is accepted (checkpoint 17).
    Plan allOpen = explain("every open alert (known COLLSCAN)", "hr_app_alerts",
        () -> assertEquals(2, alerts.findByClearedAtIsNull().size()));
    assertEquals(2, allOpen.executionStats().get("nReturned"));
  }

  // ---- Stockkart Team (worker-app R3.5, R4.12, R5.2, R10.1, R11.3, R13.5, R14.5) ----------------

  private static final String W1 = "worker-user-1";
  private static final String W2 = "worker-user-2";
  private static final String W3 = "worker-user-3";
  private static WorkerJoinRequestRepository joinRequests;
  private static boolean workerSeeded;

  /** Join requests, linked employees and account notifications for the worker explains. */
  private static void seedWorker() {
    if (workerSeeded) {
      return;
    }
    workerSeeded = true;
    joinRequests = new MongoRepositoryFactory(template).getRepository(
        WorkerJoinRequestRepository.class);

    // W1 and W2: one open request each to SHOP; W3: decided ones
    template.insert(joinRequest(W1, WorkerJoinStatus.PENDING, NOW.minus(2, ChronoUnit.HOURS),
        null));
    template.insert(joinRequest(W2, WorkerJoinStatus.PENDING, NOW.minus(1, ChronoUnit.HOURS),
        null));
    template.insert(joinRequest(W3, WorkerJoinStatus.REJECTED, NOW.minus(3, ChronoUnit.DAYS),
        NOW.minus(2, ChronoUnit.DAYS)));
    template.insert(joinRequest(W3, WorkerJoinStatus.WITHDRAWN, NOW.minus(10, ChronoUnit.DAYS),
        NOW.minus(9, ChronoUnit.DAYS)));
    WorkerJoinRequest other = joinRequest("someone-else", WorkerJoinStatus.PENDING,
        NOW.minus(5, ChronoUnit.MINUTES), null);
    other.setShopId("shop-2");
    template.insert(other);

    // employees: E1 linked and exited yesterday with the membership still open (the exit job's
    // row), E2 linked and ACTIVE, E3 unlinked; e-mail and phone on two of them (possible match)
    template.insert(employee(E1, W1, EmployeeStatus.EXITED, "2026-03-01", null,
        "ravi@example.com", "9876500001"));
    template.insert(employee(E2, W2, EmployeeStatus.ACTIVE, null, null, "asha@example.com",
        "9876500002"));
    template.insert(employee(E3, null, EmployeeStatus.EXITED, "2026-02-01", null, null, null));
    template.insert(employee("emp-4", "worker-user-4", EmployeeStatus.EXITED, "2026-02-01",
        NOW.minus(20, ChronoUnit.DAYS), null, null));

    for (int i = 0; i < 4; i++) {
      HrNotification n = new HrNotification();
      n.setShopId(i < 2 ? null : SHOP);
      n.setAudience(NotificationAudience.USER);
      n.setRecipientUserId(W1);
      n.setType(HrNotificationType.JOIN_REQUEST_RECEIVED);
      n.setText("n" + i);
      n.setReadByUserIds(i == 0 ? new ArrayList<>(List.of(W1)) : new ArrayList<>());
      n.setCreatedAt(NOW.minus(i, ChronoUnit.MINUTES));
      template.insert(n);
    }
  }

  private static WorkerJoinRequest joinRequest(String userId, WorkerJoinStatus status,
      Instant requestedAt, Instant decidedAt) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setShopId(SHOP);
    r.setUserId(userId);
    r.setStatus(status);
    r.setRequestedAt(requestedAt);
    r.setUpdatedAt(requestedAt);
    r.setDecidedAt(decidedAt);
    if (decidedAt != null) {
      r.setDecidedByUserId("owner");
      // in the future, so the TTL monitor cannot remove the row while the test runs
      r.setExpiresAt(NOW.plus(90, ChronoUnit.DAYS));
    }
    return r;
  }

  private static Employee employee(String id, String userId, EmployeeStatus status,
      String exitDate, Instant membershipEndedAt, String email, String phone) {
    Employee e = new Employee();
    e.setId(id);
    e.setShopId(SHOP);
    e.setCode("EMP-" + id);
    e.setName("Employee " + id);
    e.setUserId(userId);
    e.setStatus(status);
    e.setJoinDate("2025-01-01");
    e.setExitDate(exitDate);
    e.setMembershipEndedAt(membershipEndedAt);
    e.setEmail(email);
    e.setPhone(phone);
    e.setCreatedAt(NOW);
    return e;
  }

  @Test
  void workerIndexesHaveTheirUniquePartialAndTtlOptions() {
    Map<String, Document> join = indexesByName("hr_join_requests");
    assertEquals(Set.of("_id_", "shop_status_requested", "user_open_unique", "user_decided",
        "expires_ttl"), join.keySet(), "no link-request index: dropped by the migration (D11)");
    plain(join, "shop_status_requested",
        new Document("shopId", 1).append("status", 1).append("requestedAt", -1));
    Document oneOpen = index(join, "user_open_unique", new Document("userId", 1));
    assertEquals(true, oneOpen.get("unique"));
    assertEquals(new Document("status", new Document("$in", List.of("PENDING", "APPROVING"))),
        oneOpen.get("partialFilterExpression"));
    plain(join, "user_decided", new Document("userId", 1).append("decidedAt", -1));
    ttl(join, "expires_ttl", new Document("expiresAt", 1), 0);

    // the legacy proof indexes seeded before the first run are gone; the collection is left alone
    assertEquals(Set.of("_id_"), indexesByName("hr_pairing_proofs").keySet(),
        "hr_pairing_proofs indexes dropped by the migration (D11)");

    Map<String, Document> employees = indexesByName("hr_employees");
    Document exitPending = index(employees, "exit_membership_pending",
        new Document("status", 1).append("exitDate", 1));
    assertNull(exitPending.get("unique"));
    assertEquals(new Document("userId", new Document("$type", 2)).append("membershipEndedAt", null),
        exitPending.get("partialFilterExpression"));
    assertEquals(new Document("email", new Document("$exists", true)),
        index(employees, "shop_email", new Document("shopId", 1).append("email", 1))
            .get("partialFilterExpression"));
    assertEquals(new Document("phone", new Document("$exists", true)),
        index(employees, "shop_phone", new Document("shopId", 1).append("phone", 1))
            .get("partialFilterExpression"));

    plain(indexesByName("hr_notifications"), "recipient_created",
        new Document("recipientUserId", 1).append("createdAt", -1));
  }

  /**
   * The one-pending rule is the database's (R3.5, R14.5): a second open request of an account
   * hits {@code user_open_unique} whatever its shop, and whether the open row is
   * PENDING or APPROVING (DD12), while APPROVED, REJECTED and WITHDRAWN rows never take part in
   * the index. The service turns this duplicate key into {@code HR_WORKER_REQUEST_PENDING}.
   */
  @Test
  void theUniqueIndexAllowsOneOpenRequestPerAccountAndIgnoresDecidedRows() {
    String user = "worker-user-unique";
    try {
      // decided rows of every kind first: none of them occupies the account's one open slot
      template.insert(decided(user, WorkerJoinStatus.APPROVED));
      template.insert(decided(user, WorkerJoinStatus.REJECTED));
      template.insert(decided(user, WorkerJoinStatus.WITHDRAWN));
      WorkerJoinRequest open = template.insert(joinRequest(user, WorkerJoinStatus.PENDING, NOW,
          null));

      // a second PENDING, even to another shop, is refused
      WorkerJoinRequest second = joinRequest(user, WorkerJoinStatus.PENDING, NOW, null);
      second.setShopId("shop-2");
      DuplicateKeyException duplicate = assertThrows(DuplicateKeyException.class,
          () -> template.insert(second));
      assertTrue(duplicate.getMessage().contains("user_open_unique"), duplicate.getMessage());

      // APPROVING counts as open too, as a new row or as the state the open row moves to
      assertThrows(DuplicateKeyException.class, () -> template.insert(joinRequest(user,
          WorkerJoinStatus.APPROVING, NOW, null)));
      template.updateFirst(Query.query(Criteria.where("id").is(open.getId())),
          Update.update("status", WorkerJoinStatus.APPROVING), WorkerJoinRequest.class);
      assertThrows(DuplicateKeyException.class, () -> template.insert(joinRequest(user,
          WorkerJoinStatus.PENDING, NOW, null)));

      // the decision frees the slot: the next request of the account is accepted
      template.updateFirst(Query.query(Criteria.where("id").is(open.getId())),
          Update.update("status", WorkerJoinStatus.APPROVED).set("decidedAt", NOW)
              .set("expiresAt", NOW.plus(90, ChronoUnit.DAYS)), WorkerJoinRequest.class);
      WorkerJoinRequest next = template.insert(joinRequest(user, WorkerJoinStatus.PENDING, NOW,
          null));
      assertNotNull(next.getId());

      assertEquals(5, template.count(Query.query(Criteria.where("userId").is(user)),
          WorkerJoinRequest.class), "four decided rows and one open one");
    } finally {
      template.remove(Query.query(Criteria.where("userId").is(user)), WorkerJoinRequest.class);
    }
  }

  private static WorkerJoinRequest decided(String userId, WorkerJoinStatus status) {
    return joinRequest(userId, status, NOW.minus(2, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS));
  }

  @Test
  void joinRequestReadsUseTheirIndexes() {
    seedWorker();

    // the inbox tab: (shopId, status) newest first, paged (R4.1–R4.2); the sort is an index walk
    Plan inbox = explain("inbox list, newest first", "hr_join_requests",
        () -> assertEquals(2, joinRequests.findByShopIdAndStatusOrderByRequestedAtDesc(SHOP,
            WorkerJoinStatus.PENDING, PageRequest.of(0, 200)).getContent().size()));
    inbox.assertIndexed(Set.of("shop_status_requested"));
    assertFalse(inbox.stages().contains("SORT"), "the inbox sort must be an index walk: " + inbox);

    // the "Join requests (N)" tab count (R4.1)
    explain("pending count of a shop", "hr_join_requests",
        () -> assertEquals(2, joinRequests.countByShopIdAndStatus(SHOP,
            WorkerJoinStatus.PENDING)))
        .assertIndexed(Set.of("shop_status_requested"));

    // the one-pending-per-account check (R3.5): the same predicate as the partial unique filter
    explain("open request of an account", "hr_join_requests",
        () -> assertTrue(joinRequests.findFirstByUserIdAndStatusIn(W1,
            List.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.APPROVING)).isPresent()))
        .assertIndexed(Set.of("user_open_unique", "user_decided"));

    // the Join screen's last-outcome card (R4.12)
    explain("last decision of an account", "hr_join_requests",
        () -> assertEquals(WorkerJoinStatus.REJECTED,
            joinRequests.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(W3,
                NOW.minus(90, ChronoUnit.DAYS)).orElseThrow().getStatus()))
        .assertIndexed(Set.of("user_decided"));

    // the photo who-may-see rule: an account's requests in [PENDING, REJECTED] (R11.5)
    Plan photoRule = explain("requests of an account by status (photo rule)", "hr_join_requests",
        () -> assertEquals(1, joinRequests.findByUserIdAndStatusIn(W3,
            List.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.REJECTED)).size()));
    assertFalse(photoRule.collectionScan(), photoRule.toString());
  }

  @Test
  void employeeAndNotificationReadsUseTheirIndexes() {
    seedWorker();

    // WorkerExitJob.dueQuery: linked, exited, exit date reached, membership still open (R5.2);
    // the predicates are spelled exactly as the partial index filter, or Mongo will not use it
    explain("exit job: due memberships", "hr_employees",
        () -> assertEquals(1, template.find(Query.query(Criteria.where("status").is("EXITED")
            .and("exitDate").lte("2026-03-02").and("userId").type(2)
            .and("membershipEndedAt").is(null)), Employee.class).size()))
        .assertIndexed(Set.of("exit_membership_pending"));

    // WorkerStateService / WorkerJoinService: the account's linked ACTIVE employee anywhere
    Plan linked = explain("linked ACTIVE employee of an account", "hr_employees",
        () -> assertEquals(1, template.find(Query.query(Criteria.where("userId").is(W2)
            .and("status").is(EmployeeStatus.ACTIVE.name())), Employee.class).size()));
    // Reviewed, not asserted: no index starts with userId, so this read walks hr_employees. The
    // possible-match read (findMatchCandidates: shopId, status $in) uses shop_status_name.
    System.out.println("[hr-explain] reviewed only: " + linked);
    explain("match candidates of a shop", "hr_employees",
        () -> assertEquals(4, template.find(Query.query(Criteria.where("shopId").is(SHOP)
            .and("status").in(List.of("ACTIVE", "EXITED"))), Employee.class).size()))
        .assertIndexed(Set.of("shop_status_name"));

    // the worker's notifications across shops (R10.1): unread first, newest first
    explain("unread notifications of an account", "hr_notifications",
        () -> assertEquals(3, template.find(Query.query(Criteria.where("recipientUserId").is(W1)
            .and("audience").is(NotificationAudience.USER).and("readByUserIds").ne(W1))
            .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(20), HrNotification.class)
            .size()))
        .assertIndexed(Set.of("recipient_created", "shop_recipient_created"));
  }
}
