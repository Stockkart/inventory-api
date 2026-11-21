package com.inventory.hr.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.NotificationAudience;
import com.inventory.hr.rest.dto.response.HrNotificationResponse;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The small HR inbox (R6.9, design "In-app leave notifications"): USER rows go to one person (the
 * requester, when a request is decided), APPROVERS rows are written once per new request and
 * shown to whoever holds APPROVE_LEAVE when reading. Not SSE, because {@code EventService}
 * broadcasts to every browser of a shop. Texts carry names and dates only, never pay.
 *
 * <p>Writing a notification never fails the leave action: a failed insert is logged and dropped.
 */
@Slf4j
@Service
public class HrNotificationService {

  static final int PAGE_SIZE = 20;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private HrAccessService hrAccessService;

  @Autowired private HrQueryRunner queryRunner;

  /** A row for one user (the requester on a decision). */
  public void notifyUser(String shopId, String recipientUserId, HrNotificationType type,
      String leaveRequestId, String text) {
    if (recipientUserId == null) {
      return;
    }
    insert(row(shopId, NotificationAudience.USER, recipientUserId, type, leaveRequestId, text));
  }

  /** One row for every approver of the shop (a new request). */
  public void notifyApprovers(String shopId, HrNotificationType type, String leaveRequestId,
      String text) {
    insert(row(shopId, NotificationAudience.APPROVERS, null, type, leaveRequestId, text));
  }

  /**
   * The caller's rows newer than {@code since}, at most 20: own USER rows, plus APPROVERS rows
   * only when the caller has APPROVE_LEAVE now. The permission is resolved once up front, so a
   * poll is one permission resolve plus one query whatever the window holds: approvers read both
   * branches, everyone else only their own rows (approver rows never take places in their page).
   *
   * <p>Without {@code since} the newest 20 are returned; with it the oldest 20 newer than the
   * cursor, so a burst of more than 20 rows is delivered over the next polls instead of skipped.
   */
  public HrNotificationsResponse listForCaller(String userId, String shopId, Instant since) {
    HrPermissionGuard.requireContext(userId, shopId);
    boolean approver =
        hrAccessService.permissions(userId, shopId).contains(HrPermission.APPROVE_LEAVE);
    return queryRunner.read("hr_notifications", () -> {
      Criteria mine = Criteria.where("shopId").is(shopId).and("recipientUserId").is(userId);
      Criteria approvers = Criteria.where("shopId").is(shopId).and("audience")
          .is(NotificationAudience.APPROVERS);
      if (since != null) {
        mine = mine.and("createdAt").gt(since);
        approvers = approvers.and("createdAt").gt(since);
      }
      // non-approvers: their own branch only, so 20 approver rows can never hide their own
      // decisions and no second read is needed
      Query query = Query.query(approver ? new Criteria().orOperator(mine, approvers) : mine)
          .with(Sort.by(since == null ? Sort.Direction.DESC : Sort.Direction.ASC, "createdAt"))
          .limit(PAGE_SIZE);
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<HrNotification> found = mongoTemplate.find(query, HrNotification.class);

      Instant cursor = since;
      List<HrNotificationResponse> items = new ArrayList<>();
      for (HrNotification n : found) {
        cursor = later(cursor, n.getCreatedAt());
        items.add(new HrNotificationResponse(n.getId(), n.getType(), n.getLeaveRequestId(),
            n.getText(), n.getCreatedAt(),
            n.getReadByUserIds() != null && n.getReadByUserIds().contains(userId)));
      }
      return new HrNotificationsResponse(items, cursor);
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
   * without APPROVE_LEAVE is harmless (it is never shown to them), and avoids a permission read.
   */
  private static Criteria visibleTo(String userId) {
    return new Criteria().orOperator(
        Criteria.where("audience").is(NotificationAudience.USER).and("recipientUserId")
            .is(userId),
        Criteria.where("audience").is(NotificationAudience.APPROVERS));
  }

  private static Instant later(Instant a, Instant b) {
    if (b == null) {
      return a;
    }
    return a == null || b.isAfter(a) ? b : a;
  }

  private static HrNotification row(String shopId, NotificationAudience audience,
      String recipientUserId, HrNotificationType type, String leaveRequestId, String text) {
    HrNotification n = new HrNotification();
    n.setShopId(shopId);
    n.setAudience(audience);
    n.setRecipientUserId(recipientUserId);
    n.setType(type);
    n.setLeaveRequestId(leaveRequestId);
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
      log.error("[hr-notification] could not write {} for leave request {}: {}", n.getType(),
          n.getLeaveRequestId(), e.getMessage());
    }
  }
}
