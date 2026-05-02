package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.common.exception.ValidationException;
import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrialBalanceQueryService {

  private final JournalEntryRepository journalEntryRepository;
  private final GlAccountRepository glAccountRepository;
  private final MongoTemplate mongoTemplate;

  /** Debit/credit sums per chart row id ({@link GlAccount#getId()}) across all journals. */
  public record DebitCreditTotals(BigDecimal debit, BigDecimal credit) {
    public static DebitCreditTotals zero() {
      return new DebitCreditTotals(
          BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP));
    }
  }

  /**
   * Debit/credit activity keyed by GL account document id — same aggregation as trial balance rows
   * (excluding the synthetic total line).
   */
  @Transactional(readOnly = true)
  public Map<String, DebitCreditTotals> totalsByAccountId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    Map<String, Accum> raw = accumulateByAccountId(shopId.trim());
    Map<String, DebitCreditTotals> out = HashMap.newHashMap(raw.size());
    for (Map.Entry<String, Accum> e : raw.entrySet()) {
      Accum v = e.getValue();
      out.put(
          e.getKey(),
          new DebitCreditTotals(
              v.debit.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP),
              v.credit.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP)));
    }
    return out;
  }

  /** Aggregates amounts from every posted journal in the shop. */
  @Transactional(readOnly = true)
  public List<TrialBalanceLine> computeTrialBalance(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }

    Map<String, GlAccount> accountById = new HashMap<>();
    for (GlAccount a : glAccountRepository.findByShopIdOrderByCodeAsc(shopId)) {
      accountById.put(a.getId(), a);
    }

    Map<String, Accum> byAccountId = accumulateByAccountId(shopId);

    List<TrialBalanceLine> rows = new ArrayList<>();
    for (Map.Entry<String, Accum> e : byAccountId.entrySet()) {
      GlAccount acc = accountById.get(e.getKey());
      Accum v = e.getValue();
      String code = acc != null ? acc.getCode() : e.getKey();
      String name = acc != null ? acc.getName() : "(unknown)";
      rows.add(
          new TrialBalanceLine(
              code,
              name,
              v.debit.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP),
              v.credit.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP)));
    }
    rows.sort(DISPLAY_TRIAL_BALANCE_ORDER);

    BigDecimal debitSum =
        rows.stream()
            .map(TrialBalanceLine::debit)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal creditSum =
        rows.stream()
            .map(TrialBalanceLine::credit)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);

    rows.add(
        new TrialBalanceLine("__TOTAL__", "TOTAL", debitSum, creditSum));
    return rows;
  }

  /**
   * Liquidity ({@code CASH} = receipts) → AR/sales/output GST → purchases/ITC/payables → equity → other.
   */
  private static final Comparator<TrialBalanceLine> DISPLAY_TRIAL_BALANCE_ORDER =
      Comparator.<TrialBalanceLine>comparingInt(TrialBalanceQueryService::displayGroupOrdinal)
          .thenComparingInt(TrialBalanceQueryService::displayWithinGroupOrdinal)
          .thenComparing(TrialBalanceLine::accountCode, String.CASE_INSENSITIVE_ORDER);

  private static int displayGroupOrdinal(TrialBalanceLine line) {
    return trialBalanceSortKey(line.accountCode()).group;
  }

  private static int displayWithinGroupOrdinal(TrialBalanceLine line) {
    return trialBalanceSortKey(line.accountCode()).within;
  }

  private record TrialBalanceSortKey(int group, int within) {}

  private static TrialBalanceSortKey trialBalanceSortKey(String rawCode) {
    if (!StringUtils.hasText(rawCode)) {
      return new TrialBalanceSortKey(99, 0);
    }
    String c = rawCode.trim().toUpperCase(Locale.ROOT);
    if (c.equals(DefaultAccountCodes.CASH)) {
      return new TrialBalanceSortKey(10, 0);
    }
    if (c.equals(DefaultAccountCodes.ACCOUNTS_RECEIVABLE)) {
      return new TrialBalanceSortKey(20, 1);
    }
    if (c.equals(DefaultAccountCodes.SALES_REVENUE)) {
      return new TrialBalanceSortKey(20, 2);
    }
    if (c.equals(DefaultAccountCodes.GST_OUTPUT_COMBINED)) {
      return new TrialBalanceSortKey(20, 3);
    }
    if (c.equals(DefaultAccountCodes.PURCHASES_EXPENSE)) {
      return new TrialBalanceSortKey(30, 0);
    }
    if (c.equals(DefaultAccountCodes.GST_INPUT_COMBINED)) {
      return new TrialBalanceSortKey(30, 1);
    }
    if (c.startsWith(VendorPayableNominalService.VENDOR_PAYABLE_CODE_PREFIX)) {
      return new TrialBalanceSortKey(30, 2);
    }
    if (c.equals(DefaultAccountCodes.ACCOUNTS_PAYABLE)) {
      return new TrialBalanceSortKey(30, 3);
    }
    if (c.equals(DefaultAccountCodes.OWNER_EQUITY)) {
      return new TrialBalanceSortKey(40, 0);
    }
    return new TrialBalanceSortKey(99, 0);
  }

  /**
   * Uses a single server-side aggregation ({@code $unwind} + {@code $group}) instead of paging every
   * full {@link JournalEntry} document into the app — critical for dashboard latency.
   */
  private Map<String, Accum> accumulateByAccountId(String shopId) {
    String sid = Objects.requireNonNull(shopId, "shopId").trim();
    Decimal128 zero =
        new Decimal128(BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP));
    List<Document> pipeline =
        Arrays.asList(
            new Document("$match", new Document("shopId", sid)),
            new Document("$unwind", "$lines"),
            new Document("$match", new Document("lines.accountId", new Document("$type", "string"))),
            new Document("$match", new Document("lines.accountId", new Document("$ne", ""))),
            new Document(
                "$group",
                new Document("_id", "$lines.accountId")
                    .append(
                        "d",
                        new Document("$sum", new Document("$ifNull", Arrays.asList("$lines.debit", zero))))
                    .append(
                        "c",
                        new Document("$sum", new Document("$ifNull", Arrays.asList("$lines.credit", zero))))));

    Map<String, Accum> byAccountId = new HashMap<>();
    try {
      MongoCollection<Document> coll =
          mongoTemplate.getDb().getCollection(mongoTemplate.getCollectionName(JournalEntry.class));
      for (Document row : coll.aggregate(pipeline).allowDiskUse(true)) {
        Object idRaw = row == null ? null : row.get("_id");
        if (idRaw == null) {
          continue;
        }
        String accountId = idRaw instanceof String s ? s : Objects.toString(idRaw, null);
        if (!StringUtils.hasText(accountId)) {
          continue;
        }
        Accum acc = new Accum();
        acc.debit = mongoDecimal(row, "d");
        acc.credit = mongoDecimal(row, "c");
        byAccountId.put(accountId, acc);
      }
    } catch (Exception e) {
      log.warn("Mongo aggregation trial-balance failed for shop={}, falling back to in-app scan", sid, e);
      return accumulateByAccountIdPagingFallback(sid);
    }
    return byAccountId;
  }

  private static BigDecimal mongoDecimal(Document doc, String key) {
    if (doc == null || !doc.containsKey(key)) {
      return BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    }
    Object raw = doc.get(key);
    if (raw == null) {
      return BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    }
    try {
      if (raw instanceof Decimal128 d) {
        return new BigDecimal(d.toString()).setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
      }
      if (raw instanceof Number num) {
        return BigDecimal.valueOf(num.doubleValue()).setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
      }
      String s = raw.toString();
      if (!StringUtils.hasText(s)) {
        return BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
      }
      return new BigDecimal(s).setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    } catch (Exception ex) {
      return BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    }
  }

  /** Legacy path — full collection scan paging (slow but safe without aggregation). */
  private Map<String, Accum> accumulateByAccountIdPagingFallback(String shopId) {
    Map<String, Accum> byAccountId = new HashMap<>();
    int pageIdx = 0;
    Page<JournalEntry> batch;
    do {
      PageRequest pageable =
          PageRequest.of(pageIdx, 200, Sort.by(Sort.Direction.ASC, "postedAt"));
      batch = journalEntryRepository.findByShopIdOrderByPostedAtAsc(shopId, pageable);
      for (JournalEntry je : batch.getContent()) {
        if (je.getLines() == null) {
          continue;
        }
        for (JournalLine line : je.getLines()) {
          if (line.getAccountId() == null) {
            continue;
          }
          Accum acc =
              byAccountId.computeIfAbsent(line.getAccountId(), k -> new Accum());
          if (line.getDebit() != null && line.getDebit().signum() != 0) {
            acc.debit = acc.debit.add(line.getDebit());
          }
          if (line.getCredit() != null && line.getCredit().signum() != 0) {
            acc.credit = acc.credit.add(line.getCredit());
          }
        }
      }
      pageIdx++;
    } while (!batch.isLast());
    return byAccountId;
  }

  private static class Accum {
    BigDecimal debit = BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal credit = BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public record TrialBalanceLine(String accountCode, String accountName, BigDecimal debit, BigDecimal credit) {}
}
