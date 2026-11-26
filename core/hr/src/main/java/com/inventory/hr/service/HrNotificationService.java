package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.HrNotificationResponse;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The small HR inbox (R6.9, design "In-app leave notifications"; worker-app R4.13, R10): USER rows
 * go to one person, APPROVERS rows are written once per new request and shown to whoever holds the
 * permission that decides that kind of request when reading ({@code APPROVE_LEAVE} for leave,
 * {@code ADD_EDIT_EMPLOYEES} for join requests). Not SSE, because {@code EventService} broadcasts
 * to every browser of a shop. Texts carry names, the shop name, dates and the rejection reason
 * only, never pay, phone numbers or e-mail addresses ({@link WorkerNotificationTexts}).
 *
 * <p>Two ways to read: {@link #listForCaller} is the shop-scoped web bell; {@link #listForAccount}
 * is the Stockkart Team app's view, per account across shops, because a worker with a pending
 * request has no shop context yet.
 *
 * <p>Writing a notification never fails the action it reports: a failed insert is logged and
 * dropped.
 */
@Slf4j
@Service
public class HrNotificationService {

  static final int PAGE_SIZE = 20;

  /** Approver rows a holder of {@code APPROVE_LEAVE} sees. */
  static final Set<HrNotificationType> LEAVE_TYPES = EnumSet.of(HrNotificationType.LEAVE_REQUESTED,
      HrNotificationType.LEAVE_APPROVED, HrNotificationType.LEAVE_REJECTED);

  /** Approver rows a holder of {@code ADD_EDIT_EMPLOYEES} sees (worker-app R10.5). */
  static final Set<HrNotificationType> JOIN_APPROVER_TYPES =
      EnumSet.of(HrNotificationType.JOIN_REQUEST_RECEIVED);

  /** Rows addressed to a worker's account (worker-app R10.2); written by {@link #notifyUser}. */
  static final Set<HrNotificationType> WORKER_TYPES = EnumSet.of(
      HrNotificationType.JOIN_REQUEST_APPROVED, HrNotificationType.JOIN_REQUEST_REJECTED,
      HrNotificationType.PROFILE_REQUESTED);

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private HrAccessService hrAccessService;

  @Autowired private HrQueryRunner queryRunner;

  /** A row for one user about a leave request (the requester on a decision). */
  public void notifyUser(String shopId, String recipientUserId, HrNotificationType type,
      String leaveRequestId, String text) {
    if (recipientUserId == null) {
      return;
    }
    insert(row(shopId, NotificationAudience.USER, recipientUserId, type, leaveRequestId, null,
        text));
  }

  /**
   * A row for a worker's account (worker-app R4.13, R8.7, R10.2): one of {@link #WORKER_TYPES}.
   * Addressed to the account, not to a shop, because the worker reads it through {@link
   * #listForAccount} whether or not they belong to a shop yet; {@code shopId} is therefore null
   * and the row does not appear in the shop-scoped {@link #listForCaller}.
   *
   * @param joinRequestId the {@code hr_join_requests} row this is about; null for {@code
   *     PROFILE_REQUESTED}
   */
  public void notifyUser(String recipientUserId, HrNotificationType type, String text,
      String joinRequestId) {
    if (!WORKER_TYPES.contains(type)) {
      throw new IllegalArgumentException(type + " is not a worker-facing notification type");
    }
    if (recipientUserId == null) {
      return;
    }
    insert(row(null, NotificationAudience.USER, recipientUserId, type, null, joinRequestId, text));
  }

  /**
   * One row for every approver of the shop (a new request).
   *
   * @param refId the request the row is about: stored as {@code joinRequestId} for {@code
   *     JOIN_REQUEST_RECEIVED} and as {@code leaveRequestId} for every other type
   */
  public void notifyApprovers(String shopId, HrNotificationType type, String text, String refId) {
    boolean join = JOIN_APPROVER_TYPES.contains(type);
    insert(row(shopId, NotificationAudience.APPROVERS, null, type, join ? null : refId,
        join ? refId : null, text));
  }

  /**
   * The caller's rows newer than {@code since}, at most 20: own USER rows, plus APPROVERS rows of
   * the kinds the caller may decide now (leave rows with APPROVE_LEAVE, join-request rows with
   * ADD_EDIT_EMPLOYEES). The permissions are resolved once up front, so a poll is one permission
   * resolve plus one query whatever the window holds: approvers read both branches, everyone else
   * only their own rows (approver rows never take places in their page).
   *
   * <p>Without {@code since} the newest 20 are returned; with it the oldest 20 newer than the
   * cursor, so a burst of more than 20 rows is delivered over the next polls instead of skipped.
   */
  public HrNotificationsResponse listForCaller(String userId, String shopId, Instant since) {
    HrPermissionGuard.requireContext(userId, shopId);
    Set<HrNotificationType> approverTypes =
        approverTypesFor(hrAccessService.permissions(userId, shopId));
    return queryRunner.read("hr_notifications", () -> {
      Criteria mine = Criteria.where("shopId").is(shopId).and("recipientUserId").is(userId);
      Criteria approvers = Criteria.where("shopId").is(shopId).and("audience")
          .is(NotificationAudience.APPROVERS).and("type").in(approverTypes);
      if (since != null) {
        mine = mine.and("createdAt").gt(since);
        approvers = approvers.and("createdAt").gt(since);
      }
      // non-approvers: their own branch only, so 20 approver rows can never hide their own
      // decisions and no second read is needed
      Query query = Query.query(
              approverTypes.isEmpty() ? mine : new Criteria().orOperator(mine, approvers))
          .with(Sort.by(since == null ? Sort.Direction.DESC : Sort.Direction.ASC, "createdAt"))
          .limit(PAGE_SIZE);
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<HrNotification> found = mongoTemplate.find(query, HrNotification.class);

      Instant cursor = since;
      List<HrNotificationResponse> items = new ArrayList<>();
      for (HrNotification n : found) {
        cursor = later(cursor, n.getCreatedAt());
        items.add(toResponse(n, userId));
      }
      return new HrNotificationsResponse(items, cursor);
    });
  }

  /** The approver-row types the caller may see, by permission (worker-app R10.5). */
  static Set<HrNotificationType> approverTypesFor(Set<HrPermission> permissions) {
    Set<HrNotificationType> types = EnumSet.noneOf(HrNotificationType.class);
    if (permissions.contains(HrPermission.APPROVE_LEAVE)) {
      types.addAll(LEAVE_TYPES);
    }
    if (permissions.contains(HrPermission.ADD_EDIT_EMPLOYEES)) {
      types.addAll(JOIN_APPROVER_TYPES);
    }
    return types;
  }

  /**
   * The account's own USER rows across every shop, unread first, then newest first, at most
   * {@code limit} (clamped to 1..20), with the total still unread (worker-app R10.1). Needs no shop
   * context: a worker with a pending request has none. Two index walks on {@code recipient_created}
   * (recipientUserId, createdAt desc): the unread rows, then, only when the page has room left, the
   * read ones.
   */
  public HrAccountNotificationsResponse listForAccount(String userId, int limit) {
    requireUser(userId);
    int size = Math.max(1, Math.min(limit, PAGE_SIZE));
    return queryRunner.read("hr_notifications_account", () -> {
      Query unreadCount = Query.query(ownRows(userId, false));
      unreadCount.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      long unread = mongoTemplate.count(unreadCount, HrNotification.class);
      List<HrNotification> found = new ArrayList<>(
          mongoTemplate.find(page(ownRows(userId, false), size), HrNotification.class));
      if (found.size() < size) {
        found.addAll(
            mongoTemplate.find(page(ownRows(userId, true), size - found.size()),
                HrNotification.class));
      }
      List<HrNotificationResponse> items = new ArrayList<>();
      for (HrNotification n : found) {
        items.add(toResponse(n, userId));
      }
      return new HrAccountNotificationsResponse(items, unread);
    });
  }

  /** Marks one row read for the caller; 404 when it is not one of the caller's rows. */
  public void markRead(String userId, String shopId, String id) {
    HrPermissionGuard.requireContext(userId, shopId);
    Query query = Query.query(new Criteria().andOperator(
        Criteria.where("_id").is(id).and("shopId").is(shopId),
        visibleTo(userId)));
    long matched = mongoTemplate.updateFirst(query,
        new Update().addToSet("readByUserIds", userId), HrNotification.class).getMatchedCount();
    if (matched == 0) {
      throw new ResourceNotFoundException("Notification", "id", id);
    }
  }

  /**
   * Marks one of the account's own USER rows read, without shop context (worker-app R10.1); 404 for
   * any other row, including approver rows and other people's rows.
   */
  public void markRead(String userId, String id) {
    requireUser(userId);
    Query query = Query.query(Criteria.where("_id").is(id)
        .and("audience").is(NotificationAudience.USER)
        .and("recipientUserId").is(userId));
    long matched = mongoTemplate.updateFirst(query,
        new Update().addToSet("readByUserIds", userId), HrNotification.class).getMatchedCount();
    if (matched == 0) {
      throw new ResourceNotFoundException("Notification", "id", id);
    }
  }

  /** Marks every row of the caller read; returns how many changed. */
  public long markAllRead(String userId, String shopId) {
    HrPermissionGuard.requireContext(userId, shopId);
    Query query = Query.query(new Criteria().andOperator(
        Criteria.where("shopId").is(shopId).and("readByUserIds").ne(userId),
        visibleTo(userId)));
    return mongoTemplate.updateMulti(query, new Update().addToSet("readByUserIds", userId),
        HrNotification.class).getModifiedCount();
  }

  /**
   * Rows a caller may touch: their own USER rows and approver rows. Marking an approver row read
   * without the permission that shows it is harmless (it is never shown to them), and avoids a
   * permission read.
   */
  private static Criteria visibleTo(String userId) {
    return new Criteria().orOperator(
        Criteria.where("audience").is(NotificationAudience.USER).and("recipientUserId")
            .is(userId),
        Criteria.where("audience").is(NotificationAudience.APPROVERS));
  }

  /** The account's own rows, read or unread (`readByUserIds` absent or null counts as unread). */
  private static Criteria ownRows(String userId, boolean read) {
    Criteria own = Criteria.where("recipientUserId").is(userId)
        .and("audience").is(NotificationAudience.USER);
    return read ? own.and("readByUserIds").is(userId) : own.and("readByUserIds").ne(userId);
  }

  private static Query page(Criteria criteria, int size) {
    Query query = Query.query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
        .limit(size);
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return query;
  }

  private static void requireUser(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
  }

  private static HrNotificationResponse toResponse(HrNotification n, String userId) {
    return new HrNotificationResponse(n.getId(), n.getType(), n.getLeaveRequestId(),
        n.getJoinRequestId(), n.getText(), n.getCreatedAt(),
        n.getReadByUserIds() != null && n.getReadByUserIds().contains(userId));
  }

  private static Instant later(Instant a, Instant b) {
    if (b == null) {
      return a;
    }
    return a == null || b.isAfter(a) ? b : a;
  }

  private static HrNotification row(String shopId, NotificationAudience audience,
      String recipientUserId, HrNotificationType type, String leaveRequestId,
      String joinRequestId, String text) {
    HrNotification n = new HrNotification();
    n.setShopId(shopId);
    n.setAudience(audience);
    n.setRecipientUserId(recipientUserId);
    n.setType(type);
    n.setLeaveRequestId(leaveRequestId);
    n.setJoinRequestId(joinRequestId);
    n.setText(text);
    n.setReadByUserIds(new ArrayList<>());
    n.setCreatedAt(Instant.now());
    return n;
  }

  private void insert(HrNotification n) {
    try {
      mongoTemplate.insert(n);
    } catch (RuntimeException e) {
      // text deliberately not logged (it names the employee)
      log.error("[hr-notification] could not write {} for request {}: {}", n.getType(),
          n.getJoinRequestId() != null ? n.getJoinRequestId() : n.getLeaveRequestId(),
          e.getMessage());
    }
  }
}
