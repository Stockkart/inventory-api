package com.inventory.hr.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.accounting.domain.repository.AccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.accounting.service.AccountLedgerBalances;
import com.inventory.accounting.service.AccountService;
import com.inventory.accounting.service.AccountingFacadeImpl;
import com.inventory.accounting.service.AccountingPostingService;
import com.inventory.accounting.service.ChartOfAccountsSeeder;
import com.inventory.accounting.service.JournalEntrySequenceService;
import com.inventory.documentservice.service.HtmlToPdfConverter;
import com.inventory.documentservice.service.hr.PayslipPdfService;
import com.inventory.documentservice.service.mis.MisExcelDocumentService;
import com.inventory.documentservice.service.mis.MisPdfDocumentService;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.mapper.EmployeeMapperImpl;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.hr.validation.SalaryPaymentValidator;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import com.inventory.notifications.service.MessagingService;
import com.inventory.user.service.ShopServiceAdapter;
import com.inventory.user.service.UserShopMembershipService;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.repository.AdvanceRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.HrAuditEventRepository;
import com.inventory.hr.domain.repository.HrSettingsRepository;
import com.inventory.hr.domain.repository.LeaveRequestRepository;
import com.inventory.hr.domain.repository.LeaveTypeRepository;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.mapper.HrPayrollMapperImpl;
import com.inventory.hr.migration.HrIndexMigration;
import com.inventory.hr.validation.AdvanceValidator;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.hr.validation.LeaveRequestValidator;
import com.inventory.hr.validation.PayrollAdjustmentValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wires the attendance, leave, advance and payroll services by hand against a real MongoDB for
 * the {@code *IT} tests (T6, T7), with the real accounting module behind {@link
 * HrAccountingPoster}. The database named in {@code -Dstockkart.test.mongo.uri} is dropped first
 * and the indexes come from {@link HrIndexMigration} (accounting's own unique indexes are not
 * created, as in production where automatic index creation is off). The permission guard allows
 * everything; the permission rules have their own tests.
 */
final class HrMongoItSupport {

  final MongoClient client;
  final MongoTemplate template;
  final AttendanceService attendance;
  final LeaveService leave;
  final LeaveBalanceService balances;
  final HrNotificationService notifications;
  final SelfServiceResolver selfService;
  final LeaveTypeService types;
  final AccountingFacadeImpl accounting;
  final HrAccountingPoster poster;
  final AdvanceService advances;
  final PayrollService payroll;
  final MetricsWrapper metrics;
  final SalaryPaymentService payments;
  final PayslipService payslips;
  final HrReportService reports;
  final HrSelfService selfServiceFacade;
  final EmployeeService employees;
  final MessagingService messaging;
  final OutboundMessageRepository outbox;
  final ShopServiceAdapter shops;

  HrMongoItSupport(String dbSuffix) {
    this(dbSuffix, null);
  }

