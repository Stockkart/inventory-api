package com.inventory.accounting.migration;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.api.SystemAccountCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Read-only start-up check for the HR system accounts (R11.6): logs every shop that already has
 * an account of its own (not seeded, {@code system != true}) on 1250, 2400 or 5700. {@link
 * com.inventory.accounting.service.ChartOfAccountsSeeder} treats any account on a code as present,
 * so for such a shop HR would post salaries into the shop's own account; the fix is a manual
 * rename of that account. {@code HrAccountingPoster} refuses those posts ({@code
 * HR_ACCOUNT_CONFLICT}), so this check only tells operators ahead of time. Writes nothing; one read across all shops by {@code code} (there is
 * no index on code alone, but the check runs once per start, returns only two fields and is
 * limited to 30 s). The warning gives the shop count and at most the first 10 shop ids.
 *
 * <p>{@code stockkart.coa-conflict-check.enabled} (default true) turns it off.
 */
@Component
@Slf4j
public class SystemAccountCodeConflictCheck {

  /** The codes HR posts to that did not exist before HR; 5500 Round-off Expense is older. */
  static final List<String> HR_CODES =
      List.of(
          SystemAccountCode.STAFF_ADVANCES,
          SystemAccountCode.SALARY_PAYABLE,
          SystemAccountCode.SALARY_EXPENSE);

  /** Time limit of the one scan across all shops. */
  static final long MAX_TIME_MS = 30_000;

  /** Shops named in the warning; the rest are only counted. */
  static final int LOGGED_SHOPS = 10;

  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.coa-conflict-check.enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(12)
  public void run() {
    if (!enabled) {
      log.info("[coa-conflict-check] disabled");
      return;
    }
    try {
      Map<String, Set<String>> codesByShop = conflicts();
      if (codesByShop.isEmpty()) {
        log.info("[coa-conflict-check] no shop uses {} for an account of its own", HR_CODES);
        return;
      }
      // shop ids and account codes only: no names or balances; at most the first few shops
      log.warn(
          "[coa-conflict-check] {} shop(s) have their own account on an HR system code; rename"
              + " those accounts before running payroll; first {}: {}",
          codesByShop.size(),
          Math.min(LOGGED_SHOPS, codesByShop.size()),
          firstShops(codesByShop));
    } catch (RuntimeException e) {
      log.error("[coa-conflict-check] check failed: {}", e.getMessage(), e);
    }
  }

  /** The first {@link #LOGGED_SHOPS} entries (sorted by shop id), for a bounded log line. */
  static Map<String, Set<String>> firstShops(Map<String, Set<String>> codesByShop) {
    Map<String, Set<String>> first = new TreeMap<>();
    for (Map.Entry<String, Set<String>> e : codesByShop.entrySet()) {
      if (first.size() >= LOGGED_SHOPS) {
        break;
      }
      first.put(e.getKey(), e.getValue());
    }
    return first;
  }

  /** Shop id to the conflicting codes it holds, sorted for a stable log line. */
  Map<String, Set<String>> conflicts() {
    Query query =
        Query.query(Criteria.where("code").in(HR_CODES).and("system").ne(true));
    query.fields().include("shopId", "code");
    // bounded: a slow scan must not hold up readiness (a timeout is logged by run())
    query.maxTimeMsec(MAX_TIME_MS);
    Map<String, Set<String>> byShop = new TreeMap<>();
    for (Account a : mongoTemplate.find(query, Account.class)) {
      byShop.computeIfAbsent(String.valueOf(a.getShopId()), k -> new TreeSet<>()).add(a.getCode());
    }
    return byShop;
  }
}
