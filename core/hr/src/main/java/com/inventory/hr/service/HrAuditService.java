package com.inventory.hr.service;

import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.repository.HrAuditEventRepository;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Appends {@code hr_audit_events} rows (R14.7). Append-only: there is no update or delete. Before
 * a row is stored, payment details (bank account, IFSC, UPI, payment method) and salary amounts are
 * removed from {@code before}/{@code after}; when they differed, {@code after} carries {@code
 * "paymentDetailsChanged": true} or {@code "salaryChanged": true} instead. An audit write failure
 * is logged (without payload) and counted, and never fails the user's action.
 */
@Slf4j
@Service
public class HrAuditService {

  static final String PAYMENT_DETAILS_CHANGED = "paymentDetailsChanged";
  static final String SALARY_CHANGED = "salaryChanged";

  /** Keys that never reach an audit row, whatever map level they are on. */
  static final Set<String> PAYMENT_KEYS =
      Set.of("bankAccountNumber", "ifsc", "upiId", "paymentMethod");

  static final Set<String> SALARY_KEYS =
      Set.of("salaryRevisions", "salaryAmount", "amount", "payType", "rateAmount");

  @Autowired private HrAuditEventRepository auditEventRepository;

  @Autowired private MetricsWrapper metrics;

  /** One audit row; {@code employeeId}, {@code date}, {@code period}, maps and reason may be null. */
  public void record(AuditEntry entry) {
    try {
      auditEventRepository.insert(toEvent(entry, Instant.now()));
    } catch (RuntimeException e) {
      // payload deliberately not logged
      log.error("[hr-audit] could not write {} for {} {}: {}", entry.action(), entry.entityType(),
          entry.entityId(), e.getMessage());
      metrics.increment(HrMetricsConstants.AUDIT_FAILURES_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "action", String.valueOf(entry.action()));
    }
  }

  /**
   * Several rows in one {@code insertMany} (bulk attendance saves). Same stripping and failure
   * rule as {@link #record}: a failure is logged and counted once per row, never thrown.
   */
  public void recordAll(List<AuditEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    try {
      auditEventRepository.insert(entries.stream().map(e -> toEvent(e, now)).toList());
    } catch (RuntimeException e) {
      // payload deliberately not logged
      log.error("[hr-audit] could not write {} rows ({}): {}", entries.size(),
          entries.get(0).action(), e.getMessage());
      for (AuditEntry entry : entries) {
        metrics.increment(HrMetricsConstants.AUDIT_FAILURES_TOTAL, 1,
            "module", HrMetricsConstants.MODULE, "action", String.valueOf(entry.action()));
      }
    }
  }

  /** The row as it is stored, PII and amounts already stripped. Package-private for tests. */
  static HrAuditEvent toEvent(AuditEntry entry, Instant at) {
    Map<String, Object> before = strip(entry.before());
    Map<String, Object> after = strip(entry.after());
    if (after != null || before != null) {
      Map<String, Object> flags = after != null ? after : new LinkedHashMap<>();
      if (differs(entry.before(), entry.after(), PAYMENT_KEYS)) {
        flags.put(PAYMENT_DETAILS_CHANGED, true);
      }
      if (differs(entry.before(), entry.after(), SALARY_KEYS)) {
        flags.put(SALARY_CHANGED, true);
      }
      if (after == null && !flags.isEmpty()) {
        after = flags;
      }
    }
    HrAuditEvent event = new HrAuditEvent();
    event.setShopId(entry.shopId());
    event.setActorUserId(entry.actorUserId());
    event.setAt(at);
    event.setAction(entry.action());
    event.setEntityType(entry.entityType());
    event.setEntityId(entry.entityId());
    event.setEmployeeId(entry.employeeId());
    event.setDate(entry.date());
    event.setPeriod(entry.period());
    event.setBefore(before);
    event.setAfter(after);
    event.setReason(entry.reason());
    return event;
  }

  /** Deep copy without payment and salary keys; nested maps are stripped too. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> strip(Map<String, Object> source) {
    if (source == null) {
      return null;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : source.entrySet()) {
      if (PAYMENT_KEYS.contains(e.getKey()) || SALARY_KEYS.contains(e.getKey())) {
        continue;
      }
      Object value = e.getValue();
      copy.put(e.getKey(), value instanceof Map<?, ?> m ? strip((Map<String, Object>) m) : value);
    }
    return copy;
  }

  private static boolean differs(Map<String, Object> before, Map<String, Object> after,
      Set<String> keys) {
    for (String key : keys) {
      Object b = before != null ? before.get(key) : null;
      Object a = after != null ? after.get(key) : null;
      if (!Objects.equals(b, a)) {
        return true;
      }
    }
    return false;
  }

  /** What to audit. Build with the canonical constructor; unused fields are null. */
  public record AuditEntry(
      String shopId,
      String actorUserId,
      HrAuditAction action,
      String entityType,
      String entityId,
      String employeeId,
      String date,
      String period,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason) {

    /** Settings-style entry: no employee, date or period. */
    public static AuditEntry of(String shopId, String actorUserId, HrAuditAction action,
        String entityType, String entityId, Map<String, Object> before,
        Map<String, Object> after) {
      return new AuditEntry(shopId, actorUserId, action, entityType, entityId, null, null, null,
          before, after, null);
    }
  }
}
