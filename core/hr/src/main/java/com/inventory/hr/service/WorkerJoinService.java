package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.tx.MongoTx;
import com.inventory.common.util.PhoneNumbers;
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
import com.inventory.hr.rest.dto.response.JoinRequestRow;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.rest.dto.response.JoinRequestsPageResponse;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.inventory.user.api.WorkerAccounts.OwnedShop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * A worker's way into a shop (worker-app R3, R4, R5.1; design DD1, DD2, DD11, DD12, DD13).
 *
 * <p>Worker side: {@link #lookupShops} finds a shop by the owner's e-mail (10 an hour, every miss
 * one identical empty answer); {@link #create} files the one Pending request an account may have,
 * with the account's name, phone, e-mail and photo flag frozen into a snapshot; {@link #withdraw}
 * takes it back.
 *
 * <p>Manager side (every method requires {@code ADD_EDIT_EMPLOYEES}): {@link #inbox} and {@link
 * #detail} with the possible match (R4.3); {@link #approve}, which runs in one MongoDB transaction
 * ({@link MongoTx}) in the order lock the request → claim the account's shop → create or link the
 * employee → add the WORKER membership → mark the request approved, so a failure anywhere leaves
 * the request Pending and nothing half-made (R14.4); {@link #reject}; and the bulk actions, which
 * decide each selected request in its own transaction and skip the ones with a possible match.
 *
 * <p>Writes that leave the database (audit rows, notifications, counters) run after the commit
 * through {@link MongoTx#afterCommit}, once, and only when the data was saved. Rate-limit refusals
 * happen before any read of {@code hr_join_requests} and write nothing (Property 28).
 */
@Slf4j
@Service
public class WorkerJoinService {

  /** Audit entity type of a join request. */
  static final String ENTITY = "WORKER_JOIN_REQUEST";
  /** Where a worker's photo is served from; Bearer-protected, never public (R11.5). */
  static final String PHOTO_URL = "/api/v1/users/%s/photo";

  static final int LOOKUP_LIMIT = 10;
  static final Duration LOOKUP_WINDOW = Duration.ofHours(1);
  static final int JOIN_LIMIT = 5;
  static final Duration JOIN_WINDOW = Duration.ofHours(24);
  static final String LOOKUP_KEY = "lookup:";
  static final String JOIN_KEY = "join:";

  static final int MAX_REASON_LENGTH = 200;
  static final String REASON_TOO_LONG = "The reason must be at most 200 characters";
  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 200;
  static final int MAX_BULK = 50;
  /** A decided request is kept this long, then the TTL index removes it (R4.12, R11.3). */
  static final Duration RETENTION = Duration.ofDays(90);
  /** Statuses an approver may list (R4.1). */
  static final Set<WorkerJoinStatus> INBOX_STATUSES =
      EnumSet.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.REJECTED);
  /** The statuses that count as "an open request" (the partial unique index's filter). */
  static final Set<WorkerJoinStatus> OPEN =
      EnumSet.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.APPROVING);
  private static final Set<EmployeeStatus> MATCH_STATUSES =
      EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.EXITED);

  /**
   * The steps of {@link #approve}'s transaction, in order, as {@link #afterStep} names them:
   * the request is locked ({@code APPROVING}), the account's shop is claimed, the employee is
   * created or linked, the WORKER membership is added, the request is marked {@code APPROVED}.
   */
  static final List<String> APPROVE_STEPS =
      List.of("lock", "claim", "employee", "membership", "close");

  /**
   * Test seam for Property 30 (R14.4): called inside the approval transaction after each of
   * {@link #APPROVE_STEPS}, with the step's name. A no-op in production; a test replaces it to
   * fail the approval at a chosen point and check that nothing was kept.
   */
  Consumer<String> afterStep = step -> { };

  @Autowired private WorkerJoinRequestRepository requestRepository;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private MongoTx mongoTx;

  @Autowired private EmployeeService employeeService;

  @Autowired private WorkerAccounts workerAccounts;

  @Autowired private ShopNamePort shopNamePort;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrAuditService auditService;

  @Autowired private HrNotificationService notificationService;

  @Autowired private AppRateLimiter rateLimiter;

  @Autowired private HrWorkerCounters counters;

  // ---- worker side ----------------------------------------------------------------------------

  /**
   * The shops an owner e-mail owns (R3.2, R3.3, R11.4): {@code shopId}, name and the owner's first
   * name. Every miss is the same empty list. Limited to {@value #LOOKUP_LIMIT} calls an hour per
   * account, refused before anything is read.
   *
   * @throws BaseException {@code HR_TOO_MANY_ATTEMPTS} with the worker text (R3.8)
   */
  public ShopLookupResponse lookupShops(String userId, String ownerEmail) {
    requireUser(userId);
    if (!rateLimiter.tryAcquire(LOOKUP_KEY + userId, LOOKUP_LIMIT, LOOKUP_WINDOW)) {
      throw tooManyAttempts();
    }
    List<ShopLookupResponse.Shop> shops = new ArrayList<>();
    if (StringUtils.hasText(ownerEmail)) {
      for (OwnedShop shop : workerAccounts.shopsOwnedBy(ownerEmail.trim())) {
        shops.add(new ShopLookupResponse.Shop(shop.shopId(), shop.shopName(),
            firstName(shop.ownerName())));
      }
    }
    counters.lookup(!shops.isEmpty());
    return new ShopLookupResponse(shops);
  }

  /**
   * Files a join request for the shop (R3.4, R3.5, R3.7, R3.9). The snapshot is the account as it
   * is now and is never updated. One open request per account: a second one is refused, by the
   * pre-check and, in a race, by the partial unique index.
   *
   * @throws BaseException {@code HR_WORKER_ONLY} for a staff account; {@code HR_WORKER_HAS_SHOP}
   *     when the account already has a shop or a linked ACTIVE employee anywhere; {@code
   *     HR_WORKER_REQUEST_PENDING} when one is already waiting; {@code HR_TOO_MANY_ATTEMPTS} after
   *     {@value #JOIN_LIMIT} requests in 24 hours (R3.8)
   */
  public JoinRequestSelfResponse create(String userId, String shopId) {
    requireUser(userId);
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    AccountView account = account(userId);
    if (!account.worker()) {
      throw new BaseException(ErrorCode.HR_WORKER_ONLY, ErrorCode.HR_WORKER_ONLY.getMessage());
    }
    requireNoShop(account);
    if (requestRepository.findFirstByUserIdAndStatusIn(userId, OPEN).isPresent()) {
      throw new BaseException(ErrorCode.HR_WORKER_REQUEST_PENDING,
          ErrorCode.HR_WORKER_REQUEST_PENDING.getMessage());
    }
    if (!rateLimiter.tryAcquire(JOIN_KEY + userId, JOIN_LIMIT, JOIN_WINDOW)) {
      throw tooManyAttempts();
    }
    WorkerJoinRequest saved = insertOpen(newRequest(account, shopId));
    notificationService.notifyApprovers(shopId, HrNotificationType.JOIN_REQUEST_RECEIVED,
        WorkerNotificationTexts.joinRequestReceived(saved.getSnapshot().getName()), saved.getId());
    counters.joinRequestCreated(saved);
    return self(saved);
  }

  /**
   * Takes back the caller's own Pending request (R3.6): one conditional update, so a request an
   * approver decided in the meantime is left as decided.
   *
   * @throws BaseException {@code HR_REQUEST_ALREADY_HANDLED} when it is no longer Pending
   * @throws ResourceNotFoundException when the caller has no request with this id
   */
  public JoinRequestSelfResponse withdraw(String userId, String requestId) {
    requireUser(userId);
    Instant now = Instant.now();
    Query own = Query.query(Criteria.where("_id").is(requestId)
        .and("userId").is(userId)
        .and("status").is(WorkerJoinStatus.PENDING));
    Update close = new Update()
        .set("status", WorkerJoinStatus.WITHDRAWN)
        .set("outcome", JoinOutcome.WITHDRAWN)
        .set("decidedAt", now)
        .set("decidedByUserId", userId)
        .set("expiresAt", now.plus(RETENTION))
        .set("updatedAt", now);
    WorkerJoinRequest withdrawn = mongoTemplate.findAndModify(own, close,
        FindAndModifyOptions.options().returnNew(true), WorkerJoinRequest.class);
    if (withdrawn == null) {
      requestRepository.findById(requestId)
          .filter(r -> userId.equals(r.getUserId()))
          .orElseThrow(() -> new ResourceNotFoundException("Join request", "id", requestId));
      throw alreadyHandled();
    }
    counters.joinRequestWithdrawn(withdrawn);
    return self(withdrawn);
  }

  // ---- manager side ---------------------------------------------------------------------------

  /**
   * One page of the shop's requests in {@code status} (Pending or Rejected), newest first, with the
   * shop's pending count (R4.1, R4.2). {@code size} is at most {@value #MAX_PAGE_SIZE} (R11.3).
   */
  public JoinRequestsPageResponse inbox(String approverId, String shopId, String status,
      Integer page, Integer size) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    WorkerJoinStatus wanted = parseInboxStatus(status);
    int p = page != null ? page : 0;
    int s = size != null ? size : DEFAULT_PAGE_SIZE;
    if (p < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    if (s < 1 || s > MAX_PAGE_SIZE) {
      throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
    Page<WorkerJoinRequest> found = requestRepository.findByShopIdAndStatusOrderByRequestedAtDesc(
        shopId, wanted, PageRequest.of(p, s));
    long pending = wanted == WorkerJoinStatus.PENDING
        ? found.getTotalElements()
        : requestRepository.countByShopIdAndStatus(shopId, WorkerJoinStatus.PENDING);
    List<Employee> candidates = matchCandidates(shopId);
    List<JoinRequestRow> rows = new ArrayList<>(found.getNumberOfElements());
    for (WorkerJoinRequest r : found.getContent()) {
      rows.add(row(new JoinRequestRow(), r, candidates));
    }
    return new JoinRequestsPageResponse(rows, pending, p, s, found.getTotalElements(),
        found.getTotalPages());
  }

  /** One request with its possible match (R4.3). */
  public JoinRequestDetail detail(String approverId, String shopId, String id) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    WorkerJoinRequest request = find(shopId, id);
    List<Employee> candidates = matchCandidates(shopId);
    JoinRequestDetail detail = (JoinRequestDetail) row(new JoinRequestDetail(), request,
        candidates);
    possibleMatch(request, candidates).ifPresent(e -> detail.setPossibleMatch(
        new JoinRequestDetail.PossibleMatch(e.getId(), e.getCode(), e.getName(), e.getStatus(),
            e.getUserId() != null)));
    return detail;
  }

  /**
   * Approves a Pending request (R4.4, R4.5, R4.8–R4.10, R5.1; design DD12) in one transaction:
   * {@code PENDING → APPROVING}; for a WORKER account, claim its one shop (DD1); create the
   * employee from the snapshot ({@code CREATE}) or link the given one ({@code LINK}); for a WORKER
   * account, add the WORKER membership; {@code APPROVED} with outcome, employee and decision
   * stamps. After the commit: one {@code JOIN_REQUEST_APPROVED} audit row, one {@code
   * JOIN_REQUEST_APPROVED} notification to the worker whichever mode was used (R10.2), one counter
   * increment.
   *
   * @param mode how to approve; {@code employeeId} is required with {@code LINK} and ignored with
   *     {@code CREATE}
   * @throws ValidationException when {@code mode} is missing or {@code LINK} names no employee
   * @throws BaseException {@code HR_REQUEST_ALREADY_HANDLED} when the request is not Pending;
   *     {@code HR_WORKER_LOGIN_IN_OTHER_SHOP} when another shop holds the account (nothing is
   *     changed, the request stays Pending); {@code HR_MATCH_LINKED_ELSEWHERE} / {@code
   *     HR_MATCH_EXITED} from the link rule
   * @throws ResourceNotFoundException when the request, or the employee to link, is not this shop's
   */
  public JoinRequestDecision approve(String approverId, String shopId, String id,
      JoinApproveMode mode, String employeeId) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    if (mode == null) {
      throw new ValidationException("mode must be CREATE or LINK");
    }
    if (mode == JoinApproveMode.LINK && !StringUtils.hasText(employeeId)) {
      throw new ValidationException("employeeId is required to link");
    }
    Approved approved;
    // set once this call has taken the PENDING → APPROVING lock, so a failure is undone only for
    // a lock this call holds: a refusal because somebody else holds it must not release theirs
    AtomicBoolean locked = new AtomicBoolean();
    try {
      approved = mongoTx.run(MongoTx.WORKER_JOIN_APPROVE, shopId,
          () -> approveInTransaction(approverId, shopId, id, mode, employeeId, locked));
    } catch (RuntimeException e) {
      // the transaction rolled the lock back; without transactions (mongo.transactions.enabled=
      // false) the row would stay APPROVING until the sweep, so put it back now, conditionally
      if (locked.get()) {
        unlock(id);
      }
      throw e;
    }
    WorkerJoinRequest request = approved.request();
    MongoTx.afterCommit(() -> {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("outcome", approved.outcome().name());
      after.put("mode", approved.mode().name());
      auditService.record(new AuditEntry(shopId, approverId, HrAuditAction.JOIN_REQUEST_APPROVED,
          ENTITY, request.getId(), approved.employeeId(), null, null, null, after, null));
      notificationService.notifyUser(request.getUserId(), HrNotificationType.JOIN_REQUEST_APPROVED,
          WorkerNotificationTexts.joinRequestApproved(shopName(shopId)), request.getId());
      counters.joinRequestApproved(request, approved.outcome());
    });
    return new JoinRequestDecision(request.getId(), approved.outcome(), approved.employeeId());
  }

  /**
   * The approval's database work; every step inside the one transaction (DD12). {@code lockTaken}
   * is set as soon as the lock is this call's.
   */
  private Approved approveInTransaction(String approverId, String shopId, String id,
      JoinApproveMode mode, String employeeId, AtomicBoolean lockTaken) {
    Instant now = Instant.now();
    WorkerJoinRequest request = lock(shopId, id, now);
    lockTaken.set(true);
    afterStep.accept(APPROVE_STEPS.get(0));
    AccountView account = account(request.getUserId());
    if (account.worker()) {
      requireNotInAnotherShop(account, shopId);
      if (!workerAccounts.claimShop(shopId, request.getUserId())) {
        throw new BaseException(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP,
            ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP.getMessage());
      }
    }
    afterStep.accept(APPROVE_STEPS.get(1));
    Employee employee;
    JoinOutcome outcome;
    if (mode == JoinApproveMode.CREATE) {
      employee = employeeService.createFromJoinRequest(shopId, approverId, request);
      outcome = JoinOutcome.APPROVED_CREATED;
    } else {
      Employee match = employeeRepository.findByShopIdAndId(shopId, employeeId)
          .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", employeeId));
      employee = employeeService.linkFromJoinRequest(shopId, approverId, match,
          request.getUserId());
      outcome = JoinOutcome.APPROVED_LINKED;
    }
    afterStep.accept(APPROVE_STEPS.get(2));
    if (account.worker()) {
      workerAccounts.addWorkerMembership(shopId, request.getUserId());
    }
    afterStep.accept(APPROVE_STEPS.get(3));
    Query locked = Query.query(Criteria.where("_id").is(request.getId())
        .and("status").is(WorkerJoinStatus.APPROVING));
    Update close = new Update()
        .set("status", WorkerJoinStatus.APPROVED)
        .set("outcome", outcome)
        .set("employeeId", employee.getId())
        .set("decidedAt", now)
        .set("decidedByUserId", approverId)
        .set("expiresAt", now.plus(RETENTION))
        .set("updatedAt", now);
    if (mongoTemplate.updateFirst(locked, close, WorkerJoinRequest.class).getMatchedCount() != 1) {
      // the lock was taken from under us (the APPROVING sweep): abort, nothing is kept
      throw new BaseException(ErrorCode.HR_REQUEST_ALREADY_HANDLED,
          ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage());
    }
    afterStep.accept(APPROVE_STEPS.get(4));
    request.setStatus(WorkerJoinStatus.APPROVED);
    request.setOutcome(outcome);
    request.setEmployeeId(employee.getId());
    request.setDecidedAt(now);
    request.setDecidedByUserId(approverId);
    return new Approved(request, outcome, employee.getId(), mode);
  }

  /**
   * Rejects a Pending request (R4.7, R4.10, R4.11, R4.13): the reason is trimmed, an empty one is
   * stored as none, one over {@value #MAX_REASON_LENGTH} characters is refused. One conditional
   * update; then one audit row with the reason, one notification carrying the shop name and the
   * reason word for word, one counter increment.
   *
   * @throws ValidationException when the reason is too long
   * @throws BaseException {@code HR_REQUEST_ALREADY_HANDLED} when the request is not Pending
   */
  public JoinRequestDecision reject(String approverId, String shopId, String id, String reason) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    String cleanReason = normaliseReason(reason);
    Instant now = Instant.now();
    Query pending = Query.query(Criteria.where("_id").is(id)
        .and("shopId").is(shopId)
        .and("status").is(WorkerJoinStatus.PENDING));
    Update close = new Update()
        .set("status", WorkerJoinStatus.REJECTED)
        .set("outcome", JoinOutcome.REJECTED)
        .set("decidedAt", now)
        .set("decidedByUserId", approverId)
        .set("expiresAt", now.plus(RETENTION))
        .set("updatedAt", now);
    if (cleanReason != null) {
      close.set("rejectionReason", cleanReason);
    } else {
      close.unset("rejectionReason");
    }
    WorkerJoinRequest rejected = mongoTemplate.findAndModify(pending, close,
        FindAndModifyOptions.options().returnNew(true), WorkerJoinRequest.class);
    if (rejected == null) {
      find(shopId, id);
      throw alreadyHandled();
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("outcome", JoinOutcome.REJECTED.name());
    auditService.record(new AuditEntry(shopId, approverId, HrAuditAction.JOIN_REQUEST_REJECTED,
        ENTITY, rejected.getId(), null, null, null, null, after, cleanReason));
    notificationService.notifyUser(rejected.getUserId(), HrNotificationType.JOIN_REQUEST_REJECTED,
        WorkerNotificationTexts.joinRequestRejected(shopName(shopId), cleanReason),
        rejected.getId());
    counters.joinRequestRejected(rejected);
    return new JoinRequestDecision(rejected.getId(), JoinOutcome.REJECTED, null);
  }

  /**
   * "Approve selected" (R4.6, R14.4): each request in its own transaction, with {@code CREATE}; a
   * request with a possible match is skipped and left Pending for the approver to decide link or create; a refusal is
   * reported with its text and does not stop the rest.
   *
   * @throws ValidationException when more than {@value #MAX_BULK} ids are given
   */
  public BulkJoinResult bulkApprove(String approverId, String shopId, List<String> ids) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    List<String> unique = uniqueIds(ids);
    List<Employee> candidates = matchCandidates(shopId);
    int approved = 0;
    List<BulkJoinResult.Skipped> skipped = new ArrayList<>();
    List<BulkJoinResult.Failed> failed = new ArrayList<>();
    for (String id : unique) {
      try {
        WorkerJoinRequest request = find(shopId, id);
        if (request.getStatus() == WorkerJoinStatus.PENDING
            && possibleMatch(request, candidates).isPresent()) {
          skipped.add(new BulkJoinResult.Skipped(id, BulkJoinResult.SkipReason.POSSIBLE_MATCH));
          continue;
        }
        approve(approverId, shopId, id, JoinApproveMode.CREATE, null);
        approved++;
      } catch (BaseException e) {
        failed.add(new BulkJoinResult.Failed(id, e.getMessage()));
      }
    }
    return new BulkJoinResult(approved, skipped, failed);
  }

  /**
   * "Reject selected" (R4.6): the one reason, normalised once, for every selected request; each
   * rejection stands on its own. The result's {@code approved} counts the rejections.
   *
   * @throws ValidationException when more than {@value #MAX_BULK} ids are given or the reason is
   *     too long
   */
  public BulkJoinResult bulkReject(String approverId, String shopId, List<String> ids,
      String reason) {
    guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    List<String> unique = uniqueIds(ids);
    String cleanReason = normaliseReason(reason);
    int rejected = 0;
    List<BulkJoinResult.Failed> failed = new ArrayList<>();
    for (String id : unique) {
      try {
        reject(approverId, shopId, id, cleanReason);
        rejected++;
      } catch (BaseException e) {
        failed.add(new BulkJoinResult.Failed(id, e.getMessage()));
      }
    }
    return new BulkJoinResult(rejected, List.of(), failed);
  }

  // ---- rules shared with other worker services ------------------------------------------------

  /**
   * The possible match (R4.3, Property 7): an employee of the shop, ACTIVE or EXITED, whose e-mail
   * equals the snapshot's ignoring case, or whose phone equals the snapshot's after {@link
   * PhoneNumbers#normalise} on both sides. When several match, an ACTIVE one comes first, then an
   * e-mail match, then the lower code, so the answer is the same on every read.
   */
  static Optional<Employee> possibleMatch(WorkerJoinRequest request, List<Employee> candidates) {
    WorkerJoinRequest.Snapshot s = request != null ? request.getSnapshot() : null;
    if (s == null || candidates == null) {
      return Optional.empty();
    }
    String email = StringUtils.hasText(s.getEmail()) ? s.getEmail().trim().toLowerCase(Locale.ROOT)
        : null;
    String phone = PhoneNumbers.normalise(s.getPhone()).orElse(null);
    Employee best = null;
    int bestRank = Integer.MAX_VALUE;
    for (Employee e : candidates) {
      boolean byEmail = email != null && e.getEmail() != null
          && email.equals(e.getEmail().trim().toLowerCase(Locale.ROOT));
      boolean byPhone = phone != null && PhoneNumbers.normalise(e.getPhone())
          .map(phone::equals).orElse(false);
      if (!byEmail && !byPhone) {
        continue;
      }
      int rank = (e.getStatus() == EmployeeStatus.ACTIVE ? 0 : 2) + (byEmail ? 0 : 1);
      if (best == null || rank < bestRank
          || (rank == bestRank && compareCodes(e.getCode(), best.getCode()) < 0)) {
        best = e;
        bestRank = rank;
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * The rejection reason as stored (R4.7, Property 9): trimmed; empty or whitespace-only becomes
   * null; longer than {@value #MAX_REASON_LENGTH} characters after trimming is refused.
   */
  static String normaliseReason(String reason) {
    if (reason == null) {
      return null;
    }
    String trimmed = reason.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_REASON_LENGTH) {
      throw new ValidationException(REASON_TOO_LONG);
    }
    return trimmed;
  }

  /** The first whitespace-separated token of a display name; null for a blank name. */
  static String firstName(String name) {
    if (!StringUtils.hasText(name)) {
      return null;
    }
    return name.trim().split("\\s+")[0];
  }

  // ---- helpers --------------------------------------------------------------------------------

  private WorkerJoinRequest newRequest(AccountView account, String shopId) {
    Instant now = Instant.now();
    WorkerJoinRequest request = new WorkerJoinRequest();
    request.setShopId(shopId);
    request.setUserId(account.userId());
    request.setStatus(WorkerJoinStatus.PENDING);
    request.setSnapshot(new WorkerJoinRequest.Snapshot(
        account.name() != null ? account.name().trim() : null,
        PhoneNumbers.normalise(account.phone()).orElse(account.phone()),
        account.email() != null ? account.email().trim().toLowerCase(Locale.ROOT) : null,
        workerAccounts.hasPhoto(account.userId())));
    request.setRequestedAt(now);
    request.setUpdatedAt(now);
    return request;
  }

  /** Inserts the open request; the partial unique index turns a race into "already waiting". */
  private WorkerJoinRequest insertOpen(WorkerJoinRequest request) {
    try {
      return requestRepository.insert(request);
    } catch (DuplicateKeyException e) {
      throw new BaseException(ErrorCode.HR_WORKER_REQUEST_PENDING,
          ErrorCode.HR_WORKER_REQUEST_PENDING.getMessage());
    }
  }

  /** {@code PENDING → APPROVING} for exactly one approver (DD12). */
  private WorkerJoinRequest lock(String shopId, String id, Instant now) {
    Query pending = Query.query(Criteria.where("_id").is(id)
        .and("shopId").is(shopId)
        .and("status").is(WorkerJoinStatus.PENDING));
    Update take = new Update().set("status", WorkerJoinStatus.APPROVING).set("updatedAt", now);
    WorkerJoinRequest locked = mongoTemplate.findAndModify(pending, take,
        FindAndModifyOptions.options().returnNew(true), WorkerJoinRequest.class);
    if (locked == null) {
      find(shopId, id);
      throw alreadyHandled();
    }
    return locked;
  }

  /** {@code APPROVING → PENDING} when this approval did not finish; a no-op otherwise. */
  private void unlock(String id) {
    try {
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(id).and("status").is(WorkerJoinStatus.APPROVING)),
          new Update().set("status", WorkerJoinStatus.PENDING).set("updatedAt", Instant.now()),
          WorkerJoinRequest.class);
    } catch (RuntimeException e) {
      log.warn("[hr-join] could not unlock request {} after a failed approval: {}", id,
          e.getMessage());
    }
  }

  private WorkerJoinRequest find(String shopId, String id) {
    return requestRepository.findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("Join request", "id", id));
  }

  private AccountView account(String userId) {
    return workerAccounts.account(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
  }

  /** R3.5 for a new request: a WORKER account with a shop, or an ACTIVE employee anywhere, waits. */
  private void requireNoShop(AccountView account) {
    if (account.shopId() != null
        || !employeeRepository.findByUserIdAndStatus(account.userId(), EmployeeStatus.ACTIVE)
            .isEmpty()) {
      throw new BaseException(ErrorCode.HR_WORKER_HAS_SHOP,
          ErrorCode.HR_WORKER_HAS_SHOP.getMessage());
    }
  }

  /**
   * R5.1 at approval time: a WORKER account whose shop, or linked ACTIVE employee, is in another
   * shop cannot be approved here. The account's own shop being this one is fine.
   */
  private void requireNotInAnotherShop(AccountView account, String shopId) {
    boolean elsewhere = account.shopId() != null && !shopId.equals(account.shopId());
    if (!elsewhere) {
      for (Employee e : employeeRepository.findByUserIdAndStatus(account.userId(),
          EmployeeStatus.ACTIVE)) {
        if (!shopId.equals(e.getShopId())) {
          elsewhere = true;
          break;
        }
      }
    }
    if (elsewhere) {
      throw new BaseException(ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP,
          ErrorCode.HR_WORKER_LOGIN_IN_OTHER_SHOP.getMessage());
    }
  }

  private List<Employee> matchCandidates(String shopId) {
    return employeeRepository.findMatchCandidates(shopId, MATCH_STATUSES);
  }

  private JoinRequestRow row(JoinRequestRow row, WorkerJoinRequest r, List<Employee> candidates) {
    WorkerJoinRequest.Snapshot s = r.getSnapshot() != null ? r.getSnapshot()
        : new WorkerJoinRequest.Snapshot();
    row.setId(r.getId());
    row.setName(s.getName());
    row.setPhone(s.getPhone());
    row.setEmail(s.getEmail());
    row.setPhotoUrl(s.isHasPhoto() ? String.format(PHOTO_URL, r.getUserId()) : null);
    row.setRequestedAt(r.getRequestedAt());
    row.setStatus(r.getStatus());
    row.setHasPossibleMatch(possibleMatch(r, candidates).isPresent());
    return row;
  }

  private JoinRequestSelfResponse self(WorkerJoinRequest r) {
    return new JoinRequestSelfResponse(r.getId(), shopName(r.getShopId()), r.getStatus(),
        r.getRequestedAt());
  }

  private String shopName(String shopId) {
    String name = shopNamePort.getShopName(shopId);
    return StringUtils.hasText(name) ? name : "your shop";
  }

  private static WorkerJoinStatus parseInboxStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return WorkerJoinStatus.PENDING;
    }
    try {
      WorkerJoinStatus parsed = WorkerJoinStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
      if (INBOX_STATUSES.contains(parsed)) {
        return parsed;
      }
    } catch (IllegalArgumentException ignored) {
      // fall through
    }
    throw new ValidationException("status must be PENDING or REJECTED");
  }

  private static List<String> uniqueIds(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new ValidationException("Select at least one request");
    }
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
    unique.removeIf(Objects::isNull);
    if (unique.size() > MAX_BULK) {
      throw new ValidationException("Select at most " + MAX_BULK + " requests at a time");
    }
    if (unique.isEmpty()) {
      throw new ValidationException("Select at least one request");
    }
    return unique;
  }

  private static int compareCodes(String a, String b) {
    if (a == null) {
      return b == null ? 0 : 1;
    }
    return b == null ? -1 : a.compareTo(b);
  }

  private static void requireUser(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
  }

  private static BaseException tooManyAttempts() {
    return new BaseException(ErrorCode.HR_TOO_MANY_ATTEMPTS,
        ErrorCode.HR_TOO_MANY_ATTEMPTS_WORKER_MESSAGE);
  }

  private static BaseException alreadyHandled() {
    return new BaseException(ErrorCode.HR_REQUEST_ALREADY_HANDLED,
        ErrorCode.HR_REQUEST_ALREADY_HANDLED.getMessage());
  }

  /** What the approval transaction hands to the after-commit work; the audit row records {@code mode}. */
  private record Approved(WorkerJoinRequest request, JoinOutcome outcome, String employeeId,
      JoinApproveMode mode) {}
}
