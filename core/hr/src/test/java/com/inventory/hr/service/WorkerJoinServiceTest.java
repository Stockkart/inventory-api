package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.tx.MongoTx;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.BulkJoinResult;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.rest.dto.response.JoinRequestDetail;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.rest.dto.response.JoinRequestsPageResponse;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.inventory.user.api.WorkerAccounts.OwnedShop;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkerJoinServiceTest {

  static final String WORKER = "worker-1";
  static final String APPROVER = "manager-1";
  static final String REQUEST = "req-1";
  static final String OTHER_SHOP = "shop-2";

  @Mock private WorkerJoinRequestRepository requestRepository;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private MongoTx mongoTx;
  @Mock private EmployeeService employeeService;
  @Mock private WorkerAccounts workerAccounts;
  @Mock private ShopNamePort shopNamePort;
  @Mock private HrPermissionGuard guard;
  @Mock private HrAuditService auditService;
  @Mock private HrNotificationService notificationService;
  @Mock private AppRateLimiter rateLimiter;
  @Mock private MetricsWrapper metrics;
  @InjectMocks private WorkerJoinService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    // The counters are a thin class over the mocked wrapper, so the tests below can still assert
    // the exact meter and tags that reach Micrometer (R14.2).
    ReflectionTestUtils.setField(service, "counters", new HrWorkerCounters(metrics));
    when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(shopNamePort.getShopName(SHOP)).thenReturn("Sri Ganesh Stores");
    when(shopNamePort.getShopName(OTHER_SHOP)).thenReturn("Other Stores");
    when(mongoTx.run(anyString(), anyString(), any(Supplier.class)))
        .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(2)).get());
    when(workerAccounts.account(WORKER)).thenReturn(Optional.of(worker(null)));
    when(workerAccounts.hasPhoto(WORKER)).thenReturn(true);
    when(workerAccounts.claimShop(SHOP, WORKER)).thenReturn(true);
    when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenReturn(List.of());
    when(employeeRepository.findMatchCandidates(eq(SHOP), anyCollection())).thenReturn(List.of());
    when(employeeRepository.findAllById(anyCollection())).thenReturn(List.of());
    when(requestRepository.findFirstByUserIdAndStatusIn(anyString(), anyCollection()))
        .thenReturn(Optional.empty());
    when(requestRepository.insert(any(WorkerJoinRequest.class))).thenAnswer(inv -> {
      WorkerJoinRequest r = inv.getArgument(0);
      r.setId(REQUEST);
      return r;
    });
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
        eq(WorkerJoinRequest.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));
  }

  // ---- fixtures -------------------------------------------------------------------------------

  static AccountView worker(String shopId) {
    return new AccountView(WORKER, "Priya Sharma", "Priya@Example.com", "+91 98765 43210",
        "WORKER", shopId, true);
  }

  static AccountView staff() {
    return new AccountView("cashier-1", "Ravi Kumar", "ravi@example.com", "9876543210",
        "CASHIER", SHOP, false);
  }

  static WorkerJoinRequest pending() {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId(REQUEST);
    r.setShopId(SHOP);
    r.setUserId(WORKER);
    r.setStatus(WorkerJoinStatus.PENDING);
    r.setSnapshot(new WorkerJoinRequest.Snapshot("Priya Sharma", "9876543210",
        "priya@example.com", true));
    r.setRequestedAt(Instant.parse("2025-05-01T04:00:00Z"));
    r.setUpdatedAt(r.getRequestedAt());
    return r;
  }

  static Employee employee(String id, String code, EmployeeStatus status, String userId,
      String email, String phone) {
    Employee e = new Employee();
    e.setId(id);
    e.setShopId(SHOP);
    e.setCode(code);
    e.setName("Ravi");
    e.setStatus(status);
    e.setUserId(userId);
    e.setEmail(email);
    e.setPhone(phone);
    return e;
  }

  private void lockSucceeds(WorkerJoinRequest request) {
    WorkerJoinRequest locked = pending();
    locked.setStatus(WorkerJoinStatus.APPROVING);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(locked);
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(request));
  }

  private static Document set(Update update) {
    return (Document) update.getUpdateObject().get("$set");
  }

  // ---- lookup ---------------------------------------------------------------------------------

  @Test
  void lookupAnswersOwnedShopsWithTheOwnersFirstName() {
    when(workerAccounts.shopsOwnedBy("ramesh@example.com")).thenReturn(List.of(
        new OwnedShop(SHOP, "Sri Ganesh Stores", "Ramesh Kumar Iyer")));

    ShopLookupResponse response = service.lookupShops(WORKER, " ramesh@example.com ");

    assertEquals(1, response.getShops().size());
    ShopLookupResponse.Shop shop = response.getShops().get(0);
    assertEquals(SHOP, shop.getShopId());
    assertEquals("Sri Ganesh Stores", shop.getShopName());
    assertEquals("Ramesh", shop.getOwnerFirstName());
    verify(metrics).increment(HrMetricsConstants.WORKER_LOOKUPS_TOTAL, 1,
        "module", "hr", "result", "found");
  }

  @Test
  void everyMissIsTheSameEmptyAnswer() {
    when(workerAccounts.shopsOwnedBy(anyString())).thenReturn(List.of());

    ShopLookupResponse noAccount = service.lookupShops(WORKER, "nobody@example.com");
    ShopLookupResponse notAnOwner = service.lookupShops(WORKER, "cashier@example.com");
    ShopLookupResponse blank = service.lookupShops(WORKER, "   ");

    assertEquals(new ShopLookupResponse(List.of()), noAccount);
    assertEquals(noAccount, notAnOwner);
    assertEquals(noAccount, blank);
  }

  @Test
  void lookupIsRefusedWithTheWorkerTextBeforeAnythingIsRead() {
    when(rateLimiter.tryAcquire(eq("lookup:" + WORKER), eq(10), eq(Duration.ofHours(1))))
        .thenReturn(false);

    BaseException e = assertThrows(BaseException.class,
        () -> service.lookupShops(WORKER, "ramesh@example.com"));

    assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, e.getErrorCode());
    assertEquals("Too many attempts, try again later", e.getMessage());
    verify(workerAccounts, never()).shopsOwnedBy(anyString());
    verifyNoInteractions(metrics);
  }

  // ---- create ---------------------------------------------------------------------------------

  @Test
  void createFreezesTheAccountIntoTheSnapshotAndTellsTheApprovers() {
    JoinRequestSelfResponse response = service.create(WORKER, SHOP);

    ArgumentCaptor<WorkerJoinRequest> saved = ArgumentCaptor.forClass(WorkerJoinRequest.class);
    verify(requestRepository).insert(saved.capture());
    WorkerJoinRequest r = saved.getValue();
    assertEquals(WorkerJoinStatus.PENDING, r.getStatus());
    assertEquals(SHOP, r.getShopId());
    assertEquals(WORKER, r.getUserId());
    assertEquals("Priya Sharma", r.getSnapshot().getName());
    assertEquals("9876543210", r.getSnapshot().getPhone());
    assertEquals("priya@example.com", r.getSnapshot().getEmail());
    assertTrue(r.getSnapshot().isHasPhoto());
    assertNotNull(r.getRequestedAt());
    assertNull(r.getExpiresAt());

    assertEquals(REQUEST, response.getId());
    assertEquals("Sri Ganesh Stores", response.getShopName());
    assertEquals(WorkerJoinStatus.PENDING, response.getStatus());
    verify(notificationService).notifyApprovers(SHOP, HrNotificationType.JOIN_REQUEST_RECEIVED,
        "Priya Sharma asked to join", REQUEST);
    verify(metrics).increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", "hr", "shop", SHOP, "event", "created");
  }

  @Test
  void aStaffAccountCannotFileAJoinRequest() {
    when(workerAccounts.account("cashier-1")).thenReturn(Optional.of(staff()));

    BaseException e = assertThrows(BaseException.class, () -> service.create("cashier-1", SHOP));

    assertEquals(ErrorCode.HR_WORKER_ONLY, e.getErrorCode());
    verify(requestRepository, never()).insert(any(WorkerJoinRequest.class));
  }

  @Test
  void anAccountWithAShopIsRefused() {
    when(workerAccounts.account(WORKER)).thenReturn(Optional.of(worker(OTHER_SHOP)));

    BaseException e = assertThrows(BaseException.class, () -> service.create(WORKER, SHOP));

    assertEquals(ErrorCode.HR_WORKER_HAS_SHOP, e.getErrorCode());
    assertEquals("You are already part of a shop. Leave it first.", e.getMessage());
    verify(requestRepository, never()).insert(any(WorkerJoinRequest.class));
  }

  @Test
  void anAccountWithALinkedActiveEmployeeAnywhereIsRefused() {
    Employee linked = employee("emp-9", "EMP-009", EmployeeStatus.ACTIVE, WORKER, null, null);
    linked.setShopId(OTHER_SHOP);
    when(employeeRepository.findByUserIdAndStatus(WORKER, EmployeeStatus.ACTIVE))
        .thenReturn(List.of(linked));

    BaseException e = assertThrows(BaseException.class, () -> service.create(WORKER, SHOP));

    assertEquals(ErrorCode.HR_WORKER_HAS_SHOP, e.getErrorCode());
    verify(requestRepository, never()).insert(any(WorkerJoinRequest.class));
  }

  @Test
  void aSecondOpenRequestIsRefusedWithoutUsingTheQuota() {
    when(requestRepository.findFirstByUserIdAndStatusIn(eq(WORKER), anyCollection()))
        .thenReturn(Optional.of(pending()));

    BaseException e = assertThrows(BaseException.class, () -> service.create(WORKER, SHOP));

    assertEquals(ErrorCode.HR_WORKER_REQUEST_PENDING, e.getErrorCode());
    verify(rateLimiter, never()).tryAcquire(eq("join:" + WORKER), anyInt(), any());
    verify(requestRepository, never()).insert(any(WorkerJoinRequest.class));
  }

  @Test
  void aDuplicateKeyFromTheIndexIsAlreadyWaiting() {
    when(requestRepository.insert(any(WorkerJoinRequest.class)))
        .thenThrow(new DuplicateKeyException("user_pending_unique"));

    BaseException e = assertThrows(BaseException.class, () -> service.create(WORKER, SHOP));

    assertEquals(ErrorCode.HR_WORKER_REQUEST_PENDING, e.getErrorCode());
    assertEquals("You already have a request waiting.", e.getMessage());
    verify(notificationService, never()).notifyApprovers(any(), any(), any(), any());
    verifyNoInteractions(metrics);
  }

  @Test
  void theSixthRequestInADayIsRefusedAndWritesNothing() {
    when(rateLimiter.tryAcquire(eq("join:" + WORKER), eq(5), eq(Duration.ofHours(24))))
        .thenReturn(false);

    BaseException e = assertThrows(BaseException.class, () -> service.create(WORKER, SHOP));

    assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, e.getErrorCode());
    assertEquals("Too many attempts, try again later", e.getMessage());
    verify(requestRepository, never()).insert(any(WorkerJoinRequest.class));
    verifyNoInteractions(notificationService, metrics);
  }

  // ---- withdraw -------------------------------------------------------------------------------

  @Test
  void withdrawClosesOnlyTheCallersPendingRequest() {
    WorkerJoinRequest withdrawn = pending();
    withdrawn.setStatus(WorkerJoinStatus.WITHDRAWN);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(withdrawn);

    JoinRequestSelfResponse response = service.withdraw(WORKER, REQUEST);

    assertEquals(WorkerJoinStatus.WITHDRAWN, response.getStatus());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
    Document where = query.getValue().getQueryObject();
    assertEquals(WORKER, where.get("userId"));
    assertEquals(WorkerJoinStatus.PENDING, where.get("status"));
    Document set = set(update.getValue());
    assertEquals(WorkerJoinStatus.WITHDRAWN, set.get("status"));
    assertEquals(JoinOutcome.WITHDRAWN, set.get("outcome"));
    assertEquals(WORKER, set.get("decidedByUserId"));
    assertNotNull(set.get("expiresAt"));
    verify(metrics).increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", "hr", "shop", SHOP, "event", "withdrawn");
  }

  @Test
  void withdrawingADecidedRequestSaysAlreadyHandledAndAStrangersIsNotFound() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(null);
    WorkerJoinRequest decided = pending();
    decided.setStatus(WorkerJoinStatus.APPROVED);
    when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(decided));

    BaseException handled = assertThrows(BaseException.class,
        () -> service.withdraw(WORKER, REQUEST));
    assertEquals(ErrorCode.HR_REQUEST_ALREADY_HANDLED, handled.getErrorCode());

    assertThrows(ResourceNotFoundException.class, () -> service.withdraw("someone-else", REQUEST));
    verifyNoInteractions(metrics);
  }

  // ---- inbox and detail -----------------------------------------------------------------------

  @Test
  void inboxRowsCarryTheSnapshotPhotoUrlAndMatchFlag() {
    WorkerJoinRequest join = pending();
    WorkerJoinRequest other = pending();
    other.setId("req-2");
    other.setSnapshot(new WorkerJoinRequest.Snapshot("Anil", "9000000000", "anil@x.in", false));
    when(requestRepository.findByShopIdAndStatusOrderByRequestedAtDesc(eq(SHOP),
        eq(WorkerJoinStatus.PENDING), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(join, other), PageRequest.of(0, 50), 2));
    when(employeeRepository.findMatchCandidates(eq(SHOP), anyCollection())).thenReturn(List.of(
        employee("emp-14", "EMP-014", EmployeeStatus.ACTIVE, null, "PRIYA@example.com", null)));

    JoinRequestsPageResponse page = service.inbox(APPROVER, SHOP, null, null, null);

    verify(guard).require(APPROVER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES);
    assertEquals(2, page.getPendingCount());
    assertEquals(2, page.getItems().size());
    var first = page.getItems().get(0);
    assertEquals(REQUEST, first.getId());
    assertEquals("Priya Sharma", first.getName());
    assertEquals("9876543210", first.getPhone());
    assertEquals("/api/v1/users/" + WORKER + "/photo", first.getPhotoUrl());
    assertTrue(first.isHasPossibleMatch());
    var second = page.getItems().get(1);
    assertNull(second.getPhotoUrl());
    assertFalse(second.isHasPossibleMatch());
    verify(employeeRepository, never()).findAllById(anyCollection());
    assertEquals(0, page.getPage());
    assertEquals(50, page.getSize());
    assertEquals(2L, page.getTotalItems());
    assertEquals(1, page.getTotalPages());
  }

  @Test
  void inboxRefusesOtherStatusesAndOversizedPages() {
    assertThrows(ValidationException.class,
        () -> service.inbox(APPROVER, SHOP, "APPROVED", 0, 10));
    assertThrows(ValidationException.class,
        () -> service.inbox(APPROVER, SHOP, "PENDING", 0, 201));
    assertThrows(ValidationException.class,
        () -> service.inbox(APPROVER, SHOP, "PENDING", -1, 10));
  }

  @Test
  void detailNamesThePossibleMatchByPhoneAfterNormalisation() {
    WorkerJoinRequest request = pending();
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(request));
    when(employeeRepository.findMatchCandidates(eq(SHOP), anyCollection())).thenReturn(List.of(
        employee("emp-14", "EMP-014", EmployeeStatus.EXITED, "someone", "other@x.in",
            "+91 98765-43210")));

    JoinRequestDetail detail = service.detail(APPROVER, SHOP, REQUEST);

    assertTrue(detail.isHasPossibleMatch());
    JoinRequestDetail.PossibleMatch match = detail.getPossibleMatch();
    assertEquals("emp-14", match.getEmployeeId());
    assertEquals("EMP-014", match.getCode());
    assertEquals("Ravi", match.getName());
    assertEquals(EmployeeStatus.EXITED, match.getStatus());
    assertTrue(match.isLinked());
  }

  @Test
  void possibleMatchRules() {
    WorkerJoinRequest request = pending();
    Employee byEmail = employee("a", "EMP-002", EmployeeStatus.EXITED, null, "Priya@EXAMPLE.com",
        "1111111111");
    Employee byPhone = employee("b", "EMP-003", EmployeeStatus.ACTIVE, null, "x@x.in",
        "098765 43210");
    Employee neither = employee("c", "EMP-001", EmployeeStatus.ACTIVE, null, "y@y.in",
        "9876543211");

    assertTrue(WorkerJoinService.possibleMatch(request, List.of()).isEmpty());
    assertTrue(WorkerJoinService.possibleMatch(request, List.of(neither)).isEmpty());
    assertEquals("a", WorkerJoinService.possibleMatch(request, List.of(byEmail)).get().getId());
    // "098765 43210" has 11 digits: not an Indian mobile, so it does not match
    assertTrue(WorkerJoinService.possibleMatch(request, List.of(byPhone)).isEmpty());
    byPhone.setPhone("91 98765 43210");
    assertEquals("b", WorkerJoinService.possibleMatch(request, List.of(byPhone)).get().getId());
    // ACTIVE wins over EXITED whatever the order
    assertEquals("b",
        WorkerJoinService.possibleMatch(request, List.of(byEmail, byPhone)).get().getId());
    assertEquals("b",
        WorkerJoinService.possibleMatch(request, List.of(byPhone, byEmail)).get().getId());
  }

  // ---- approve --------------------------------------------------------------------------------

  @Test
  void approveCreateRunsTheStepsInOrderInsideTheTransactionAndTellsTheWorkerAfter() {
    WorkerJoinRequest request = pending();
    lockSucceeds(request);
    Employee created = employee("emp-15", "EMP-015", EmployeeStatus.ACTIVE, WORKER, null, null);
    when(employeeService.createFromJoinRequest(eq(SHOP), eq(APPROVER), any()))
        .thenReturn(created);

    JoinRequestDecision decision = service.approve(APPROVER, SHOP, REQUEST,
        JoinApproveMode.CREATE, null);

    assertEquals(REQUEST, decision.getId());
    assertEquals(JoinOutcome.APPROVED_CREATED, decision.getOutcome());
    assertEquals("emp-15", decision.getEmployeeId());

    InOrder order = inOrder(guard, mongoTx, mongoTemplate, workerAccounts, employeeService,
        auditService, notificationService, metrics);
    order.verify(guard).require(APPROVER, SHOP, HrPermission.ADD_EDIT_EMPLOYEES);
    order.verify(mongoTx).run(eq(MongoTx.WORKER_JOIN_APPROVE), eq(SHOP), any(Supplier.class));
    order.verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
    order.verify(workerAccounts).claimShop(SHOP, WORKER);
    order.verify(employeeService).createFromJoinRequest(eq(SHOP), eq(APPROVER), any());
    order.verify(workerAccounts).addWorkerMembership(SHOP, WORKER);
    ArgumentCaptor<Update> close = ArgumentCaptor.forClass(Update.class);
    ArgumentCaptor<Query> locked = ArgumentCaptor.forClass(Query.class);
    order.verify(mongoTemplate).updateFirst(locked.capture(), close.capture(),
        eq(WorkerJoinRequest.class));
    order.verify(auditService).record(any(AuditEntry.class));
    order.verify(notificationService).notifyUser(WORKER, HrNotificationType.JOIN_REQUEST_APPROVED,
        "Sri Ganesh Stores accepted you", REQUEST);
    order.verify(metrics).increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", "hr", "shop", SHOP, "event", "approved_created");

    assertEquals(WorkerJoinStatus.APPROVING, locked.getValue().getQueryObject().get("status"));
    Document set = set(close.getValue());
    assertEquals(WorkerJoinStatus.APPROVED, set.get("status"));
    assertEquals(JoinOutcome.APPROVED_CREATED, set.get("outcome"));
    assertEquals("emp-15", set.get("employeeId"));
    assertEquals(APPROVER, set.get("decidedByUserId"));
    Instant decidedAt = (Instant) set.get("decidedAt");
    assertEquals(decidedAt.plus(Duration.ofDays(90)), set.get("expiresAt"));

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.JOIN_REQUEST_APPROVED, audit.getValue().action());
    assertEquals(APPROVER, audit.getValue().actorUserId());
    assertEquals(REQUEST, audit.getValue().entityId());
    assertEquals("emp-15", audit.getValue().employeeId());
    assertEquals("APPROVED_CREATED", audit.getValue().after().get("outcome"));
    assertEquals("CREATE", audit.getValue().after().get("mode"));
    verify(employeeService, never()).linkFromJoinRequest(any(), any(), any(), any());
  }

  @Test
  void approveLinkSetsTheLoginOnTheMatchAndSaysLinked() {
    WorkerJoinRequest request = pending();
    lockSucceeds(request);
    Employee match = employee("emp-14", "EMP-014", EmployeeStatus.ACTIVE, null, null, null);
    when(employeeRepository.findByShopIdAndId(SHOP, "emp-14")).thenReturn(Optional.of(match));
    when(employeeService.linkFromJoinRequest(SHOP, APPROVER, match, WORKER)).thenReturn(match);

    JoinRequestDecision decision = service.approve(APPROVER, SHOP, REQUEST,
        JoinApproveMode.LINK, "emp-14");

    assertEquals(JoinOutcome.APPROVED_LINKED, decision.getOutcome());
    assertEquals("emp-14", decision.getEmployeeId());
    verify(employeeService).linkFromJoinRequest(SHOP, APPROVER, match, WORKER);
    verify(employeeService, never()).createFromJoinRequest(any(), any(), any());
    verify(workerAccounts).claimShop(SHOP, WORKER);
    verify(workerAccounts).addWorkerMembership(SHOP, WORKER);
    // R10.2: an approval that links sends JOIN_REQUEST_APPROVED like any other approval
    verify(notificationService).notifyUser(WORKER, HrNotificationType.JOIN_REQUEST_APPROVED,
        "Sri Ganesh Stores accepted you", REQUEST);
    verify(metrics).increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", "hr", "shop", SHOP, "event", "approved_linked");
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals("APPROVED_LINKED", audit.getValue().after().get("outcome"));
    assertEquals("LINK", audit.getValue().after().get("mode"), "the audit records what was done");
  }

  @Test
  void approveLinkNeedsAnEmployeeIdAndTheEmployeeMustBeTheShops() {
    assertThrows(ValidationException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.LINK, " "));
    assertThrows(ValidationException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, null, null));

    lockSucceeds(pending());
    when(employeeRepository.findByShopIdAndId(SHOP, "emp-x")).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.LINK, "emp-x"));
    verify(workerAccounts, never()).addWorkerMembership(any(), any());
    verify(notificationService, never()).notifyUser(any(), any(), any(), any());
  }

  @Test
  void whenAnotherShopHoldsTheLoginNothingIsCreatedAndTheRequestIsUnlocked() {
    lockSucceeds(pending());
    when(workerAccounts.claimShop(SHOP, WORKER)).thenReturn(false);

    BaseException e = assertThrows(BaseException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.CREATE, null));

    assertEquals(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP, e.getErrorCode());
    assertEquals("This login is already part of another shop.", e.getMessage());
    verify(employeeService, never()).createFromJoinRequest(any(), any(), any());
    verify(workerAccounts, never()).addWorkerMembership(any(), any());
    verifyNoInteractions(auditService, notificationService, metrics);
    // the only update after the failed claim puts APPROVING back to PENDING
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(),
        eq(WorkerJoinRequest.class));
    assertEquals(WorkerJoinStatus.APPROVING, query.getValue().getQueryObject().get("status"));
    assertEquals(WorkerJoinStatus.PENDING, set(update.getValue()).get("status"));
  }

  @Test
  void aStaleLinkedEmployeeInAnotherShopAlsoBlocksTheApproval() {
    lockSucceeds(pending());
    Employee elsewhere = employee("emp-7", "EMP-007", EmployeeStatus.ACTIVE, WORKER, null, null);
    elsewhere.setShopId(OTHER_SHOP);
    when(employeeRepository.findByUserIdAndStatus(WORKER, EmployeeStatus.ACTIVE))
        .thenReturn(List.of(elsewhere));

    BaseException e = assertThrows(BaseException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.CREATE, null));

    assertEquals(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP, e.getErrorCode());
    verify(workerAccounts, never()).claimShop(any(), any());
    verify(employeeService, never()).createFromJoinRequest(any(), any(), any());
  }

  @Test
  void aFailureAfterTheEmployeeWasMadeStillUnlocksAndReportsNothing() {
    lockSucceeds(pending());
    when(employeeService.createFromJoinRequest(eq(SHOP), eq(APPROVER), any()))
        .thenReturn(employee("emp-15", "EMP-015", EmployeeStatus.ACTIVE, WORKER, null, null));
    doThrow(new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "membership write failed"))
        .when(workerAccounts).addWorkerMembership(SHOP, WORKER);

    assertThrows(BaseException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.CREATE, null));

    verifyNoInteractions(auditService, notificationService, metrics);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(any(Query.class), update.capture(),
        eq(WorkerJoinRequest.class));
    assertEquals(WorkerJoinStatus.PENDING, set(update.getValue()).get("status"));
  }

  @Test
  void aRequestThatIsNoLongerPendingIsAlreadyHandled() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(null);
    WorkerJoinRequest rejected = pending();
    rejected.setStatus(WorkerJoinStatus.REJECTED);
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(rejected));

    BaseException e = assertThrows(BaseException.class,
        () -> service.approve(APPROVER, SHOP, REQUEST, JoinApproveMode.CREATE, null));

    assertEquals(ErrorCode.HR_REQUEST_ALREADY_HANDLED, e.getErrorCode());
    assertEquals("This request was already handled", e.getMessage());
    verify(workerAccounts, never()).claimShop(any(), any());
    verify(employeeService, never()).createFromJoinRequest(any(), any(), any());
    // this call never held the lock, so it must not "give back" one another approver may hold
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
        eq(WorkerJoinRequest.class));

    when(requestRepository.findByShopIdAndId(SHOP, "req-other")).thenReturn(Optional.empty());
    assertThrows(ResourceNotFoundException.class,
        () -> service.approve(APPROVER, SHOP, "req-other", JoinApproveMode.CREATE, null));
  }

  @Test
  void aStaffAccountsApprovalClaimsNoShopAndAddsNoWorkerMembership() {
    WorkerJoinRequest request = pending();
    request.setUserId("cashier-1");
    lockSucceeds(request);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(request);
    when(workerAccounts.account("cashier-1")).thenReturn(Optional.of(staff()));
    Employee match = employee("emp-14", "EMP-014", EmployeeStatus.ACTIVE, null, null, null);
    when(employeeRepository.findByShopIdAndId(SHOP, "emp-14")).thenReturn(Optional.of(match));
    when(employeeService.linkFromJoinRequest(SHOP, APPROVER, match, "cashier-1")).thenReturn(match);

    JoinRequestDecision decision = service.approve(APPROVER, SHOP, REQUEST,
        JoinApproveMode.LINK, "emp-14");

    assertEquals(JoinOutcome.APPROVED_LINKED, decision.getOutcome());
    verify(workerAccounts, never()).claimShop(any(), any());
    verify(workerAccounts, never()).addWorkerMembership(any(), any());
    verify(notificationService).notifyUser(eq("cashier-1"),
        eq(HrNotificationType.JOIN_REQUEST_APPROVED), anyString(), eq(REQUEST));
  }

  // ---- reject ---------------------------------------------------------------------------------

  @Test
  void rejectStoresTheTrimmedReasonAndTellsTheWorkerWordForWord() {
    WorkerJoinRequest rejected = pending();
    rejected.setStatus(WorkerJoinStatus.REJECTED);
    rejected.setRejectionReason("Not hiring now");
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(rejected);

    JoinRequestDecision decision = service.reject(APPROVER, SHOP, REQUEST, "  Not hiring now  ");

    assertEquals(JoinOutcome.REJECTED, decision.getOutcome());
    assertNull(decision.getEmployeeId());
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
    assertEquals(SHOP, query.getValue().getQueryObject().get("shopId"));
    assertEquals(WorkerJoinStatus.PENDING, query.getValue().getQueryObject().get("status"));
    Document set = set(update.getValue());
    assertEquals("Not hiring now", set.get("rejectionReason"));
    assertEquals(WorkerJoinStatus.REJECTED, set.get("status"));
    assertEquals(JoinOutcome.REJECTED, set.get("outcome"));
    assertEquals(APPROVER, set.get("decidedByUserId"));
    assertNotNull(set.get("expiresAt"));

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertEquals(HrAuditAction.JOIN_REQUEST_REJECTED, audit.getValue().action());
    assertEquals("Not hiring now", audit.getValue().reason());
    assertEquals(REQUEST, audit.getValue().entityId());
    verify(notificationService).notifyUser(WORKER, HrNotificationType.JOIN_REQUEST_REJECTED,
        "Sri Ganesh Stores did not accept your request: Not hiring now", REQUEST);
    verify(metrics).increment(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL, 1,
        "module", "hr", "shop", SHOP, "event", "rejected");
  }

  @Test
  void aBlankReasonIsStoredAsNoneAndALongOneIsRefusedBeforeAnyWrite() {
    WorkerJoinRequest rejected = pending();
    rejected.setStatus(WorkerJoinStatus.REJECTED);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(rejected);

    service.reject(APPROVER, SHOP, REQUEST, "   ");

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
    assertFalse(set(update.getValue()).containsKey("rejectionReason"));
    assertTrue(update.getValue().getUpdateObject().containsKey("$unset"));
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertNull(audit.getValue().reason());
    verify(notificationService).notifyUser(WORKER, HrNotificationType.JOIN_REQUEST_REJECTED,
        "Sri Ganesh Stores did not accept your request", REQUEST);

    ValidationException tooLong = assertThrows(ValidationException.class,
        () -> service.reject(APPROVER, SHOP, REQUEST, "x".repeat(201)));
    assertTrue(tooLong.getMessage().contains("The reason must be at most 200 characters"));
    verify(mongoTemplate).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class)); // still the one call

    assertEquals("x".repeat(200), WorkerJoinService.normaliseReason(" " + "x".repeat(200) + " "));
    assertNull(WorkerJoinService.normaliseReason(null));
    assertNull(WorkerJoinService.normaliseReason("\t\n"));
  }

  @Test
  void rejectingADecidedRequestIsAlreadyHandled() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class))).thenReturn(null);
    WorkerJoinRequest approved = pending();
    approved.setStatus(WorkerJoinStatus.APPROVED);
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(approved));

    BaseException e = assertThrows(BaseException.class,
        () -> service.reject(APPROVER, SHOP, REQUEST, null));

    assertEquals(ErrorCode.HR_REQUEST_ALREADY_HANDLED, e.getErrorCode());
    verifyNoInteractions(auditService, notificationService, metrics);
  }

  // ---- bulk -----------------------------------------------------------------------------------

  @Test
  void bulkApprovePartitionsTheSelection() {
    WorkerJoinRequest plain = pending();
    WorkerJoinRequest matched = pending();
    matched.setId("req-2");
    matched.setSnapshot(new WorkerJoinRequest.Snapshot("Ravi", "9999999999", "ravi@example.com",
        false));
    WorkerJoinRequest gone = pending();
    gone.setId("req-3");
    gone.setStatus(WorkerJoinStatus.WITHDRAWN);
    when(requestRepository.findByShopIdAndId(SHOP, REQUEST)).thenReturn(Optional.of(plain));
    when(requestRepository.findByShopIdAndId(SHOP, "req-2")).thenReturn(Optional.of(matched));
    when(requestRepository.findByShopIdAndId(SHOP, "req-3")).thenReturn(Optional.of(gone));
    when(requestRepository.findByShopIdAndId(SHOP, "req-4")).thenReturn(Optional.empty());
    when(employeeRepository.findMatchCandidates(eq(SHOP), anyCollection())).thenReturn(List.of(
        employee("emp-14", "EMP-014", EmployeeStatus.ACTIVE, null, "ravi@example.com", null)));
    // the lock succeeds for req-1 only
    WorkerJoinRequest locked = pending();
    locked.setStatus(WorkerJoinStatus.APPROVING);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class)))
        .thenAnswer(inv -> REQUEST.equals(((Query) inv.getArgument(0)).getQueryObject().get("_id"))
            ? locked : null);
    when(employeeService.createFromJoinRequest(eq(SHOP), eq(APPROVER), any()))
        .thenReturn(employee("emp-15", "EMP-015", EmployeeStatus.ACTIVE, WORKER, null, null));

    BulkJoinResult result = service.bulkApprove(APPROVER, SHOP,
        List.of(REQUEST, "req-2", "req-3", "req-4", REQUEST));

    assertEquals(1, result.getApproved());
    assertEquals(1, result.getSkipped().size());
    assertEquals("req-2", result.getSkipped().get(0).getId());
    assertEquals(BulkJoinResult.SkipReason.POSSIBLE_MATCH, result.getSkipped().get(0).getReason());
    assertEquals(2, result.getFailed().size());
    assertEquals("req-3", result.getFailed().get(0).getId());
    assertEquals("This request was already handled", result.getFailed().get(0).getMessage());
    assertEquals("req-4", result.getFailed().get(1).getId());
    verify(employeeService).createFromJoinRequest(eq(SHOP), eq(APPROVER), any()); // once
    verify(mongoTx, org.mockito.Mockito.times(2)).run(eq(MongoTx.WORKER_JOIN_APPROVE), eq(SHOP),
        any(Supplier.class)); // req-1 and req-3 each got their own transaction
  }

  @Test
  void bulkRejectUsesOneReasonForAllAndReportsEachRefusal() {
    WorkerJoinRequest rejected = pending();
    rejected.setStatus(WorkerJoinStatus.REJECTED);
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class)))
        .thenAnswer(inv -> REQUEST.equals(((Query) inv.getArgument(0)).getQueryObject().get("_id"))
            ? rejected : null);
    WorkerJoinRequest decided = pending();
    decided.setId("req-2");
    decided.setStatus(WorkerJoinStatus.APPROVED);
    when(requestRepository.findByShopIdAndId(SHOP, "req-2")).thenReturn(Optional.of(decided));

    BulkJoinResult result = service.bulkReject(APPROVER, SHOP, List.of(REQUEST, "req-2"),
        " Not now ");

    assertEquals(1, result.getApproved());
    assertTrue(result.getSkipped().isEmpty());
    assertEquals(1, result.getFailed().size());
    assertEquals("req-2", result.getFailed().get(0).getId());
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate, org.mockito.Mockito.times(2)).findAndModify(any(Query.class),
        update.capture(), any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
    assertEquals("Not now", set(update.getAllValues().get(0)).get("rejectionReason"));
    assertEquals("Not now", set(update.getAllValues().get(1)).get("rejectionReason"));
  }

  @Test
  void bulkActionsTakeAtMostFiftyIdsAndAtLeastOne() {
    List<String> fiftyOne = java.util.stream.IntStream.rangeClosed(1, 51)
        .mapToObj(i -> "req-" + i).toList();
    assertThrows(ValidationException.class, () -> service.bulkApprove(APPROVER, SHOP, fiftyOne));
    assertThrows(ValidationException.class,
        () -> service.bulkReject(APPROVER, SHOP, fiftyOne, null));
    assertThrows(ValidationException.class, () -> service.bulkApprove(APPROVER, SHOP, List.of()));
    assertThrows(ValidationException.class,
        () -> service.bulkReject(APPROVER, SHOP, List.of(REQUEST), "x".repeat(201)));
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(WorkerJoinRequest.class));
  }

  @Test
  void firstNameIsTheFirstToken() {
    assertEquals("Ramesh", WorkerJoinService.firstName("  Ramesh   Kumar "));
    assertEquals("Ramesh", WorkerJoinService.firstName("Ramesh"));
    assertNull(WorkerJoinService.firstName("   "));
    assertNull(WorkerJoinService.firstName(null));
  }
}
