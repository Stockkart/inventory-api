package com.inventory.hr.migration;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrNotification;
import com.inventory.hr.domain.model.HrRolePermissions;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Idempotent: ensures every index of the {@code hr_*} collections (design "Data model"), including
 * collections whose services come in later tasks, so their first query is already indexed.
 * Automatic index creation is off and HR models carry no {@code @Indexed}, so nothing else creates
 * them. {@code hr_counters} needs none ({@code _id} only). Each index is ensured on its own, so one
 * failure (e.g. duplicates in old data) is logged and does not stop the rest.
 */
@Component
@Slf4j
public class HrIndexMigration {

  /** 90 days, after which Mongo itself removes HR inbox rows (no app job). */
  static final long NOTIFICATION_TTL_SECONDS = Duration.ofDays(90).toSeconds();

  @Autowired private MongoTemplate mongoTemplate;

  @EventListener(ApplicationReadyEvent.class)
  @Order(11)
  public void run() {
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
    return s;
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
