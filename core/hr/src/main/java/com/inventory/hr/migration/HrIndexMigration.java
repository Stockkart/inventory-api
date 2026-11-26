package com.inventory.hr.migration;

import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Idempotent: ensures every index of the {@code hr_*} collections (HR-module design "Data model"
 * and worker-app design "Data Models"), including collections whose services come in later tasks,
 * so their first query is already indexed. Automatic index creation is off and HR models carry no
 * {@code @Indexed}, so nothing else creates them. {@code hr_counters} needs none ({@code _id}
 * only). Each index is ensured on its own, so one failure (e.g. duplicates in old data) is logged
 * and does not stop the rest.
 *
 * <p>It also drops what an earlier build of the Stockkart Team app created and D11 removed ({@link
 * #DROPPED}): the {@code hr_pairing_proofs} indexes and the {@code hr_join_requests} link-request
 * index. Each is dropped only when present, so a dev database that had them converges and a fresh
 * one is untouched; the collection itself is left alone (dropping data is not this class's job).
 */
@Component
@Slf4j
public class HrIndexMigration {

  /** 90 days, after which Mongo itself removes HR inbox rows (no app job). */
  static final long NOTIFICATION_TTL_SECONDS = Duration.ofDays(90).toSeconds();

  /** 15 minutes: longer than the ±10 min envelope timestamp window, so a replay is always caught. */
  static final long NONCE_TTL_SECONDS = Duration.ofMinutes(15).toSeconds();

  /** The collection the removed pairing proofs lived in (worker-app D11, DD6 removed). */
  static final String PAIRING_PROOFS_COLLECTION = "hr_pairing_proofs";

  /** The {@code hr_join_requests} collection, where the removed link-request index lived. */
  static final String JOIN_REQUESTS_COLLECTION = "hr_join_requests";

  /**
   * Indexes of the removed legacy pairing flows, by collection: dropped when present (worker-app
   * D11). {@code hr_pairing_proofs}: {@code token_hash_unique}, {@code expires_ttl}; {@code
   * hr_join_requests}: the partial {@code (pairingId)} index {@code pairing_link_pending}.
   */
  static final Map<String, List<String>> DROPPED = Map.of(
      PAIRING_PROOFS_COLLECTION, List.of("token_hash_unique", "expires_ttl"),
      JOIN_REQUESTS_COLLECTION, List.of("pairing_link_pending"));

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(11)
  public void run() {
    dropRemoved();
    int ensured = 0;
    List<IndexSpec> specs = specs();
    for (IndexSpec spec : specs) {
      try {
        mongoTemplate.indexOps(spec.type()).ensureIndex(spec.index());
        ensured++;
      } catch (RuntimeException e) {
        log.error("[hr-index] failed to ensure {} on {}: {}", spec.name(), spec.type().getSimpleName(),
            e.getMessage(), e);
      }
    }
    log.info("[hr-index] ensured {} of {} indexes", ensured, specs.size());
  }

  /**
   * Drops every index in {@link #DROPPED} that exists. Idempotent: a missing collection or index is
   * nothing to do, and a failure is logged and does not stop the rest, like {@link #run}.
   */
  void dropRemoved() {
    for (Map.Entry<String, List<String>> e : DROPPED.entrySet()) {
      String collection = e.getKey();
      try {
        if (!mongoTemplate.collectionExists(collection)) {
          continue;
        }
        List<String> present = mongoTemplate.indexOps(collection).getIndexInfo().stream()
            .map(IndexInfo::getName)
            .toList();
        for (String name : e.getValue()) {
          if (present.contains(name)) {
            mongoTemplate.indexOps(collection).dropIndex(name);
            log.info("[hr-index] dropped removed index {} on {}", name, collection);
          }
        }
      } catch (RuntimeException ex) {
        log.error("[hr-index] failed to drop removed indexes on {}: {}", collection,
            ex.getMessage(), ex);
      }
    }
  }

  /** Every HR index, in design-table order. Package-private for the migration test. */
  static List<IndexSpec> specs() {
    List<IndexSpec> s = new ArrayList<>();
    s.add(spec(HrSettings.class, "shop_unique", asc("shopId").unique()));
    s.add(spec(HrRolePermissions.class, "shop_unique", asc("shopId").unique()));
    s.add(spec(Holiday.class, "shop_date_unique", asc("shopId", "date").unique()));
    s.add(spec(Employee.class, "shop_code_unique", asc("shopId", "code").unique()));
    // Partial: only linked rows (userId is a string) take part, so any number of employees
    // without a login can share userId = null while a login links to at most one employee (R4.6).
    s.add(spec(Employee.class, "shop_user_unique",
        asc("shopId", "userId").unique()
            // BSON type 2 = string, i.e. partialFilterExpression {userId: {$type: "string"}}
            .partial(PartialIndexFilter.of(Criteria.where("userId").type(2)))));
    s.add(spec(Employee.class, "shop_status_name", asc("shopId", "status", "name")));
    s.add(spec(AttendanceDay.class, "shop_employee_date_unique",
        asc("shopId", "employeeId", "date").unique()));
    s.add(spec(AttendanceDay.class, "shop_date", asc("shopId", "date")));
    s.add(spec(LeaveType.class, "shop_name_unique", asc("shopId", "name").unique()));
    s.add(spec(LeaveRequest.class, "shop_status_from", asc("shopId", "status", "fromDate")));
    s.add(spec(LeaveRequest.class, "shop_employee_year", asc("shopId", "employeeId", "leaveYear")));
    s.add(spec(Advance.class, "shop_employee_date", asc("shopId", "employeeId", "date")));
    // the advance list without an employee filter sorts by date, createdAt (newest first): with
    // createdAt in the index too the sort is a backward index walk, not an in-memory sort
    s.add(spec(Advance.class, "shop_date", asc("shopId", "date", "createdAt")));
    s.add(spec(PayrollRun.class, "shop_period_unique", asc("shopId", "period").unique()));
    s.add(spec(PayrollLine.class, "run_employee_unique", asc("runId", "employeeId").unique()));
    s.add(spec(PayrollLine.class, "shop_employee_period", asc("shopId", "employeeId", "period")));
    s.add(spec(HrNotification.class, "shop_recipient_created",
        asc("shopId", "recipientUserId", "createdAt")));
    s.add(spec(HrNotification.class, "shop_audience_created",
        asc("shopId", "audience", "createdAt")));
    s.add(spec(HrNotification.class, "created_ttl",
        asc("createdAt").expire(NOTIFICATION_TTL_SECONDS)));
    s.add(spec(HrAuditEvent.class, "shop_entity_employee_date",
        asc("shopId", "entityType", "employeeId", "date")));
    s.add(spec(HrAuditEvent.class, "shop_at", asc("shopId", "at")));
    // self check-in: idempotent by dedupeKey; one employee-day read; register flags by $in
    s.add(spec(Punch.class, "shop_dedupe_unique", asc("shopId", "dedupeKey").unique()));
    s.add(spec(Punch.class, "shop_employee_date", asc("shopId", "employeeId", "localDate")));
    // Attendance app pairings (D3, R2.11, R3.10, R10.12). The pairing id is _id, so the per-request
    // lookup needs no extra index. Partial on status = ACTIVE: any number of UNPAIRED rows may share
    // an employee or installation; only one phone is active for each at a time.
    s.add(spec(DevicePairing.class, "shop_employee_active_unique",
        asc("shopId", "employeeId").unique().partial(activeOnly())));
    s.add(spec(DevicePairing.class, "shop_employee_status", asc("shopId", "employeeId", "status")));
    s.add(spec(DevicePairing.class, "installation_active",
        asc("installationId").partial(activeOnly())));
    s.add(spec(DevicePairing.class, "scheduled_unpair_active",
        asc("scheduledUnpairAt").partial(activeOnly())));
    s.add(spec(DevicePairing.class, "purge_ttl", asc("purgeAt").expire(0)));
    // Pairing codes: stored hashed; the TTL removes expired codes without an app job (R9.11)
    s.add(spec(PairingCode.class, "code_hash_unique", asc("codeHash").unique()));
    s.add(spec(PairingCode.class, "shop_employee_status", asc("shopId", "employeeId", "status")));
    s.add(spec(PairingCode.class, "expires_ttl", asc("expiresAt").expire(0)));
    // Request-envelope nonces: replay check, forgotten after the envelope's ±10 min window
    s.add(spec(AppNonce.class, "pairing_nonce_unique", asc("pairingId", "nonce").unique()));
    s.add(spec(AppNonce.class, "seen_ttl", asc("seenAt").expire(NONCE_TTL_SECONDS)));
    // Signature-failure alerts: dashboard reads the open ones per shop
    s.add(spec(AppHealthAlert.class, "shop_cleared", asc("shopId", "clearedAt")));
    // Worker join requests (worker-app R3.5, R4.12, R11.3, R14.5). Inbox list and tab count
    // read (shopId, status) newest first; requestedAt desc makes the sort an index walk.
    s.add(spec(WorkerJoinRequest.class, "shop_status_requested",
        asc("shopId", "status").on("requestedAt", Sort.Direction.DESC)));
    // One open request per account, enforced by the database: only PENDING / APPROVING rows take
    // part, so an account may hold any number of decided rows. A racing second insert gets a
    // duplicate-key error, which the service turns into HR_WORKER_REQUEST_PENDING. ($in inside a
    // partial filter needs MongoDB 6.0+; the compose file runs 7.)
    s.add(spec(WorkerJoinRequest.class, "user_open_unique",
        asc("userId").unique().partial(openRequestsOnly())));
    // the Join screen's "last outcome" card: the account's most recent decision in 90 days
    s.add(spec(WorkerJoinRequest.class, "user_decided",
        asc("userId").on("decidedAt", Sort.Direction.DESC)));
    // 90-day retention after the decision; expiresAt is null while PENDING, so open requests stay
    s.add(spec(WorkerJoinRequest.class, "expires_ttl", asc("expiresAt").expire(0)));
    // WorkerExitJob (DD14): linked employees whose exit has not yet ended their membership. The
    // partial filter keeps the index to those rows; the job's query must repeat both predicates
    // exactly ({userId: {$type: "string"}, membershipEndedAt: null}) — $exists or $ne: null on
    // userId is not recognised as a subset and falls back to a collection scan.
    s.add(spec(Employee.class, "exit_membership_pending",
        asc("status", "exitDate").partial(PartialIndexFilter.of(
            Criteria.where("userId").type(2).and("membershipEndedAt").is(null)))));
    // Possible match for a join request (R4.3): a shop's employees by e-mail or phone. Both fields
    // are optional. A sparse compound index would not skip anything (shopId is always set), so
    // each is partial on the optional field existing; equality, $in and an anchored regex on the
    // field all walk it.
    s.add(spec(Employee.class, "shop_email",
        asc("shopId", "email").partial(PartialIndexFilter.of(Criteria.where("email").exists(true)))));
    s.add(spec(Employee.class, "shop_phone",
        asc("shopId", "phone").partial(PartialIndexFilter.of(Criteria.where("phone").exists(true)))));
    // Worker notifications without a shop context (R10.1): a worker with a pending request has no
    // shop yet, so the inbox is read per account across shops, newest first
    s.add(spec(HrNotification.class, "recipient_created",
        asc("recipientUserId").on("createdAt", Sort.Direction.DESC)));
    return s;
  }

  /** {@code partialFilterExpression {status: "ACTIVE"}}: enums are stored as constant names. */
  private static PartialIndexFilter activeOnly() {
    return PartialIndexFilter.of(Criteria.where("status").is(PairingStatus.ACTIVE.name()));
  }

  /** {@code partialFilterExpression {status: {$in: ["PENDING", "APPROVING"]}}} (R3.5, DD12). */
  private static PartialIndexFilter openRequestsOnly() {
    return PartialIndexFilter.of(Criteria.where("status")
        .in(WorkerJoinStatus.PENDING.name(), WorkerJoinStatus.APPROVING.name()));
  }

  private static Index asc(String... keys) {
    Index index = new Index();
    for (String key : keys) {
      index.on(key, Sort.Direction.ASC);
    }
    return index;
  }

  private static IndexSpec spec(Class<?> type, String name, Index index) {
    return new IndexSpec(type, name, index.named(name));
  }

  record IndexSpec(Class<?> type, String name, Index index) {}
}