  /** @param listener optional driver command listener (HrPerformanceIT counts round trips) */
  HrMongoItSupport(String dbSuffix, com.mongodb.event.CommandListener listener) {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    com.mongodb.MongoClientSettings.Builder clientSettings = com.mongodb.MongoClientSettings
        .builder().applyConnectionString(new com.mongodb.ConnectionString(uri));
    if (listener != null) {
      clientSettings.addCommandListener(listener);
    }
    client = MongoClients.create(clientSettings.build());
    String db = uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_" + dbSuffix;
    template = template(client, db);
    template.getDb().drop();
    HrIndexMigration migration = new HrIndexMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    migration.run();

    MongoRepositoryFactory repos = new MongoRepositoryFactory(template);
    metrics = mock(MetricsWrapper.class);
    HrPermissionGuard guard = mock(HrPermissionGuard.class);
    when(guard.require(anyString(), anyString(), any(HrPermission[].class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    when(guard.require(anyString(), anyString(), any(HrPermission.class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    when(guard.requireOneOf(anyString(), anyString(), any(HrPermission[].class)))
        .thenReturn(EnumSet.allOf(HrPermission.class));
    HrAccessService access = mock(HrAccessService.class);
    when(access.permissions(anyString(), anyString()))
        .thenReturn(EnumSet.allOf(HrPermission.class));

    HrQueryRunner runner = new HrQueryRunner();
    ReflectionTestUtils.setField(runner, "metrics", metrics);
    HrSettingsService settings = new HrSettingsService();
    ReflectionTestUtils.setField(settings, "settingsRepository",
        repos.getRepository(HrSettingsRepository.class));
    PeriodLockService lock = new PeriodLockService();
    ReflectionTestUtils.setField(lock, "mongoTemplate", template);
    HrAuditService audit = new HrAuditService();
    ReflectionTestUtils.setField(audit, "auditEventRepository",
        repos.getRepository(HrAuditEventRepository.class));
    ReflectionTestUtils.setField(audit, "metrics", metrics);
    types = new LeaveTypeService();
    ReflectionTestUtils.setField(types, "leaveTypeRepository",
        repos.getRepository(LeaveTypeRepository.class));
    selfService = new SelfServiceResolver();
    ReflectionTestUtils.setField(selfService, "employeeRepository",
        repos.getRepository(EmployeeRepository.class));

    attendance = new AttendanceService();
    ReflectionTestUtils.setField(attendance, "mongoTemplate", template);
    ReflectionTestUtils.setField(attendance, "guard", guard);
    ReflectionTestUtils.setField(attendance, "settingsService", settings);
    ReflectionTestUtils.setField(attendance, "periodLockService", lock);
    ReflectionTestUtils.setField(attendance, "auditService", audit);
    ReflectionTestUtils.setField(attendance, "validator", new AttendanceDayValidator());
    ReflectionTestUtils.setField(attendance, "queryRunner", runner);
    ReflectionTestUtils.setField(attendance, "metrics", metrics);
    // names are display-only; the user module is not wired here (empty map for every lookup)
    ReflectionTestUtils.setField(attendance, "userNames", mock(HrUserNames.class));

    balances = new LeaveBalanceService();
    ReflectionTestUtils.setField(balances, "mongoTemplate", template);
    ReflectionTestUtils.setField(balances, "guard", guard);
    ReflectionTestUtils.setField(balances, "settingsService", settings);
    ReflectionTestUtils.setField(balances, "leaveTypeService", types);
    ReflectionTestUtils.setField(balances, "queryRunner", runner);

    notifications = new HrNotificationService();
    ReflectionTestUtils.setField(notifications, "mongoTemplate", template);
    ReflectionTestUtils.setField(notifications, "hrAccessService", access);
    ReflectionTestUtils.setField(notifications, "queryRunner", runner);

    leave = new LeaveService();
    ReflectionTestUtils.setField(leave, "mongoTemplate", template);
    ReflectionTestUtils.setField(leave, "leaveRequestRepository",
        repos.getRepository(LeaveRequestRepository.class));
    ReflectionTestUtils.setField(leave, "leaveTypeRepository",
        repos.getRepository(LeaveTypeRepository.class));
    ReflectionTestUtils.setField(leave, "guard", guard);
    ReflectionTestUtils.setField(leave, "selfServiceResolver", selfService);
    ReflectionTestUtils.setField(leave, "settingsService", settings);
    ReflectionTestUtils.setField(leave, "balanceService", balances);
    ReflectionTestUtils.setField(leave, "periodLockService", lock);
    ReflectionTestUtils.setField(leave, "auditService", audit);
    ReflectionTestUtils.setField(leave, "notificationService", notifications);
    ReflectionTestUtils.setField(leave, "validator", new LeaveRequestValidator());
    ReflectionTestUtils.setField(leave, "queryRunner", runner);

    // the real accounting module, wired as Spring would (its @Transactional does nothing here,
    // as in the app: no transaction manager)
    AccountRepository accountRepository = repos.getRepository(AccountRepository.class);
    LedgerEntryRepository ledgerRepository = repos.getRepository(LedgerEntryRepository.class);
    JournalEntryRepository journalRepository = repos.getRepository(JournalEntryRepository.class);
    ChartOfAccountsSeeder seeder = new ChartOfAccountsSeeder(accountRepository, ledgerRepository);
    AccountService accountService =
        new AccountService(accountRepository, seeder, metrics, template);
    AccountingPostingService posting = new AccountingPostingService(journalRepository,
        ledgerRepository, accountService, new JournalEntrySequenceService(template), metrics,
        new AccountLedgerBalances(template, ledgerRepository));
    accounting = new AccountingFacadeImpl(posting, journalRepository, accountRepository);
    poster = new HrAccountingPoster();
    ReflectionTestUtils.setField(poster, "accountingFacade", accounting);
    ReflectionTestUtils.setField(poster, "metrics", metrics);

    HrPayrollMapper payrollMapper = new HrPayrollMapperImpl();
    advances = new AdvanceService();
    ReflectionTestUtils.setField(advances, "mongoTemplate", template);
    ReflectionTestUtils.setField(advances, "advanceRepository",
        repos.getRepository(AdvanceRepository.class));
    ReflectionTestUtils.setField(advances, "guard", guard);
    ReflectionTestUtils.setField(advances, "validator", new AdvanceValidator());
    ReflectionTestUtils.setField(advances, "poster", poster);
    ReflectionTestUtils.setField(advances, "auditService", audit);
    ReflectionTestUtils.setField(advances, "mapper", payrollMapper);
    ReflectionTestUtils.setField(advances, "queryRunner", runner);
    ReflectionTestUtils.setField(advances, "metrics", metrics);

    PayrollInputReader reader = new PayrollInputReader();
    ReflectionTestUtils.setField(reader, "mongoTemplate", template);
    ReflectionTestUtils.setField(reader, "settingsService", settings);
    ReflectionTestUtils.setField(reader, "attendanceService", attendance);
    ReflectionTestUtils.setField(reader, "advanceService", advances);

    payroll = new PayrollService();
    ReflectionTestUtils.setField(payroll, "mongoTemplate", template);
    ReflectionTestUtils.setField(payroll, "runRepository",
        repos.getRepository(PayrollRunRepository.class));
    ReflectionTestUtils.setField(payroll, "lineRepository",
        repos.getRepository(PayrollLineRepository.class));
    ReflectionTestUtils.setField(payroll, "guard", guard);
    ReflectionTestUtils.setField(payroll, "inputReader", reader);
    ReflectionTestUtils.setField(payroll, "poster", poster);
    ReflectionTestUtils.setField(payroll, "auditService", audit);
    ReflectionTestUtils.setField(payroll, "validator", new PayrollAdjustmentValidator());
    ReflectionTestUtils.setField(payroll, "mapper", payrollMapper);
    ReflectionTestUtils.setField(payroll, "queryRunner", runner);
    ReflectionTestUtils.setField(payroll, "metrics", metrics);

    // ---- run B4: payments, payslips, reports, self-service, employees --------------------------
    payments = new SalaryPaymentService();
    ReflectionTestUtils.setField(payments, "mongoTemplate", template);
    ReflectionTestUtils.setField(payments, "runRepository",
        repos.getRepository(PayrollRunRepository.class));
    ReflectionTestUtils.setField(payments, "lineRepository",
        repos.getRepository(PayrollLineRepository.class));
    ReflectionTestUtils.setField(payments, "guard", guard);
    ReflectionTestUtils.setField(payments, "poster", poster);
    ReflectionTestUtils.setField(payments, "auditService", audit);
    ReflectionTestUtils.setField(payments, "validator", new SalaryPaymentValidator());
    ReflectionTestUtils.setField(payments, "mapper", payrollMapper);
    ReflectionTestUtils.setField(payments, "queryRunner", runner);
    ReflectionTestUtils.setField(payments, "metrics", metrics);
    // reopen settles stale payment claims first
    ReflectionTestUtils.setField(payroll, "salaryPaymentService", payments);

    // the real Thymeleaf template and PDF renderer, as documentservice runs them
    PayslipPdfService pdf = new PayslipPdfService();
    ReflectionTestUtils.setField(pdf, "templateEngine", templateEngine());
    ReflectionTestUtils.setField(pdf, "htmlToPdfConverter", new HtmlToPdfConverter());
    ReflectionTestUtils.setField(pdf, "metrics", metrics);
    shops = mock(ShopServiceAdapter.class);
    when(shops.getShopSummary(anyString()))
        .thenReturn(new ShopServiceAdapter.ShopSummary("Test Shop", "1 Main Road, Pune"));
    messaging = new MessagingService();
    outbox = repos.getRepository(OutboundMessageRepository.class);
    ReflectionTestUtils.setField(messaging, "outboundMessageRepository", outbox);
    ReflectionTestUtils.setField(messaging, "metrics", metrics);
    payslips = new PayslipService();
    ReflectionTestUtils.setField(payslips, "mongoTemplate", template);
    ReflectionTestUtils.setField(payslips, "runRepository",
        repos.getRepository(PayrollRunRepository.class));
    ReflectionTestUtils.setField(payslips, "lineRepository",
        repos.getRepository(PayrollLineRepository.class));
    ReflectionTestUtils.setField(payslips, "guard", guard);
    ReflectionTestUtils.setField(payslips, "selfServiceResolver", selfService);
    ReflectionTestUtils.setField(payslips, "payslipPdfService", pdf);
    ReflectionTestUtils.setField(payslips, "shopServiceAdapter", shops);
    ReflectionTestUtils.setField(payslips, "messagingService", messaging);
    ReflectionTestUtils.setField(payslips, "queryRunner", runner);
    ReflectionTestUtils.setField(payslips, "metrics", metrics);

    MisExcelDocumentService excel = new MisExcelDocumentService();
    ReflectionTestUtils.setField(excel, "metrics", metrics);
    MisPdfDocumentService misPdf = new MisPdfDocumentService(templateEngine(), metrics);
    reports = new HrReportService();
    ReflectionTestUtils.setField(reports, "guard", guard);
    ReflectionTestUtils.setField(reports, "attendanceService", attendance);
    ReflectionTestUtils.setField(reports, "leaveBalanceService", balances);
    ReflectionTestUtils.setField(reports, "runRepository",
        repos.getRepository(PayrollRunRepository.class));
    ReflectionTestUtils.setField(reports, "lineRepository",
        repos.getRepository(PayrollLineRepository.class));
    ReflectionTestUtils.setField(reports, "excelService", excel);
    ReflectionTestUtils.setField(reports, "pdfService", misPdf);
    ReflectionTestUtils.setField(reports, "shopServiceAdapter", shops);
    ReflectionTestUtils.setField(reports, "queryRunner", runner);
    ReflectionTestUtils.setField(reports, "metrics", metrics);

    selfServiceFacade = new HrSelfService();
    ReflectionTestUtils.setField(selfServiceFacade, "selfServiceResolver", selfService);
    ReflectionTestUtils.setField(selfServiceFacade, "employeeMapper", new EmployeeMapperImpl());
    ReflectionTestUtils.setField(selfServiceFacade, "attendanceService", attendance);
    ReflectionTestUtils.setField(selfServiceFacade, "leaveService", leave);
    ReflectionTestUtils.setField(selfServiceFacade, "leaveBalanceService", balances);
    ReflectionTestUtils.setField(selfServiceFacade, "payslipService", payslips);
    ReflectionTestUtils.setField(selfServiceFacade, "notificationService", notifications);

    employees = new EmployeeService();
    ReflectionTestUtils.setField(employees, "employeeRepository",
        repos.getRepository(EmployeeRepository.class));
    ReflectionTestUtils.setField(employees, "mongoTemplate", template);
    ReflectionTestUtils.setField(employees, "employeeMapper", new EmployeeMapperImpl());
    ReflectionTestUtils.setField(employees, "validator", new EmployeeValidator());
    ReflectionTestUtils.setField(employees, "guard", guard);
    HrCounterService counter = new HrCounterService();
    ReflectionTestUtils.setField(counter, "mongoTemplate", template);
    ReflectionTestUtils.setField(employees, "counterService", counter);
    ReflectionTestUtils.setField(employees, "membershipService",
        mock(UserShopMembershipService.class));
    ReflectionTestUtils.setField(employees, "periodLockService", lock);
    ReflectionTestUtils.setField(employees, "auditService", audit);
  }

  /** Thymeleaf over {@code classpath:templates/*.html}, as the app's auto-configuration. */
  static TemplateEngine templateEngine() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding("UTF-8");
    // SpringTemplateEngine (SpEL), as the app's auto-configuration; plain Thymeleaf needs OGNL
    TemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine();
    engine.setTemplateResolver(resolver);
    return engine;
  }

  /** A finalised run of {@code period} for the given employees (marks every working day). */
  PayrollRun finalisedRun(String shopId, String period, Employee... staff) {
    for (Employee e : staff) {
      markMonthPresent(shopId, e, YearMonth.parse(period));
    }
    CreatePayrollRunRequest create = new CreatePayrollRunRequest();
    create.setPeriod(period);
    String runId = payroll.create("owner", shopId, create).getId();
    payroll.finalise("owner", shopId, runId);
    return template.findById(runId, PayrollRun.class);
  }

  /** SALARY_PAYABLE (2400) balance of a shop from its live journals: credits − debits. */
  BigDecimal salaryPayable(String shopId) {
    BigDecimal balance = BigDecimal.ZERO;
    for (JournalEntry e : template.find(Query.query(Criteria.where("shopId").is(shopId)),
        JournalEntry.class)) {
      if (e.getLines() == null) {
        continue;
      }
      for (com.inventory.accounting.domain.model.JournalLine l : e.getLines()) {
        if (com.inventory.accounting.domain.model.SystemAccountCode.SALARY_PAYABLE
            .equals(l.getAccountCode())) {
          balance = balance.add(nz(l.getCredit())).subtract(nz(l.getDebit()));
        }
      }
    }
    return balance;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  /** Live (POSTED, not a reversal) journals of a source type in a shop. */
  List<JournalEntry> liveJournals(String shopId, JournalSource type) {
    return template.find(Query.query(Criteria.where("shopId").is(shopId).and("sourceType")
        .is(type).and("status").is(JournalStatus.POSTED)), JournalEntry.class);
  }

  /** An employee with a monthly salary from {@code effectiveFrom}. */
  Employee paidEmployee(String shopId, String code, String name, String joinDate,
      String effectiveFrom, String monthly) {
    Employee e = new Employee();
    e.setShopId(shopId);
    e.setCode(code);
    e.setName(name);
    e.setJoinDate(joinDate);
    e.setStatus(EmployeeStatus.ACTIVE);
    e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision(effectiveFrom,
        PayType.MONTHLY, new BigDecimal(monthly), Instant.EPOCH, "owner"))));
    return template.insert(e);
  }

  /**
   * Stored Present marks on every working day (not Sunday, the default week-off) of the month
   * except {@code skip}, marked long ago so no finalise recheck sees them as new.
   */
  void markMonthPresent(String shopId, Employee e, YearMonth month, String... skip) {
    Set<String> skipped = Set.of(skip);
    List<AttendanceDay> days = new ArrayList<>();
    for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
      String date = d.toString();
      if (d.getDayOfWeek() == DayOfWeek.SUNDAY || skipped.contains(date)) {
        continue;
      }
      AttendanceDay day = new AttendanceDay();
      day.setShopId(shopId);
      day.setEmployeeId(e.getId());
      day.setDate(date);
      day.setPeriod(date.substring(0, 7));
      day.setStatus(AttendanceStatus.PRESENT);
      day.setMarkedAt(Instant.parse("2020-01-01T00:00:00Z"));
      day.setMarkedByUserId("owner");
      days.add(day);
    }
    template.insertAll(days);
  }

  /** Same BigDecimal ↔ Decimal128 conversion as the app's {@code MongoConfig} (P7). */
  private static MongoTemplate template(MongoClient client, String db) {
    SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, db);
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

  Employee employee(String shopId, String code, String name, String userId) {
    Employee e = new Employee();
    e.setShopId(shopId);
    e.setCode(code);
    e.setName(name);
    e.setJoinDate("2025-01-01");
    e.setStatus(EmployeeStatus.ACTIVE);
    e.setUserId(userId);
    return template.insert(e);
  }

  /**
   * An employee service behind the real {@link HrPermissionGuard}: the caller holds exactly
   * {@code perms}, is the owner or not, and {@code members} are the shop's active logins.
   */
  EmployeeService employeesAs(java.util.Set<HrPermission> perms, boolean owner,
      List<com.inventory.user.rest.dto.response.ShopMemberSummaryDto> members) {
    HrAccessService access = mock(HrAccessService.class);
    when(access.permissions(anyString(), anyString())).thenReturn(perms);
    when(access.isOwner(anyString(), anyString())).thenReturn(owner);
    HrPermissionGuard realGuard = new HrPermissionGuard();
    ReflectionTestUtils.setField(realGuard, "hrAccessService", access);
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.listActiveMembers(anyString())).thenReturn(members);
    EmployeeService service = new EmployeeService();
    for (String field : List.of("employeeRepository", "mongoTemplate", "employeeMapper",
        "validator", "counterService", "periodLockService", "auditService")) {
      ReflectionTestUtils.setField(service, field, ReflectionTestUtils.getField(employees, field));
    }
    ReflectionTestUtils.setField(service, "guard", realGuard);
    ReflectionTestUtils.setField(service, "membershipService", membership);
    return service;
  }

  void close() {
    template.getDb().drop();
    client.close();
  }
}
