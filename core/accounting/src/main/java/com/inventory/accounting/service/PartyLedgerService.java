package com.inventory.accounting.service;

import static com.inventory.accounting.service.MoneyUtil.nz;
import static com.inventory.accounting.service.MoneyUtil.scale;

import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subsidiary-ledger reader on top of the {@code ledger_entries} collection. Tally / Zoho / SAP all
 * model vendors and customers as a single control account (Sundry Creditors / Sundry Debtors) in
 * the general ledger, with per-party balances tracked via a subsidiary index. We do the same — the
 * {@code partyType + partyRefId} fields on each {@link LedgerEntry} act as the subsidiary key, and
 * this service exposes the aggregated and detailed views.
 *
 * <p>Balance orientation is normalized to "money owed in the natural direction":
 * <ul>
 *   <li>{@code VENDOR}: positive = we owe the vendor (credit-side).</li>
 *   <li>{@code CUSTOMER}: positive = customer owes us (debit-side).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PartyLedgerService {

  private static final String LEDGER_ENTRIES = "ledger_entries";

  private final MongoTemplate mongoTemplate;
  private final LedgerEntryRepository ledgerEntryRepository;

  @Transactional(readOnly = true)
  public PartySummary listParties(
      String shopId, PartyType partyType, LocalDate from, LocalDate to) {
    if (partyType == null) {
      throw new IllegalArgumentException("partyType is required");
    }
    Criteria match =
        Criteria.where("shopId")
            .is(shopId)
            .and("partyType")
            .is(partyType.name())
            .and("partyRefId")
            .ne(null);
    if (from != null) match = match.and("txnDate").gte(from);
    if (to != null) {
      // txnDate is stored as LocalDate (BSON Date at start-of-day UTC by the Mongo driver)
      match = match.and("txnDate").lte(to);
    }

    Aggregation agg =
        Aggregation.newAggregation(
            Aggregation.match(match),
            Aggregation.group("partyRefId")
                .last("partyDisplayName")
                .as("partyDisplayName")
                .sum("debit")
                .as("debitTurnover")
                .sum("credit")
                .as("creditTurnover")
                .max("txnDate")
                .as("lastTxnDate")
                .count()
                .as("txnCount"),
            Aggregation.sort(Sort.Direction.ASC, "partyDisplayName"));

    AggregationResults<Document> results =
        mongoTemplate.aggregate(agg, LEDGER_ENTRIES, Document.class);

    List<PartyRow> rows = new ArrayList<>();
    BigDecimal totalDebit = MoneyUtil.zero();
    BigDecimal totalCredit = MoneyUtil.zero();
    for (Document d : results) {
      String partyRefId = stringOf(d.get("_id"));
      if (partyRefId == null || partyRefId.isBlank()) continue;
      BigDecimal debit = toBigDecimal(d.get("debitTurnover"));
      BigDecimal credit = toBigDecimal(d.get("creditTurnover"));
      BigDecimal balance = orientBalance(partyType, debit, credit);
      LocalDate lastTxnDate = toLocalDate(d.get("lastTxnDate"));
      Integer txnCount = d.getInteger("txnCount", 0);
      rows.add(
          new PartyRow(
              partyType,
              partyRefId,
              d.getString("partyDisplayName"),
              scale(debit),
              scale(credit),
              balance,
              lastTxnDate,
              txnCount));
      totalDebit = totalDebit.add(debit);
      totalCredit = totalCredit.add(credit);
    }
    rows.sort(
        Comparator.comparing(
            (PartyRow r) ->
                r.partyDisplayName() != null ? r.partyDisplayName().toLowerCase() : "",
            Comparator.nullsLast(Comparator.naturalOrder())));

    BigDecimal totalBalance = orientBalance(partyType, totalDebit, totalCredit);
    return new PartySummary(
        partyType,
        from,
        to,
        rows,
        scale(totalDebit),
        scale(totalCredit),
        totalBalance,
        LocalDate.now());
  }

  @Transactional(readOnly = true)
  public Statement statement(
      String shopId,
      PartyType partyType,
      String partyRefId,
      LocalDate from,
      LocalDate to,
      int page,
      int size) {
    if (partyType == null || partyRefId == null || partyRefId.isBlank()) {
      throw new IllegalArgumentException("partyType and partyRefId are required");
    }
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 200);
    PageRequest pr =
        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "postedAt"));

    Page<LedgerEntry> p;
    if (from != null && to != null) {
      p =
          ledgerEntryRepository
              .findByShopIdAndPartyTypeAndPartyRefIdAndTxnDateBetweenOrderByPostedAtAsc(
                  shopId, partyType, partyRefId, from, to, pr);
    } else {
      p =
          ledgerEntryRepository.findByShopIdAndPartyTypeAndPartyRefIdOrderByPostedAtAsc(
              shopId, partyType, partyRefId, pr);
    }

    BigDecimal opening = openingBalance(shopId, partyType, partyRefId, from);
    String displayName =
        p.getContent().stream()
            .map(LedgerEntry::getPartyDisplayName)
            .filter(s -> s != null && !s.isBlank())
            .findFirst()
            .orElse(null);

    BigDecimal running = opening;
    List<EnrichedRow> enriched = new ArrayList<>(p.getNumberOfElements());
    for (LedgerEntry e : p.getContent()) {
      BigDecimal delta = partyDelta(partyType, nz(e.getDebit()), nz(e.getCredit()));
      running = scale(running.add(delta));
      enriched.add(new EnrichedRow(e, running));
    }
    return new Statement(
        partyType,
        partyRefId,
        displayName,
        opening,
        running,
        enriched,
        p.getNumber(),
        p.getSize(),
        p.getTotalElements(),
        p.getTotalPages());
  }

  private BigDecimal openingBalance(
      String shopId, PartyType partyType, String partyRefId, LocalDate from) {
    if (from == null) return MoneyUtil.zero();
    Criteria match =
        Criteria.where("shopId")
            .is(shopId)
            .and("partyType")
            .is(partyType.name())
            .and("partyRefId")
            .is(partyRefId)
            .and("txnDate")
            .lt(from);
    Aggregation agg =
        Aggregation.newAggregation(
            Aggregation.match(match),
            Aggregation.group()
                .sum("debit")
                .as("debit")
                .sum("credit")
                .as("credit"));
    AggregationResults<Document> r = mongoTemplate.aggregate(agg, LEDGER_ENTRIES, Document.class);
    Document doc = r.getUniqueMappedResult();
    if (doc == null) return MoneyUtil.zero();
    return orientBalance(
        partyType, toBigDecimal(doc.get("debit")), toBigDecimal(doc.get("credit")));
  }

  private static BigDecimal partyDelta(
      PartyType partyType, BigDecimal debit, BigDecimal credit) {
    return scale(orientBalance(partyType, debit, credit));
  }

  private static BigDecimal orientBalance(
      PartyType partyType, BigDecimal debit, BigDecimal credit) {
    BigDecimal d = debit != null ? debit : MoneyUtil.zero();
    BigDecimal c = credit != null ? credit : MoneyUtil.zero();
    return partyType == PartyType.CUSTOMER ? scale(d.subtract(c)) : scale(c.subtract(d));
  }

  private static String stringOf(Object v) {
    return v == null ? null : v.toString();
  }

  private static BigDecimal toBigDecimal(Object v) {
    if (v == null) return MoneyUtil.zero();
    if (v instanceof BigDecimal bd) return bd;
    if (v instanceof Decimal128 d) return d.bigDecimalValue();
    if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    try {
      return new BigDecimal(v.toString());
    } catch (NumberFormatException nfe) {
      return MoneyUtil.zero();
    }
  }

  private static LocalDate toLocalDate(Object v) {
    if (v == null) return null;
    if (v instanceof LocalDate ld) return ld;
    if (v instanceof Date d) return d.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
    if (v instanceof Instant i) return i.atOffset(ZoneOffset.UTC).toLocalDate();
    if (v instanceof Long l) return Instant.ofEpochMilli(l).atOffset(ZoneOffset.UTC).toLocalDate();
    try {
      return LocalDate.parse(v.toString());
    } catch (Exception ignore) {
      return null;
    }
  }

  /** One row per party in the subsidiary listing. */
  public record PartyRow(
      PartyType partyType,
      String partyRefId,
      String partyDisplayName,
      BigDecimal debitTurnover,
      BigDecimal creditTurnover,
      BigDecimal balance,
      LocalDate lastTxnDate,
      int txnCount) {}

  /** Aggregated subsidiary listing for a partyType. */
  public record PartySummary(
      PartyType partyType,
      LocalDate from,
      LocalDate to,
      List<PartyRow> parties,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      BigDecimal totalBalance,
      LocalDate asOf) {}

  /** Entry + running per-party balance after this entry. */
  public record EnrichedRow(LedgerEntry entry, BigDecimal balanceAfter) {}

  /** A vendor / customer statement: detailed ledger view bounded to one party. */
  public record Statement(
      PartyType partyType,
      String partyRefId,
      String partyDisplayName,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      List<EnrichedRow> entries,
      int page,
      int size,
      long totalItems,
      int totalPages) {

    public static Statement empty(PartyType partyType, String partyRefId) {
      return new Statement(
          partyType,
          partyRefId,
          null,
          MoneyUtil.zero(),
          MoneyUtil.zero(),
          List.of(),
          0,
          0,
          0,
          0);
    }

    /** Convenience constructor that wraps an empty {@link PageImpl}. */
    public static Statement of(PartyType partyType, String partyRefId, BigDecimal opening) {
      return new Statement(
          partyType, partyRefId, null, opening, opening, List.of(), 0, 0, 0, 0);
    }
  }
}
