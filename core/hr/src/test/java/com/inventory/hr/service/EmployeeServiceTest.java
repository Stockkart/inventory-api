package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.all;
import static com.inventory.hr.HrTestFixtures.employee;
import static com.inventory.hr.HrTestFixtures.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.mapper.EmployeeMapper;
import com.inventory.hr.mapper.EmployeeMapperImpl;
import com.inventory.hr.rest.dto.request.AddSalaryRevisionRequest;
import com.inventory.hr.rest.dto.request.ExitEmployeeRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.EmployeesPageResponse;
import com.inventory.hr.rest.dto.response.LinkableLoginResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.rest.dto.response.LoginContactDto;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.user.service.UserShopMembershipService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeServiceTest {

  @Mock private EmployeeRepository employeeRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Spy private EmployeeMapper employeeMapper = new EmployeeMapperImpl();
  @Spy private EmployeeValidator validator = new EmployeeValidator();
  @Mock private HrPermissionGuard guard;
  @Mock private HrCounterService counterService;
  @Mock private UserShopMembershipService membershipService;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAuditService auditService;
  @Mock private DevicePairingService pairingService;
  @Mock private HrNotificationService notificationService;
  @Mock private WorkerMembershipEnder membershipEnder;
  @InjectMocks private EmployeeService service;

  private void grant(java.util.Set<HrPermission> perms) {
    when(guard.require(eq(USER), eq(SHOP), any(HrPermission[].class))).thenReturn(perms);
    when(guard.require(eq(USER), eq(SHOP), any(HrPermission.class))).thenReturn(perms);
  }

  private static SaveEmployeeRequest create(String name) {
    SaveEmployeeRequest r = new SaveEmployeeRequest();
    r.setName(name);
    r.setJoinDate("2025-04-10");
    return r;
  }

  private void insertReturnsArgument() {
    when(employeeRepository.insert(any(Employee.class))).thenAnswer(i -> {
      Employee e = i.getArgument(0);
      e.setId("e-new");
      return e;
    });
  }

  @Test
  void anAutoCodeSkipsNumbersTakenByHand() {
    grant(all());
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-001", "EMP-002");
    when(employeeRepository.existsByShopIdAndCode(SHOP, "EMP-001")).thenReturn(true);
    insertReturnsArgument();

    EmployeeResponse r = service.create(USER, SHOP, create("Ravi"));

    assertEquals("EMP-002", r.getCode());
    assertEquals(EmployeeStatus.ACTIVE, r.getStatus());
  }

  @Test
  void aConcurrentCodeClashRetriesAndGivesUpAfterFiveTries() {
    grant(all());
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-1", "EMP-2", "EMP-3", "EMP-4",
        "EMP-5", "EMP-6");
    when(employeeRepository.insert(any(Employee.class)))
        .thenThrow(new DuplicateKeyException("E11000 index: shop_code_unique"));

    BaseException e = assertThrows(BaseException.class,
        () -> service.create(USER, SHOP, create("Ravi")));
    assertEquals(ErrorCode.HR_EMPLOYEE_CODE_TAKEN, e.getErrorCode());
    verify(counterService, org.mockito.Mockito.times(5)).nextEmployeeCode(SHOP);
  }

  @Test
  void aRequestedCodeThatIsTakenIs409() {
    grant(all());
    when(employeeRepository.existsByShopIdAndCode(SHOP, "A-1")).thenReturn(true);
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("A-1");

    assertEquals(ErrorCode.HR_EMPLOYEE_CODE_TAKEN,
        assertThrows(BaseException.class, () -> service.create(USER, SHOP, r)).getErrorCode());
  }

  @Test
  void payOnCreateStartsInTheJoinMonth() {
    grant(all());
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-001");
    insertReturnsArgument();
    SaveEmployeeRequest r = create("Ravi");
    r.setPayType(PayType.MONTHLY);
    r.setSalaryAmount(new BigDecimal("14500"));

    service.create(USER, SHOP, r);

    ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
    verify(employeeRepository).insert(saved.capture());
    assertEquals("2025-04", saved.getValue().getSalaryRevisions().get(0).getEffectiveFrom());
    assertEquals(SHOP, saved.getValue().getShopId());
  }

  @Test
  void payOrPaymentDetailsWithoutViewSalariesAre403() {
    grant(without(HrPermission.VIEW_SALARIES));
    SaveEmployeeRequest r = create("Ravi");
    r.setUpiId("ravi@okaxis");

    assertEquals(ErrorCode.ACCESS_DENIED,
        assertThrows(BaseException.class, () -> service.create(USER, SHOP, r)).getErrorCode());
    verify(employeeRepository, never()).insert(any(Employee.class));
  }

  @Test
  void theThousandthActiveEmployeeIsTheLast() {
    grant(all());
    when(employeeRepository.countByShopIdAndStatus(SHOP, EmployeeStatus.ACTIVE)).thenReturn(1000L);

    assertEquals(ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT, assertThrows(BaseException.class,
        () -> service.create(USER, SHOP, create("Ravi"))).getErrorCode());
    verify(employeeRepository, never()).insert(any(Employee.class));
  }

  @Test
  void aLoginMustBeAnActiveMemberAndFree() {
    grant(all());
    when(membershipService.listActiveMembers(SHOP)).thenReturn(
        List.of(new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED")));
    SaveEmployeeRequest outsider = create("Ravi");
    outsider.setUserId("stranger");
    assertThrows(ValidationException.class, () -> service.create(USER, SHOP, outsider));

    Employee other = employee("e-other");
    other.setUserId("login-1");
    when(employeeRepository.findByShopIdAndUserId(SHOP, "login-1")).thenReturn(Optional.of(other));
    SaveEmployeeRequest taken = create("Ravi");
    taken.setUserId("login-1");
    assertEquals(ErrorCode.HR_LOGIN_ALREADY_LINKED,
        assertThrows(BaseException.class, () -> service.create(USER, SHOP, taken)).getErrorCode());
  }

  @Test
  void anotherShopsEmployeeIsNotFound() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e-elsewhere")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.get(USER, SHOP, "e-elsewhere"));
  }

  @Test
  void theDetailIsMaskedWithoutViewSalaries() {
    grant(without(HrPermission.VIEW_SALARIES));
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));

    EmployeeResponse r = service.get(USER, SHOP, "e1");

    assertTrue(r.isPayHidden());
    assertNull(r.getSalaryAmount());
    assertEquals("XXXXXX9012", r.getBankAccountNumber());
  }

  @Test
  void updateWithoutViewSalariesKeepsPaymentDetails() {
    grant(without(HrPermission.VIEW_SALARIES));
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest r = create("Ravi K");
    r.setCode("EMP-001");
    r.setRevision(0L);

    service.update(USER, SHOP, "e1", r);

    assertEquals("123456789012", stored.getBankAccountNumber());
    assertEquals("Ravi K", stored.getName());
  }

  @Test
  void anAddEditOnlyCallerCannotLinkTheirOwnLogin() {
    grant(without(HrPermission.VIEW_SALARIES));
    when(membershipService.listActiveMembers(SHOP)).thenReturn(
        List.of(new ShopMemberSummaryDto(USER, "Asha", "CASHIER", "ACTIVE")));
    SaveEmployeeRequest r = create("Ravi");
    r.setUserId(USER);

    assertEquals(ErrorCode.ACCESS_DENIED,
        assertThrows(BaseException.class, () -> service.create(USER, SHOP, r)).getErrorCode());
    verify(employeeRepository, never()).insert(any(Employee.class));
  }

  @Test
  void anAddEditOnlyCallerCannotChangeOrClearALink() {
    grant(without(HrPermission.VIEW_SALARIES));
    Employee stored = employee("e1");
    stored.setUserId("login-1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    SaveEmployeeRequest change = create("Ravi");
    change.setCode("EMP-001");
    change.setRevision(0L);
    change.setUserId("login-2");
    SaveEmployeeRequest clear = create("Ravi");
    clear.setCode("EMP-001");
    clear.setRevision(0L);

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, "e1", change)).getErrorCode());
    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, "e1", clear)).getErrorCode());
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void anAddEditOnlyCallerKeepsTheStoredLinkWhenSendingItBack() {
    grant(without(HrPermission.VIEW_SALARIES));
    Employee stored = employee("e1");
    stored.setUserId("login-1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest r = create("Ravi K");
    r.setCode("EMP-001");
    r.setRevision(0L);
    r.setUserId("login-1");

    service.update(USER, SHOP, "e1", r);

    assertEquals("login-1", stored.getUserId());
    verify(auditService, org.mockito.Mockito.times(1)).record(any(AuditEntry.class));
  }

  @Test
  void aViewSalariesMemberLinkingTheirOwnLoginMustBeTheOwner() {
    grant(all());
    org.mockito.Mockito.doThrow(
        new BaseException(ErrorCode.ACCESS_DENIED, "Only the shop owner can change this"))
        .when(guard).requireOwner(USER, SHOP);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(0L);
    r.setUserId(USER);

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, "e1", r)).getErrorCode());
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void theOwnerLinksALoginAndItIsAuditedOnItsOwn() {
    grant(all());
    when(membershipService.listActiveMembers(SHOP)).thenReturn(
        List.of(new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "ACTIVE")));
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(0L);
    r.setUserId("login-1");
    r.setEmail("ravi@example.com");

    EmployeeResponse saved = service.update(USER, SHOP, "e1", r);

    assertEquals("login-1", saved.getUserId());
    ArgumentCaptor<AuditEntry> audits = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, org.mockito.Mockito.times(2)).record(audits.capture());
    AuditEntry link = audits.getAllValues().get(1);
    assertEquals(HrAuditAction.EMPLOYEE_LINK_CHANGED, link.action());
    assertEquals("no", link.before().get("linked"));
    assertEquals("yes", link.after().get("linked"));
    assertEquals("login-1", link.after().get("userId"));
  }

  @Test
  void anAddEditOnlyCallerCannotRedirectTheEmail() {
    grant(without(HrPermission.VIEW_SALARIES));
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(0L);
    r.setEmail("attacker@example.com");

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, "e1", r)).getErrorCode());
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void withoutViewSalariesANullOrSameEmailKeepsTheStoredOne() {
    grant(without(HrPermission.VIEW_SALARIES));
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest same = create("Ravi");
    same.setCode("EMP-001");
    same.setRevision(0L);
    same.setEmail(" RAVI@example.com ");

    service.update(USER, SHOP, "e1", same);
    assertEquals("ravi@example.com", stored.getEmail());

    SaveEmployeeRequest none = create("Ravi");
    none.setCode("EMP-001");
    none.setRevision(0L);
    service.update(USER, SHOP, "e1", none);
    assertEquals("ravi@example.com", stored.getEmail());
    verify(auditService, never()).record(org.mockito.ArgumentMatchers.argThat(
        a -> a.action() == HrAuditAction.EMPLOYEE_EMAIL_CHANGED));
  }

  @Test
  void anEmailChangeIsAuditedAsAFlagWithoutTheAddress() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(0L);
    r.setEmail("ravi.new@example.com");

    service.update(USER, SHOP, "e1", r);

    ArgumentCaptor<AuditEntry> audits = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, org.mockito.Mockito.times(2)).record(audits.capture());
    AuditEntry email = audits.getAllValues().get(1);
    assertEquals(HrAuditAction.EMPLOYEE_EMAIL_CHANGED, email.action());
    assertNull(email.before());
    assertEquals(java.util.Map.of("emailChanged", true), email.after());
    // neither the old nor the new address reaches any audit row
    assertTrue(audits.getAllValues().stream().noneMatch(a -> {
      String text = a.before() + " " + a.after();
      return text.contains("ravi.new@example.com") || text.contains("ravi@example.com");
    }));
  }

  @Test
  void anUpdateMustSayWhichRevisionItEdits() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");

    assertThrows(ValidationException.class, () -> service.update(USER, SHOP, "e1", r));
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void payIsNotChangedThroughUpdate() {
    grant(all());
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setPayType(PayType.DAILY);
    r.setSalaryAmount(new BigDecimal("600"));

    assertThrows(ValidationException.class, () -> service.update(USER, SHOP, "e1", r));
  }

  @Test
  void aStaleRevisionIsAConflict() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(7L);

    assertEquals(ErrorCode.HR_CONCURRENT_UPDATE, assertThrows(BaseException.class,
        () -> service.update(USER, SHOP, "e1", r)).getErrorCode());
  }

  @Test
  void aRevisionForTheSameMonthReplacesItAndEarlierMonthsStay() {
    grant(all());
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    AddSalaryRevisionRequest r = new AddSalaryRevisionRequest();
    r.setEffectiveFrom("2025-06");
    r.setPayType(PayType.MONTHLY);
    r.setAmount(new BigDecimal("16000"));

    service.addSalaryRevision(USER, SHOP, "e1", r);
    r.setAmount(new BigDecimal("16500"));
    service.addSalaryRevision(USER, SHOP, "e1", r);

    assertEquals(List.of("2024-04", "2025-06"), stored.getSalaryRevisions().stream()
        .map(Employee.SalaryRevision::getEffectiveFrom).toList());
    assertEquals(new BigDecimal("16500"), stored.getSalaryRevisions().get(1).getAmount());
    verify(guard, org.mockito.Mockito.atLeastOnce())
        .require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_SALARIES);
    verify(periodLockService, org.mockito.Mockito.times(2)).assertOpen(SHOP, "2025-06");
  }

  @Test
  void exitKeepsTheRowAndIsAudited() {
    grant(all());
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate("2025-05-31");

    EmployeeResponse response = service.exit(USER, SHOP, "e1", r);

    assertEquals(EmployeeStatus.EXITED, response.getStatus());
    assertEquals("2025-05-31", response.getExitDate());
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(entry.capture());
    assertEquals(HrAuditAction.EMPLOYEE_CHANGED, entry.getValue().action());
    assertEquals("e1", entry.getValue().employeeId());
  }

  // ---- exit and the Attendance app pairing (R3.4, R3.5) --------------------------------------

  private Employee exitWith(String exitDate) {
    grant(all());
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate(exitDate);
    service.exit(USER, SHOP, "e1", r);
    return stored;
  }

  @Test
  void exitHandsTheExitDateToThePairingServiceAfterTheSave() {
    exitWith("2025-05-31");

    // the pairing service decides between "unpair now" and "schedule" from the date and the
    // clock; the employee service only tells it the saved exit date
    verify(pairingService).scheduleExitUnpair(SHOP, "e1", LocalDate.parse("2025-05-31"));
    verify(pairingService, never()).clearExitUnpair(any(), any());
  }

  @Test
  void thePairingIsUpdatedOnlyAfterTheEmployeeIsSaved() {
    exitWith("2024-06-30");

    org.mockito.InOrder order = org.mockito.Mockito.inOrder(employeeRepository, pairingService);
    order.verify(employeeRepository).save(any(Employee.class));
    order.verify(pairingService).scheduleExitUnpair(SHOP, "e1", LocalDate.parse("2024-06-30"));
  }

  @Test
  void changingTheExitDateMovesTheSchedule() {
    exitWith("2025-05-31");
    ExitEmployeeRequest later = new ExitEmployeeRequest();
    later.setExitDate("2025-06-15");

    service.exit(USER, SHOP, "e1", later);

    verify(pairingService).scheduleExitUnpair(SHOP, "e1", LocalDate.parse("2025-05-31"));
    verify(pairingService).scheduleExitUnpair(SHOP, "e1", LocalDate.parse("2025-06-15"));
  }

  @Test
  void aSaveOfAnActiveEmployeeClearsAnyPendingSchedule() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    SaveEmployeeRequest r = create("Ravi");
    r.setCode("EMP-001");
    r.setRevision(0L);

    service.update(USER, SHOP, "e1", r);

    verify(pairingService).clearExitUnpair(SHOP, "e1");
    verify(pairingService, never()).scheduleExitUnpair(any(), any(), any());
  }

  @Test
  void aFailedSaveNeverTouchesThePairing() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    when(employeeRepository.save(any(Employee.class)))
        .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("stale"));
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate("2025-05-31");

    assertThrows(BaseException.class, () -> service.exit(USER, SHOP, "e1", r));

    verify(pairingService, never()).scheduleExitUnpair(any(), any(), any());
    verify(pairingService, never()).clearExitUnpair(any(), any());
  }

  @Test
  void aPairingFailureDoesNotUndoTheExit() {
    grant(all());
    Employee stored = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
    when(pairingService.scheduleExitUnpair(any(), any(), any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate("2025-05-31");

    EmployeeResponse response = service.exit(USER, SHOP, "e1", r);

    assertEquals(EmployeeStatus.EXITED, response.getStatus());
    verify(auditService).record(any(AuditEntry.class));
  }

  @Test
  void theListIsPagedAndRowsAreMasked() {
    grant(all());
    when(mongoTemplate.count(any(Query.class), eq(Employee.class))).thenReturn(45L);
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1")));

    EmployeesPageResponse page = service.list(USER, SHOP, "ra(vi", "active", null, 2, 20);

    assertEquals(2, page.getPage().getPage());
    assertEquals(20, page.getPage().getSize());
    assertEquals(45L, page.getPage().getTotalItems());
    assertEquals(3, page.getPage().getTotalPages());
    assertEquals("XXXXXX9012", page.getItems().get(0).getBankAccountNumber());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Employee.class));
    assertEquals(SHOP, query.getValue().getQueryObject().get("shopId"));
    assertEquals(40L, query.getValue().getSkip());
  }

  @Test
  void listParametersAreChecked() {
    grant(all());
    assertThrows(ValidationException.class,
        () -> service.list(USER, SHOP, null, null, null, 0, 101));
    assertThrows(ValidationException.class,
        () -> service.list(USER, SHOP, null, null, null, -1, 20));
    assertThrows(ValidationException.class,
        () -> service.list(USER, SHOP, null, "GONE", null, 0, 20));
  }

  // ---- Attendance app: appPaired and ?paired= (R8.12, R1.9) ----------------------------------

  @Test
  void everyListRowSaysWhetherTheEmployeeHasAPairedPhoneFromOneRead() {
    grant(all());
    when(mongoTemplate.count(any(Query.class), eq(Employee.class))).thenReturn(2L);
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1"), employee("e2")));
    DevicePairing paired = new DevicePairing();
    paired.setEmployeeId("e2");
    when(pairingService.activePairingsByEmployee(SHOP, List.of("e1", "e2")))
        .thenReturn(Map.of("e2", paired));

    EmployeesPageResponse page = service.list(USER, SHOP, null, null, null, null, 0, 20);

    assertEquals(Boolean.FALSE, page.getItems().get(0).getAppPaired());
    assertEquals(Boolean.TRUE, page.getItems().get(1).getAppPaired());
    verify(pairingService).activePairingsByEmployee(SHOP, List.of("e1", "e2"));
    verify(pairingService, never()).activePairedEmployeeIds(any());
  }

  @Test
  void pairedTrueListsOnlyEmployeesWithAnActivePhone() {
    grant(all());
    when(pairingService.activePairedEmployeeIds(SHOP)).thenReturn(Set.of("e2"));
    when(mongoTemplate.count(any(Query.class), eq(Employee.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e2")));

    EmployeesPageResponse page = service.list(USER, SHOP, null, null, null, "true", 0, 20);

    assertEquals(1, page.getItems().size());
    assertEquals(Boolean.TRUE, page.getItems().get(0).getAppPaired());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).count(query.capture(), eq(Employee.class));
    Document id = (Document) query.getValue().getQueryObject().get("_id");
    assertEquals(List.of("e2"), new java.util.ArrayList<>((Collection<?>) id.get("$in")));
    // the shop's active set is read once and reused for the rows
    verify(pairingService).activePairedEmployeeIds(SHOP);
    verify(pairingService, never()).activePairingsByEmployee(any(), any());
  }

  @Test
  void pairedFalseListsOnlyEmployeesWithoutAnActivePhone() {
    grant(all());
    when(pairingService.activePairedEmployeeIds(SHOP)).thenReturn(Set.of("e2"));
    when(mongoTemplate.count(any(Query.class), eq(Employee.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1")));

    EmployeesPageResponse page = service.list(USER, SHOP, null, null, null, "FALSE", 0, 20);

    assertEquals(Boolean.FALSE, page.getItems().get(0).getAppPaired());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).count(query.capture(), eq(Employee.class));
    Document id = (Document) query.getValue().getQueryObject().get("_id");
    assertEquals(List.of("e2"), new java.util.ArrayList<>((Collection<?>) id.get("$nin")));
    assertEquals(SHOP, query.getValue().getQueryObject().get("shopId"));
  }

  @Test
  void pairedMustBeTrueOrFalseAndBlankMeansAll() {
    grant(all());
    assertThrows(ValidationException.class,
        () -> service.list(USER, SHOP, null, null, null, "yes", 0, 20));
    verify(pairingService, never()).activePairedEmployeeIds(any());

    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of());
    service.list(USER, SHOP, null, null, null, " ", 0, 20);
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).count(query.capture(), eq(Employee.class));
    assertNull(query.getValue().getQueryObject().get("_id"));
  }

  @Test
  void theDetailSaysWhetherTheEmployeeHasAPairedPhone() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));
    when(pairingService.activePairing(SHOP, "e1")).thenReturn(Optional.of(new DevicePairing()));

    assertEquals(Boolean.TRUE, service.get(USER, SHOP, "e1").getAppPaired());

    when(pairingService.activePairing(SHOP, "e1")).thenReturn(Optional.empty());
    assertEquals(Boolean.FALSE, service.get(USER, SHOP, "e1").getAppPaired());
  }

  @Test
  void linkableLoginsShowWhoIsLinked() {
    grant(all());
    when(membershipService.listActiveMembers(SHOP)).thenReturn(List.of(
        new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED"),
        new ShopMemberSummaryDto("login-2", "Mohan", "MANAGER", "INVITED")));
    Employee linked = new Employee();
    linked.setId("e1");
    linked.setUserId("login-2");
    when(employeeRepository.findLinkedByShopId(SHOP)).thenReturn(List.of(linked));

    List<LinkableLoginResponse> logins = service.linkableLogins(USER, SHOP);

    assertNull(logins.get(0).getLinkedEmployeeId());
    assertEquals("e1", logins.get(1).getLinkedEmployeeId());
  }

  @Test
  void linkableLoginsCarryEmailAndPhoneForAViewSalariesCaller() {
    grant(all());
    when(membershipService.listActiveMembers(SHOP)).thenReturn(List.of(
        new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED"),
        new ShopMemberSummaryDto("login-2", "Mohan", "MANAGER", "INVITED")));
    when(membershipService.contactsOf(List.of("login-1", "login-2"))).thenReturn(Map.of(
        "login-1", new LoginContactDto("login-1", "Asha", "asha@example.com", "9000000001")));

    List<LinkableLoginResponse> logins = service.linkableLogins(USER, SHOP);

    assertEquals("asha@example.com", logins.get(0).getEmail());
    assertEquals("9000000001", logins.get(0).getPhone());
    // an id the account read did not return keeps null contacts
    assertNull(logins.get(1).getEmail());
    assertNull(logins.get(1).getPhone());
    verify(membershipService, org.mockito.Mockito.times(1)).contactsOf(any());
  }

  @Test
  void linkableLoginsHideEmailAndPhoneWithoutViewSalaries() {
    grant(without(HrPermission.VIEW_SALARIES));
    when(membershipService.listActiveMembers(SHOP)).thenReturn(List.of(
        new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED")));

    List<LinkableLoginResponse> logins = service.linkableLogins(USER, SHOP);

    assertEquals("Asha", logins.get(0).getName());
    assertNull(logins.get(0).getEmail());
    assertNull(logins.get(0).getPhone());
    verify(membershipService, never()).contactsOf(any());
  }

  @Test
  void createWithALinkedLogin() {
    grant(all());
    when(membershipService.listActiveMembers(SHOP)).thenReturn(
        List.of(new ShopMemberSummaryDto("login-1", "Asha", "CASHIER", "INVITED")));
    when(employeeRepository.findByShopIdAndUserId(SHOP, "login-1")).thenReturn(Optional.empty());
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-001");
    insertReturnsArgument();
    SaveEmployeeRequest r = create("Asha");
    r.setUserId("login-1");
    r.setEmail("asha@example.com");
    r.setPhone("9000000001");

    EmployeeResponse saved = service.create(USER, SHOP, r);

    assertEquals("login-1", saved.getUserId());
    ArgumentCaptor<Employee> inserted = ArgumentCaptor.forClass(Employee.class);
    verify(employeeRepository).insert(inserted.capture());
    assertEquals("login-1", inserted.getValue().getUserId());
    assertEquals("asha@example.com", inserted.getValue().getEmail());
    assertEquals("9000000001", inserted.getValue().getPhone());
    // linking someone else's login needs no owner check
    verify(guard, never()).requireOwner(any(), any());
    ArgumentCaptor<AuditEntry> audits = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, org.mockito.Mockito.times(2)).record(audits.capture());
    assertEquals(HrAuditAction.EMPLOYEE_LINK_CHANGED, audits.getAllValues().get(1).action());
    assertEquals("yes", audits.getAllValues().get(1).after().get("linked"));
  }

  // ---- Stockkart Team app: join-request approval (worker-app R4.5, R4.8, R4.9) ------------------

  private static final String WORKER = "worker-1";
  private static final String APPROVER = "manager-1";

  private static WorkerJoinRequest joinRequest() {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("jr-1");
    r.setShopId(SHOP);
    r.setUserId(WORKER);
    r.setStatus(WorkerJoinStatus.PENDING);
    r.setSnapshot(new WorkerJoinRequest.Snapshot(" Priya Sharma ", "9000000002",
        "priya@example.com", true));
    return r;
  }

  private void saveReturnsArgument() {
    when(employeeRepository.save(any(Employee.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void createFromJoinRequestBuildsTheEmployeeFromTheSnapshotAndNothingElse() {
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-015");
    insertReturnsArgument();

    Employee saved = service.createFromJoinRequest(SHOP, APPROVER, joinRequest());

    assertEquals("e-new", saved.getId());
    assertEquals(SHOP, saved.getShopId());
    assertEquals("EMP-015", saved.getCode());
    assertEquals("Priya Sharma", saved.getName());
    assertEquals("9000000002", saved.getPhone());
    assertEquals("priya@example.com", saved.getEmail());
    assertEquals(WORKER, saved.getUserId());
    assertEquals(EmployeeStatus.ACTIVE, saved.getStatus());
    assertEquals(HrDateUtils.format(HrDateUtils.today()), saved.getJoinDate());
    assertEquals(APPROVER, saved.getCreatedByUserId());
    assertEquals(APPROVER, saved.getUpdatedByUserId());
    // every other field empty
    assertNull(saved.getDesignation());
    assertNull(saved.getWorkStart());
    assertNull(saved.getWorkEnd());
    assertNull(saved.getExitDate());
    assertNull(saved.getPaymentMethod());
    assertNull(saved.getBankAccountNumber());
    assertNull(saved.getIfsc());
    assertNull(saved.getUpiId());
    assertNull(saved.getProfileRequestedAt());
    assertNull(saved.getMembershipEndedAt());
    assertTrue(saved.getSalaryRevisions().isEmpty());
    // no permission check of its own: the caller holds ADD_EDIT_EMPLOYEES inside the transaction
    verify(guard, never()).require(any(), any(), any(HrPermission[].class));
    verify(guard, never()).requireOwner(any(), any());
    verify(membershipService, never()).listActiveMembers(any());
  }

  /**
   * R4.8 "all other fields empty", checked over the whole document rather than a hand-written
   * list, so a field added to {@link Employee} later is caught here unless it is named below.
   */
  @Test
  void createFromJoinRequestLeavesEveryFieldOutsideTheSnapshotEmpty() throws Exception {
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-015");
    insertReturnsArgument();

    Employee saved = service.createFromJoinRequest(SHOP, APPROVER, joinRequest());

    Set<String> filled = Set.of("id", "shopId", "code", "name", "phone", "email", "userId",
        "joinDate", "status", "createdAt", "createdByUserId", "updatedAt", "updatedByUserId");
    for (java.lang.reflect.Field field : Employee.class.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
          || filled.contains(field.getName())) {
        continue;
      }
      field.setAccessible(true);
      Object value = field.get(saved);
      if (value instanceof Collection<?> c) {
        assertTrue(c.isEmpty(), field.getName() + " is not empty: " + c);
      } else {
        assertNull(value, field.getName() + " is set: " + value);
      }
    }
    for (String name : filled) {
      java.lang.reflect.Field field = Employee.class.getDeclaredField(name);
      field.setAccessible(true);
      assertNotNull(field.get(saved), name + " should be set");
    }
  }

  @Test
  void createFromJoinRequestWritesOneChangeRowAndOneLinkRow() {
    when(counterService.nextEmployeeCode(SHOP)).thenReturn("EMP-015");
    insertReturnsArgument();

    service.createFromJoinRequest(SHOP, APPROVER, joinRequest());

    ArgumentCaptor<AuditEntry> audits = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService, org.mockito.Mockito.times(2)).record(audits.capture());
    AuditEntry changed = audits.getAllValues().get(0);
    assertEquals(HrAuditAction.EMPLOYEE_CHANGED, changed.action());
    assertEquals(APPROVER, changed.actorUserId());
    assertEquals("e-new", changed.employeeId());
    assertNull(changed.before());
    assertEquals("Priya Sharma", changed.after().get("name"));
    AuditEntry link = audits.getAllValues().get(1);
    assertEquals(HrAuditAction.EMPLOYEE_LINK_CHANGED, link.action());
    assertEquals(APPROVER, link.actorUserId());
    assertEquals("no", link.before().get("linked"));
    assertEquals(WORKER, link.after().get("userId"));
    // the snapshot's phone and e-mail never reach an audit row
    assertTrue(audits.getAllValues().stream().noneMatch(a -> {
      String text = a.before() + " " + a.after();
      return text.contains("9000000002") || text.contains("priya@example.com");
    }));
  }

  @Test
  void createFromJoinRequestRespectsTheActiveEmployeeLimit() {
    when(employeeRepository.countByShopIdAndStatus(SHOP, EmployeeStatus.ACTIVE)).thenReturn(1000L);

    assertEquals(ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT, assertThrows(BaseException.class,
        () -> service.createFromJoinRequest(SHOP, APPROVER, joinRequest())).getErrorCode());
    verify(employeeRepository, never()).insert(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
  }

  @Test
  void linkFromJoinRequestSetsOnlyTheUserIdAndAuditsTheLink() {
    saveReturnsArgument();
    Employee match = employee("e-match");
    match.setCode("EMP-014");
    Employee before = employee("e-match");
    before.setCode("EMP-014");

    Employee saved = service.linkFromJoinRequest(SHOP, APPROVER, match, WORKER);

    assertEquals(WORKER, saved.getUserId());
    assertEquals(APPROVER, saved.getUpdatedByUserId());
    // nothing but the link, the update stamp and the actor changed
    before.setUserId(WORKER);
    before.setUpdatedAt(saved.getUpdatedAt());
    before.setUpdatedByUserId(APPROVER);
    assertEquals(before, saved);
    ArgumentCaptor<AuditEntry> audits = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audits.capture());
    assertEquals(HrAuditAction.EMPLOYEE_LINK_CHANGED, audits.getValue().action());
    assertEquals(APPROVER, audits.getValue().actorUserId());
    assertEquals("no", audits.getValue().before().get("linked"));
    assertEquals(WORKER, audits.getValue().after().get("userId"));
    // not the manual-link rule: no VIEW_SALARIES or owner check, no membership check
    verify(guard, never()).require(any(), any(), any(HrPermission[].class));
    verify(guard, never()).requireOwner(any(), any());
    verify(membershipService, never()).listActiveMembers(any());
  }

  @Test
  void linkFromJoinRequestRefusesAnEmployeeLinkedToAnotherLogin() {
    Employee match = employee("e-match");
    match.setCode("EMP-014");
    match.setUserId("someone-else");

    BaseException e = assertThrows(BaseException.class,
        () -> service.linkFromJoinRequest(SHOP, APPROVER, match, WORKER));

    assertEquals(ErrorCode.HR_MATCH_LINKED_ELSEWHERE, e.getErrorCode());
    assertEquals("EMP-014 is already linked to another login", e.getMessage());
    assertEquals("someone-else", match.getUserId());
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
  }

  @Test
  void linkFromJoinRequestRefusesAnExitedEmployee() {
    Employee match = employee("e-match");
    match.setCode("EMP-014");
    match.setStatus(EmployeeStatus.EXITED);
    match.setExitDate("2025-03-31");

    BaseException e = assertThrows(BaseException.class,
        () -> service.linkFromJoinRequest(SHOP, APPROVER, match, WORKER));

    assertEquals(ErrorCode.HR_MATCH_EXITED, e.getErrorCode());
    assertEquals("EMP-014 has exited. Create a new employee instead.", e.getMessage());
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void linkFromJoinRequestToTheSameLoginAgainWritesNothing() {
    Employee match = employee("e-match");
    match.setUserId(WORKER);

    Employee result = service.linkFromJoinRequest(SHOP, APPROVER, match, WORKER);

    assertEquals(WORKER, result.getUserId());
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
  }

  @Test
  void linkFromJoinRequestNeverTouchesAnotherShopsEmployee() {
    Employee elsewhere = employee("e-elsewhere");
    elsewhere.setShopId("shop-2");

    assertThrows(ResourceNotFoundException.class,
        () -> service.linkFromJoinRequest(SHOP, APPROVER, elsewhere, WORKER));
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  // ---- Stockkart Team app: the worker's own record (worker-app R5.2, R5.3, R8.2) ----------------

  private Employee ownEmployee() {
    Employee own = employee("e-own");
    own.setUserId(WORKER);
    when(employeeRepository.findByShopIdAndUserId(SHOP, WORKER)).thenReturn(Optional.of(own));
    return own;
  }

  @Test
  void exitBySelfExitsTodayAsSelfAndEndsTheMembershipWithinTheSave() {
    Employee own = ownEmployee();
    saveReturnsArgument();

    Employee saved = service.exitBySelf(SHOP, WORKER);

    String today = HrDateUtils.format(HrDateUtils.today());
    assertEquals(EmployeeStatus.EXITED, saved.getStatus());
    assertEquals(today, saved.getExitDate());
    assertEquals(EmployeeService.SELF_ACTOR, saved.getUpdatedByUserId());
    assertEquals(WORKER, saved.getUserId()); // the link stays so payslips remain reachable
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.EMPLOYEE_CHANGED, audit.getValue().action());
    assertEquals(EmployeeService.SELF_ACTOR, audit.getValue().actorUserId());
    assertEquals("ACTIVE", audit.getValue().before().get("status"));
    assertEquals("EXITED", audit.getValue().after().get("status"));
    assertEquals(today, audit.getValue().after().get("exitDate"));
    // the save unpairs the phone (HR R3.4) and ends the WORKER membership (R5.2), in that order,
    // after the employee is stored
    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(employeeRepository, pairingService, membershipEnder);
    order.verify(employeeRepository).save(own);
    order.verify(pairingService).scheduleExitUnpair(SHOP, "e-own", HrDateUtils.today());
    order.verify(membershipEnder).onExit(own);
    // no HR permission is needed to leave: the record is the caller's own
    verify(guard, never()).require(any(), any(), any(HrPermission[].class));
  }

  @Test
  void exitBySelfNeedsALinkedEmployeeInTheShop() {
    when(employeeRepository.findByShopIdAndUserId(SHOP, WORKER)).thenReturn(Optional.empty());

    assertEquals(ErrorCode.HR_NOT_LINKED, assertThrows(BaseException.class,
        () -> service.exitBySelf(SHOP, WORKER)).getErrorCode());
    verify(employeeRepository, never()).save(any(Employee.class));
  }

  @Test
  void exitBySelfOnAnExitedEmployeeChangesNothing() {
    Employee own = ownEmployee();
    own.setStatus(EmployeeStatus.EXITED);
    own.setExitDate("2025-03-31");

    Employee result = service.exitBySelf(SHOP, WORKER);

    assertEquals("2025-03-31", result.getExitDate());
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
    verify(membershipEnder, never()).onExit(any());
  }

  @Test
  void aManagersExitWithAFutureDateLeavesTheMembershipToTheJob() {
    grant(all());
    Employee stored = employee("e1");
    stored.setUserId(WORKER);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    saveReturnsArgument();
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate(HrDateUtils.format(HrDateUtils.today().plusDays(3)));

    service.exit(USER, SHOP, "e1", r);

    verify(membershipEnder, never()).onExit(any());
  }

  @Test
  void aManagersExitWithAPastDateEndsTheMembershipAtOnce() {
    grant(all());
    Employee stored = employee("e1");
    stored.setUserId(WORKER);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    saveReturnsArgument();
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate("2024-06-30");

    service.exit(USER, SHOP, "e1", r);

    verify(membershipEnder).onExit(stored);
  }

  @Test
  void anExitOfAnEmployeeWithoutALoginOrAlreadyEndedNeverCallsTheEnder() {
    grant(all());
    Employee unlinked = employee("e1");
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(unlinked));
    saveReturnsArgument();
    ExitEmployeeRequest r = new ExitEmployeeRequest();
    r.setExitDate("2024-06-30");
    service.exit(USER, SHOP, "e1", r);

    Employee ended = employee("e2");
    ended.setUserId(WORKER);
    ended.setMembershipEndedAt(java.time.Instant.EPOCH);
    when(employeeRepository.findByShopIdAndId(SHOP, "e2")).thenReturn(Optional.of(ended));
    service.exit(USER, SHOP, "e2", r);

    verify(membershipEnder, never()).onExit(any());
  }

  @Test
  void aMembershipEnderFailureDoesNotUndoTheExit() {
    ownEmployee();
    saveReturnsArgument();
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException(
        "down")).when(membershipEnder).onExit(any());

    Employee saved = service.exitBySelf(SHOP, WORKER);

    assertEquals(EmployeeStatus.EXITED, saved.getStatus());
    verify(auditService).record(any(AuditEntry.class));
  }

  @Test
  void updateSelfDetailsChangesNameAndPhoneOnlyWithOneSelfAuditRow() {
    Employee own = ownEmployee();
    saveReturnsArgument();

    Employee saved = service.updateSelfDetails(SHOP, WORKER, "  Ravi K  ", "+91 90000 00009")
        .orElseThrow();

    assertEquals("Ravi K", saved.getName());
    assertEquals("9000000009", saved.getPhone());
    assertEquals(EmployeeService.SELF_ACTOR, saved.getUpdatedByUserId());
    // everything else as it was
    assertEquals("EMP-001", saved.getCode());
    assertEquals("ravi@example.com", saved.getEmail());
    assertEquals("Helper", saved.getDesignation());
    assertEquals("123456789012", saved.getBankAccountNumber());
    assertEquals(1, saved.getSalaryRevisions().size());
    assertEquals(WORKER, saved.getUserId());
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.EMPLOYEE_CHANGED, audit.getValue().action());
    assertEquals(EmployeeService.SELF_ACTOR, audit.getValue().actorUserId());
    assertEquals("Ravi Kumar", audit.getValue().before().get("name"));
    assertEquals("Ravi K", audit.getValue().after().get("name"));
    assertEquals(Boolean.TRUE, audit.getValue().after().get(EmployeeService.PHONE_CHANGED));
    // the number itself stays out of the audit row
    String text = audit.getValue().before() + " " + audit.getValue().after();
    assertTrue(!text.contains("9000000009") && !text.contains("9876543210"));
    verify(employeeRepository).save(own);
    verify(guard, never()).require(any(), any(), any(HrPermission[].class));
  }

  @Test
  void updateSelfDetailsWithAnUnchangedPhoneSetsNoPhoneFlag() {
    ownEmployee();
    saveReturnsArgument();

    service.updateSelfDetails(SHOP, WORKER, "Ravi Kumar", "98765-43210");

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertNull(audit.getValue().after().get(EmployeeService.PHONE_CHANGED));
  }

  @Test
  void updateSelfDetailsRefusesABadNameOrPhoneAndChangesNothing() {
    ownEmployee();

    assertThrows(ValidationException.class,
        () -> service.updateSelfDetails(SHOP, WORKER, " R ", "9000000009"));
    assertThrows(ValidationException.class,
        () -> service.updateSelfDetails(SHOP, WORKER, "x".repeat(101), "9000000009"));
    assertThrows(ValidationException.class,
        () -> service.updateSelfDetails(SHOP, WORKER, "Ravi", "12345"));
    assertThrows(ValidationException.class,
        () -> service.updateSelfDetails(SHOP, WORKER, "Ravi", null));
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
  }

  @Test
  void updateSelfDetailsWithoutAnEmployeeIsEmptyAndWritesNothing() {
    when(employeeRepository.findByShopIdAndUserId(SHOP, WORKER)).thenReturn(Optional.empty());

    assertTrue(service.updateSelfDetails(SHOP, WORKER, "Ravi", "9000000009").isEmpty());
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
  }

  // ---- Stockkart Team app: "Request profile details" (worker-app R8.7) --------------------------

  @Test
  void requestProfileStampsAuditsAndNotifiesTheLinkedAccount() {
    grant(all());
    Employee stored = employee("e1");
    stored.setUserId(WORKER);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    saveReturnsArgument();
    when(pairingService.shopName(SHOP)).thenReturn("Sri Ganesh Stores");
    when(pairingService.activePairing(SHOP, "e1")).thenReturn(Optional.of(new DevicePairing()));

    EmployeeResponse r = service.requestProfile(USER, SHOP, "e1");

    assertNotNull(r.getProfileRequestedAt());
    assertEquals(stored.getProfileRequestedAt(), r.getProfileRequestedAt());
    assertEquals(Boolean.TRUE, r.getAppPaired());
    verify(guard).require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES);
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.PROFILE_REQUESTED, audit.getValue().action());
    assertEquals(USER, audit.getValue().actorUserId());
    assertEquals("e1", audit.getValue().employeeId());
    assertNull(audit.getValue().before().get("profileRequestedAt"));
    assertEquals(stored.getProfileRequestedAt().toString(),
        audit.getValue().after().get("profileRequestedAt"));
    verify(notificationService).notifyUser(WORKER, HrNotificationType.PROFILE_REQUESTED,
        "Sri Ganesh Stores asked for your profile details", null);
  }

  @Test
  void askingAgainWhileARequestIsOutstandingMovesTheStampAndKeepsThePreviousOneInTheAudit() {
    grant(all());
    Employee stored = employee("e1");
    stored.setUserId(WORKER);
    java.time.Instant earlier = java.time.Instant.parse("2025-06-01T09:00:00Z");
    stored.setProfileRequestedAt(earlier);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(stored));
    saveReturnsArgument();
    when(pairingService.shopName(SHOP)).thenReturn("Sri Ganesh Stores");

    EmployeeResponse r = service.requestProfile(USER, SHOP, "e1");

    // the stamp moves to now (the implementation's documented behaviour: asking again re-asks)
    assertTrue(r.getProfileRequestedAt().isAfter(earlier));
    assertEquals(stored.getProfileRequestedAt(), r.getProfileRequestedAt());
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.PROFILE_REQUESTED, audit.getValue().action());
    assertEquals(earlier.toString(), audit.getValue().before().get("profileRequestedAt"));
    assertEquals(stored.getProfileRequestedAt().toString(),
        audit.getValue().after().get("profileRequestedAt"));
    // and the worker is told again: one row per ask (R10.2)
    verify(notificationService, org.mockito.Mockito.times(1)).notifyUser(eq(WORKER),
        eq(HrNotificationType.PROFILE_REQUESTED), any(), eq(null));
  }

  @Test
  void theDetailCarriesProfileRequestedAtAndNullWhenNeverAsked() {
    grant(all());
    Employee asked = employee("e1");
    java.time.Instant at = java.time.Instant.parse("2025-06-01T09:00:00Z");
    asked.setProfileRequestedAt(at);
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(asked));
    when(employeeRepository.findByShopIdAndId(SHOP, "e2")).thenReturn(Optional.of(employee("e2")));

    assertEquals(at, service.get(USER, SHOP, "e1").getProfileRequestedAt());
    assertNull(service.get(USER, SHOP, "e2").getProfileRequestedAt());
  }

  @Test
  void requestProfileNeedsAddEditEmployees() {
    org.mockito.Mockito.doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "no"))
        .when(guard).require(USER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES);

    assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(BaseException.class,
        () -> service.requestProfile(USER, SHOP, "e1")).getErrorCode());
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(notificationService, never()).notifyUser(any(), any(), any(), any());
  }

  @Test
  void requestProfileOnAnEmployeeWithoutALoginIsRefused() {
    grant(all());
    when(employeeRepository.findByShopIdAndId(SHOP, "e1")).thenReturn(Optional.of(employee("e1")));

    assertThrows(ValidationException.class, () -> service.requestProfile(USER, SHOP, "e1"));
    verify(employeeRepository, never()).save(any(Employee.class));
    verify(auditService, never()).record(any(AuditEntry.class));
    verify(notificationService, never()).notifyUser(any(), any(), any(), any());
  }
}
