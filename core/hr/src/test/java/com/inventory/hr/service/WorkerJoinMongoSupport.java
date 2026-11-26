package com.inventory.hr.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.tx.MongoTx;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrAuditEventRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.mapper.EmployeeMapperImpl;
import com.inventory.hr.migration.HrIndexMigration;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.UserPhotoRepository;
import com.inventory.user.domain.repository.UserShopMembershipRepository;
import com.inventory.user.mapper.UserShopMembershipMapperImpl;
import com.inventory.user.service.JoinRequestService;
import com.inventory.user.service.UserPhotoService;
import com.inventory.user.service.UserShopMembershipService;
import com.inventory.user.service.WorkerAccountService;
import com.inventory.user.service.api.WorkerAccountsImpl;
import com.inventory.user.validation.UserValidator;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import net.jqwik.api.lifecycle.LifecycleContext;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.lifecycle.SkipExecutionHook;
import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link WorkerJoinService} wired by hand over a real MongoDB <em>replica set</em>, for the
 * worker-app property tests that need the database (tasks 4.7 and 4.8): the partial unique index
 * {@code user_open_unique} that keeps one open request per account, and {@link MongoTx}, whose
 * transaction makes an approval all-or-nothing. Everything the approval touches is the real code
 * on the same {@link MongoTemplate}, so every write joins the transaction: {@link EmployeeService}
 * (create or link the employee, its audit rows, the {@code EMP-nnn} counter), the user module's
 * {@link WorkerAccountService#claimShop} and {@link UserShopMembershipService#addMembership}
 * behind the real {@link WorkerAccountsImpl}, {@link HrAuditService} and {@link
 * HrNotificationService} for the after-commit rows. The permission guard allows everything (the
 * permission rules have their own tests) and the rate limiter admits every call (Property 28 has
 * the limits).
 *
 * <p>The database is the one named in {@code -Dstockkart.test.mongo.rs.uri} plus {@code _<suffix>};
 * it is dropped on construction and the indexes come from {@link HrIndexMigration}. {@link #reset}
 * empties every collection and keeps the indexes, so one wiring serves many property tries.
 * Without the system property the properties are skipped through {@link RequiresReplicaSet}, as
 * the {@code *IT} classes are through {@code @EnabledIfSystemProperty} (T6).
 *
 * <pre>
 *   mvn -pl core/hr -am test -Dtest='WorkerJoinService*Properties' \
 *       -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dstockkart.test.mongo.rs.uri='mongodb://127.0.0.1:27018/wjs47?directConnection=true'
 * </pre>
 */
final class WorkerJoinMongoSupport {

  static final String RS_PROPERTY = "stockkart.test.mongo.rs.uri";

  /** Skips a jqwik container (and its properties) unless {@value #RS_PROPERTY} is set. */
  public static final class RequiresReplicaSet implements SkipExecutionHook {
    @Override
    public SkipResult shouldBeSkipped(LifecycleContext context) {
      String uri = System.getProperty(RS_PROPERTY);
      return uri == null || uri.isBlank()
          ? SkipResult.skip("needs -D" + RS_PROPERTY + "=<mongodb replica-set uri>")
          : SkipResult.doNotSkip();
    }

    @Override
    public PropagationMode propagateTo() {
      return PropagationMode.ALL_DESCENDANTS;
    }
  }

  final MongoClient client;
  final MongoTemplate template;
  final MongoTx mongoTx;
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final WorkerJoinService service;
  final EmployeeService employees;
  final WorkerAccounts workerAccounts;
  final HrAuditService audit;
  final HrNotificationService notifications;
  final WorkerJoinRequestRepository requests;
  final EmployeeRepository employeeRepository;
  final UserAccountRepository userAccounts;
  final UserShopMembershipRepository memberships;
  final UserPhotoRepository photos;

  WorkerJoinMongoSupport(String dbSuffix) {
    String uri = System.getProperty(RS_PROPERTY);
    client = MongoClients.create(MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(uri)).build());
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_" + dbSuffix;
    SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, db);
    template = template(factory);
    template.getDb().drop();
    HrIndexMigration migration = new HrIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    migration.run();
    // the app's settings: transactions on, five attempts per flow, three commit attempts
    mongoTx = new MongoTx(factory, true, 5, 3, 15000);

    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    requests = repos.getRepository(WorkerJoinRequestRepository.class);
    employeeRepository = repos.getRepository(EmployeeRepository.class);
    userAccounts = repos.getRepository(UserAccountRepository.class);
    memberships = repos.getRepository(UserShopMembershipRepository.class);
    photos = repos.getRepository(UserPhotoRepository.class);
    MetricsWrapper metrics = new MetricsWrapper(registry);
    HrPermissionGuard guard = mock(HrPermissionGuard.class);
    when(guard.require(anyString(), anyString(), any(HrPermission[].class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    when(guard.require(anyString(), anyString(), any(HrPermission.class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    ShopNamePort shopNames = shopId -> "Shop " + shopId;

    audit = new HrAuditService();
    ReflectionTestUtils.setField(audit, "auditEventRepository",
        repos.getRepository(HrAuditEventRepository.class));
    ReflectionTestUtils.setField(audit, "metrics", metrics);

    HrQueryRunner runner = new HrQueryRunner();
    ReflectionTestUtils.setField(runner, "metrics", metrics);
    notifications = new HrNotificationService();
    ReflectionTestUtils.setField(notifications, "mongoTemplate", template);
    ReflectionTestUtils.setField(notifications, "hrAccessService", mock(HrAccessService.class));
    ReflectionTestUtils.setField(notifications, "queryRunner", runner);

    // the real pairing service: an employee save clears a scheduled unpair through it, which must
    // leave an ACTIVE pairing byte-identical (Property 6)
    DevicePairingService pairings = new DevicePairingService();
    ReflectionTestUtils.setField(pairings, "codeRepository",
        repos.getRepository(PairingCodeRepository.class));
    ReflectionTestUtils.setField(pairings, "pairingRepository",
        repos.getRepository(DevicePairingRepository.class));
    ReflectionTestUtils.setField(pairings, "employeeRepository", employeeRepository);
    ReflectionTestUtils.setField(pairings, "settingsService", mock(HrSettingsService.class));
    ReflectionTestUtils.setField(pairings, "consentVersions", new ConsentVersions());
    ReflectionTestUtils.setField(pairings, "auditService", audit);
    ReflectionTestUtils.setField(pairings, "metrics", metrics);
    ReflectionTestUtils.setField(pairings, "mongoTemplate", template);
    ReflectionTestUtils.setField(pairings, "shopNamePort", shopNames);

    HrCounterService counters = new HrCounterService();
    ReflectionTestUtils.setField(counters, "mongoTemplate", template);
    employees = new EmployeeService();
    ReflectionTestUtils.setField(employees, "employeeRepository", employeeRepository);
    ReflectionTestUtils.setField(employees, "mongoTemplate", template);
    ReflectionTestUtils.setField(employees, "employeeMapper", new EmployeeMapperImpl());
    ReflectionTestUtils.setField(employees, "validator", new EmployeeValidator());
    ReflectionTestUtils.setField(employees, "guard", guard);
    ReflectionTestUtils.setField(employees, "counterService", counters);
    ReflectionTestUtils.setField(employees, "membershipService",
        mock(UserShopMembershipService.class));
    ReflectionTestUtils.setField(employees, "periodLockService", mock(PeriodLockService.class));
    ReflectionTestUtils.setField(employees, "auditService", audit);
    ReflectionTestUtils.setField(employees, "pairingService", pairings);
    ReflectionTestUtils.setField(employees, "notificationService", notifications);

    // the user module as deployed: the one-shop lock and the WORKER membership on the same template
    WorkerAccountService accountService = new WorkerAccountService();
    ReflectionTestUtils.setField(accountService, "mongoTemplate", template);
    ReflectionTestUtils.setField(accountService, "userAccountRepository", userAccounts);
    ReflectionTestUtils.setField(accountService, "membershipRepository", memberships);
    UserShopMembershipService membershipService = new UserShopMembershipService();
    ReflectionTestUtils.setField(membershipService, "membershipRepository", memberships);
    ReflectionTestUtils.setField(membershipService, "userAccountRepository", userAccounts);
    ReflectionTestUtils.setField(membershipService, "userShopMembershipMapper",
        new UserShopMembershipMapperImpl());
    ReflectionTestUtils.setField(membershipService, "userValidator", new UserValidator());
    ReflectionTestUtils.setField(membershipService, "metrics", metrics);
    UserPhotoService photoService = new UserPhotoService();
    ReflectionTestUtils.setField(photoService, "userPhotoRepository", photos);
    ReflectionTestUtils.setField(photoService, "userAccountRepository", userAccounts);
    ReflectionTestUtils.setField(photoService, "membershipRepository", memberships);
    workerAccounts = new WorkerAccountsImpl(userAccounts, accountService, membershipService,
        photoService, new JoinRequestService());

    AppRateLimiter rateLimiter = mock(AppRateLimiter.class);
    when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);

    service = new WorkerJoinService();
    ReflectionTestUtils.setField(service, "requestRepository", requests);
    ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
    ReflectionTestUtils.setField(service, "mongoTemplate", template);
    ReflectionTestUtils.setField(service, "mongoTx", mongoTx);
    ReflectionTestUtils.setField(service, "employeeService", employees);
    ReflectionTestUtils.setField(service, "workerAccounts", workerAccounts);
    ReflectionTestUtils.setField(service, "shopNamePort", shopNames);
    ReflectionTestUtils.setField(service, "guard", guard);
    ReflectionTestUtils.setField(service, "auditService", audit);
    ReflectionTestUtils.setField(service, "notificationService", notifications);
    ReflectionTestUtils.setField(service, "rateLimiter", rateLimiter);
    ReflectionTestUtils.setField(service, "counters", new HrWorkerCounters(metrics));
  }

  /** Empties every collection (indexes stay), so the next try starts from nothing. */
  void reset() {
    for (String name : template.getCollectionNames()) {
      template.getCollection(name).deleteMany(new org.bson.Document());
    }
  }

  void close() {
    template.getDb().drop();
    client.close();
  }

  /** Same BigDecimal ↔ Decimal128 conversion as the app's {@code MongoConfig} (P7). */
  private static MongoTemplate template(MongoDatabaseFactory factory) {
    MongoCustomConversions conversions = new MongoCustomConversions(
        List.of(new ToDecimal128(), new FromDecimal128()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter converter =
        new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return new MongoTemplate(factory, converter);
  }

  @WritingConverter
  static class ToDecimal128 implements Converter<BigDecimal, Decimal128> {
    @Override
    public Decimal128 convert(BigDecimal source) {
      return Decimal128.parse(source.toString());
    }
  }

  @ReadingConverter
  static class FromDecimal128 implements Converter<Decimal128, BigDecimal> {
    @Override
    public BigDecimal convert(Decimal128 source) {
      return source.bigDecimalValue();
    }
  }
}
