package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static com.inventory.hr.HrTestFixtures.all;
import static com.inventory.hr.HrTestFixtures.employee;
import static com.inventory.hr.HrTestFixtures.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
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
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.user.service.UserShopMembershipService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
}
