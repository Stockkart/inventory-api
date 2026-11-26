package com.inventory.hr.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.request.ApproveJoinRequestRequest;
import com.inventory.hr.rest.dto.request.BulkJoinRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Shape checks for the manager's Join requests inbox ({@code /api/v1/hr/join-requests/**},
 * worker-app R4.1–R4.7, R11.3; design: "Validation (400) is for shapes only"). Ids are 24 hex
 * characters, a page is at most {@value #MAX_PAGE_SIZE} rows, a bulk action names at most {@value
 * #MAX_BULK} requests, {@code status} is Pending or Rejected and {@code mode} is {@code CREATE} or
 * {@code LINK} with an employee id when linking. Business refusals (already handled, linked
 * elsewhere, exited, other shop) are the service's, with their own codes.
 */
@Component
public class JoinRequestInboxValidator {

  public static final Pattern OBJECT_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
  /** The largest page the inbox serves (R11.3, design "Manager inbox"). */
  public static final int MAX_PAGE_SIZE = 200;
  /** The most requests one "Approve selected" / "Reject selected" may name (R4.6). */
  public static final int MAX_BULK = 50;
  /** A rejection reason after trimming (R4.7); the same bound the service enforces. */
  public static final int MAX_REASON_LENGTH = 200;

  static final String STATUS_MESSAGE = "status must be PENDING or REJECTED";
  static final String MODE_MESSAGE = "mode must be CREATE or LINK";
  static final String EMPLOYEE_REQUIRED_MESSAGE = "employeeId is required to link";
  static final String REASON_TOO_LONG = "The reason must be at most " + MAX_REASON_LENGTH
      + " characters";
  static final String IDS_EMPTY = "Select at least one request";
  static final String IDS_TOO_MANY = "Select at most " + MAX_BULK + " requests at a time";

  /** A Mongo ObjectId as the web sends it (request id, employee id). */
  public void requireObjectId(String value, String field) {
    if (!StringUtils.hasText(value) || !OBJECT_ID.matcher(value.trim()).matches()) {
      throw new ValidationException(field + " must be a 24-character id");
    }
  }

  /**
   * The tab filter (R4.1): absent or blank means Pending; otherwise {@code PENDING} or {@code
   * REJECTED} in any case.
   */
  public WorkerJoinStatus requireInboxStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return WorkerJoinStatus.PENDING;
    }
    String wanted = status.trim().toUpperCase(Locale.ROOT);
    if (WorkerJoinStatus.PENDING.name().equals(wanted)) {
      return WorkerJoinStatus.PENDING;
    }
    if (WorkerJoinStatus.REJECTED.name().equals(wanted)) {
      return WorkerJoinStatus.REJECTED;
    }
    throw new ValidationException(STATUS_MESSAGE);
  }

  /** Zero-based page and a size of 1 to {@value #MAX_PAGE_SIZE}; null means the default. */
  public void requirePage(Integer page, Integer size) {
    if (page != null && page < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
      throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
  }

  /**
   * The approve body (R4.4): {@code mode} parsed case-insensitively, {@code employeeId} a 24-hex
   * id when linking.
   *
   * @return the parsed mode
   */
  public JoinApproveMode requireApprove(ApproveJoinRequestRequest body) {
    if (body == null) {
      throw new ValidationException(MODE_MESSAGE);
    }
    JoinApproveMode mode = requireMode(body.getMode());
    if (mode == JoinApproveMode.LINK) {
      if (!StringUtils.hasText(body.getEmployeeId())) {
        throw new ValidationException(EMPLOYEE_REQUIRED_MESSAGE);
      }
      requireObjectId(body.getEmployeeId(), "employeeId");
    }
    return mode;
  }

  /** {@code CREATE} or {@code LINK}, any case, trimmed. */
  public JoinApproveMode requireMode(String mode) {
    if (!StringUtils.hasText(mode)) {
      throw new ValidationException(MODE_MESSAGE);
    }
    try {
      return JoinApproveMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException(MODE_MESSAGE);
    }
  }

  /**
   * The optional rejection reason (R4.7): null or whitespace-only is fine (stored as none by the
   * service); more than {@value #MAX_REASON_LENGTH} characters after trimming is refused here with
   * the same text the service uses.
   */
  public void requireReason(String reason) {
    if (reason != null && reason.trim().length() > MAX_REASON_LENGTH) {
      throw new ValidationException(REASON_TOO_LONG);
    }
  }

  /**
   * The bulk body (R4.6): one to {@value #MAX_BULK} ids after dropping nulls, blanks and repeats,
   * each a 24-hex id.
   *
   * @return the distinct, trimmed ids in the order given
   */
  public List<String> requireBulkIds(BulkJoinRequest body) {
    if (body == null || body.getIds() == null || body.getIds().isEmpty()) {
      throw new ValidationException(IDS_EMPTY);
    }
    List<String> unique = new ArrayList<>();
    for (String id : body.getIds()) {
      if (!StringUtils.hasText(id)) {
        continue;
      }
      String trimmed = id.trim();
      if (!unique.contains(trimmed)) {
        unique.add(trimmed);
      }
    }
    if (unique.isEmpty()) {
      throw new ValidationException(IDS_EMPTY);
    }
    if (unique.size() > MAX_BULK) {
      throw new ValidationException(IDS_TOO_MANY);
    }
    for (String id : unique) {
      requireObjectId(id, "ids");
    }
    return unique;
  }
}
