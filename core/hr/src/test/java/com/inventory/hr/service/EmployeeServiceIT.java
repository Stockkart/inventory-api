package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.mapper.EmployeeMapperImpl;
import com.inventory.hr.migration.HrIndexMigration;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.user.service.UserShopMembershipService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Employees against a real MongoDB (design Property 14 "Unique codes and links", R4.3, R4.6,
 * R14.1). Runs only when {@code -Dstockkart.test.mongo.uri=…} is set:
 *
 * <pre>
 *   docker run -d --rm --name hr-test-mongo -p 127.0.0.1:27019:27017 --tmpfs /data/db mongo:7
 *   mvn -pl core/hr -am test -Dtest='EmployeeServiceIT' -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.uri=mongodb://localhost:27019/hr_test
 *   docker stop hr-test-mongo
 * </pre>
 *
 * The indexes come from {@link HrIndexMigration} itself, so this also proves the runner works.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class EmployeeServiceIT {

  private static final String USER = "owner-1";

  private static MongoClient client;
  private static MongoTemplate template;
  private static EmployeeRepository repository;
  private static EmployeeService service;

  @BeforeAll
  static void setUp() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0];
    template = new MongoTemplate(client, db);
    template.getDb().drop();

    HrIndexMigration migration = new HrIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    migration.run();

    repository = new MongoRepositoryFactory(template).getRepository(EmployeeRepository.class);
    HrCounterService counter = new HrCounterService();
    ReflectionTestUtils.setField(counter, "mongoTemplate", template);
    HrPermissionGuard guard = mock(HrPermissionGuard.class);
    when(guard.require(anyString(), anyString(), any(HrPermission[].class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    when(guard.require(anyString(), anyString(), any(HrPermission.class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    UserShopMembershipService members = mock(UserShopMembershipService.class);
    when(members.listActiveMembers(anyString())).thenReturn(List.of(
        new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED"),
        new ShopMemberSummaryDto("login-2", "Mohan", "MANAGER", "INVITED")));

    service = new EmployeeService();
    ReflectionTestUtils.setField(service, "employeeRepository", repository);
    ReflectionTestUtils.setField(service, "mongoTemplate", template);
    ReflectionTestUtils.setField(service, "employeeMapper", new EmployeeMapperImpl());
    ReflectionTestUtils.setField(service, "validator", new EmployeeValidator());
    ReflectionTestUtils.setField(service, "guard", guard);
    ReflectionTestUtils.setField(service, "counterService", counter);
    ReflectionTestUtils.setField(service, "membershipService", members);
    PeriodLockService periodLockService = new PeriodLockService();
    ReflectionTestUtils.setField(periodLockService, "mongoTemplate", template);
    ReflectionTestUtils.setField(periodLockService, "settingsRepository",
        new MongoRepositoryFactory(template).getRepository(
            com.inventory.hr.domain.repository.HrSettingsRepository.class));
    ReflectionTestUtils.setField(service, "periodLockService", periodLockService);
    ReflectionTestUtils.setField(service, "auditService", mock(HrAuditService.class));
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

  private static SaveEmployeeRequest request(String name) {
    SaveEmployeeRequest r = new SaveEmployeeRequest();
    r.setName(name);
    r.setJoinDate("2025-04-01");
    return r;
  }

  @Test
  void concurrentCreatesGetUniqueSequentialCodes() throws Exception {
    String shop = "shop-codes";
    int n = 20;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<EmployeeResponse>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int k = i;
      futures.add(pool.submit(() -> service.create(USER, shop, request("Helper " + k))));
    }
    Set<String> codes = new HashSet<>();
    for (Future<EmployeeResponse> f : futures) {
      codes.add(f.get().getCode());
    }
    pool.shutdown();

    assertEquals(n, codes.size());
    for (int i = 1; i <= n; i++) {
      assertTrue(codes.contains(String.format("EMP-%03d", i)), "missing EMP-" + i);
    }
    assertEquals(n, repository.countByShopIdAndStatus(shop, EmployeeStatus.ACTIVE));
  }

  @Test
  void anAutoCodeSkipsACodeTakenByHand() {
    String shop = "shop-skip";
    SaveEmployeeRequest manual = request("Manual");
    manual.setCode("EMP-002");
    service.create(USER, shop, manual);

    assertEquals("EMP-001", service.create(USER, shop, request("First")).getCode());
    assertEquals("EMP-003", service.create(USER, shop, request("Second")).getCode());
  }

  @Test
  void aLoginLinksToAtMostOneEmployeePerShop() {
    String shop = "shop-link";
    SaveEmployeeRequest first = request("Asha");
    first.setUserId("login-1");
    service.create(USER, shop, first);

    SaveEmployeeRequest second = request("Asha again");
    second.setUserId("login-1");
    BaseException e = assertThrows(BaseException.class, () -> service.create(USER, shop, second));
    assertEquals(ErrorCode.HR_LOGIN_ALREADY_LINKED, e.getErrorCode());

    // many unlinked employees may coexist (partial index ignores userId = null)
    service.create(USER, shop, request("Helper 1"));
    service.create(USER, shop, request("Helper 2"));
    assertEquals(3, repository.countByShopIdAndStatus(shop, EmployeeStatus.ACTIVE));
  }

  @Test
  void theUniqueIndexStopsADuplicateLinkThatSkipsTheCheck() {
    String shop = "shop-race";
    repository.insert(raw(shop, "R-1", "login-2"));

    assertThrows(DuplicateKeyException.class, () -> repository.insert(raw(shop, "R-2", "login-2")));
    // the same login may be linked in another shop
    repository.insert(raw("shop-race-2", "R-1", "login-2"));
  }

  @Test
  void anotherShopsEmployeeCannotBeReadOrListed() {
    EmployeeResponse a = service.create(USER, "shop-a", request("Only in A"));

    assertThrows(ResourceNotFoundException.class, () -> service.get(USER, "shop-b", a.getId()));
    assertEquals(0, service.list(USER, "shop-b", "Only", null, null, 0, 20).getPage()
        .getTotalItems());
    assertEquals(1, service.list(USER, "shop-a", "only in", null, null, 0, 20).getPage()
        .getTotalItems());
    // codes are unique per shop, not globally
    assertEquals("EMP-001", service.create(USER, "shop-b", request("B")).getCode());
  }

  @Test
  void theLinkedEmployeeLookupUsesThePartialIndex() {
    repository.insert(raw("shop-explain", "X-1", "login-9"));

    Document plan = template.getCollection("hr_employees")
        .find(new Document("shopId", "shop-explain")
            .append("userId", new Document("$eq", "login-9").append("$type", "string")))
        .explain();

    String winning = plan.get("queryPlanner", Document.class).get("winningPlan", Document.class)
        .toJson();
    assertTrue(winning.contains("shop_user_unique"), "winning plan: " + winning);
    assertEquals("X-1", repository.findByShopIdAndUserId("shop-explain", "login-9")
        .orElseThrow().getCode());
  }

  @Test
  void theIndexesExist() {
    List<Document> indexes = template.getCollection("hr_employees").listIndexes()
        .into(new ArrayList<>());
    Document link = indexes.stream().filter(d -> "shop_user_unique".equals(d.getString("name")))
        .findFirst().orElseThrow();
    assertEquals(true, link.getBoolean("unique"));
    assertTrue(link.containsKey("partialFilterExpression"));

    Document ttl = template.getCollection("hr_notifications").listIndexes()
        .into(new ArrayList<>()).stream()
        .filter(d -> "created_ttl".equals(d.getString("name"))).findFirst().orElseThrow();
    assertEquals(7_776_000L, ((Number) ttl.get("expireAfterSeconds")).longValue());
  }

  private static Employee raw(String shop, String code, String userId) {
    Employee e = new Employee();
    e.setShopId(shop);
    e.setCode(code);
    e.setName(code);
    e.setUserId(userId);
    e.setStatus(EmployeeStatus.ACTIVE);
    return e;
  }
}
