package com.inventory.accounting.service;

import static com.inventory.accounting.service.MoneyUtil.nz;
import static com.inventory.accounting.service.MoneyUtil.scale;
import static com.inventory.accounting.service.MoneyUtil.zero;

import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Core double-entry posting engine. Validates that {@code Σ debits == Σ credits}, resolves account
 * references, persists a {@link JournalEntry} together with one {@link LedgerEntry} per line, and
 * keeps each account's running balance up-to-date.
 *
 * <p>All public operations are transactional. Idempotency is enforced through the unique index
 * on {@code (shopId, sourceType, sourceId)} on {@code journal_entries}: a duplicate insert is
 * caught and the already-persisted entry returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingPostingService {

  private final JournalEntryRepository journalEntryRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final AccountService accountService;
  private final JournalEntrySequenceService sequenceService;

  /**
   * Optional plug-in tested with {@code @Autowired(required = false)} so callers without a clock
   * configuration just fall back to {@link Instant#now()}.
   */
  @Autowired(required = false)
  private java.time.Clock clock;

  @Transactional
  public JournalEntry post(String shopId, String userId, PostJournalRequest req) {
    validateRequest(req);
    accountService.ensureSeeded(shopId);

    JournalSource sourceType = req.getSourceType();
    String sourceId = trim(req.getSourceId());
    if (sourceType != null && sourceId != null) {
      Optional<JournalEntry> existing =
          journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(shopId, sourceType, sourceId);
      if (existing.isPresent()) {
        log.debug(
            "Returning existing journal entry {} for {}:{} on shop {}",
            existing.get().getId(),
            sourceType,
            sourceId,
            shopId);
        return existing.get();
      }
    }

    Map<String, Account> accountCache = new HashMap<>();
    List<JournalLine> lines = new ArrayList<>(req.getLines().size());
    BigDecimal totalDebit = zero();
    BigDecimal totalCredit = zero();

    int idx = 0;
    for (PostJournalLine in : req.getLines()) {
      Account a = resolveAccount(shopId, in, accountCache);
      BigDecimal debit = nz(in.getDebit());
      BigDecimal credit = nz(in.getCredit());
      if (debit.signum() < 0 || credit.signum() < 0) {
        throw new ValidationException("Journal line amounts must be non-negative");
      }
      if (debit.signum() > 0 && credit.signum() > 0) {
        throw new ValidationException(
            "Journal line cannot have both debit and credit non-zero (account "
                + a.getCode()
                + ")");
      }
      if (debit.signum() == 0 && credit.signum() == 0) {
        continue;
      }

      JournalLine line =
          JournalLine.builder()
              .lineIndex(idx++)
              .accountId(a.getId())
              .accountCode(a.getCode())
              .accountName(a.getName())
              .debit(debit)
              .credit(credit)
              .partyType(in.getPartyType())
              .partyRefId(trim(in.getPartyRefId()))
              .partyDisplayName(trim(in.getPartyDisplayName()))
              .memo(trim(in.getMemo()))
              .build();
      lines.add(line);
      totalDebit = scale(totalDebit.add(debit));
      totalCredit = scale(totalCredit.add(credit));
    }

    if (lines.size() < 2) {
      throw new ValidationException("Journal entry needs at least two effective lines");
    }
    if (totalDebit.compareTo(totalCredit) != 0) {
      throw new ValidationException(
          "Unbalanced journal entry: debit=" + totalDebit + " credit=" + totalCredit);
    }

    Instant now = now();
    LocalDate txnDate =
        req.getTxnDate() != null ? req.getTxnDate() : LocalDate.ofInstant(now, ZoneOffset.UTC);

    JournalEntry entry = new JournalEntry();
    entry.setShopId(shopId);
    entry.setEntryNo(sequenceService.nextEntryNo(shopId));
    entry.setTxnDate(txnDate);
    entry.setPostedAt(now);
    entry.setSourceType(sourceType != null ? sourceType : JournalSource.MANUAL);
    entry.setSourceId(sourceId);
    entry.setSourceKey(trim(req.getSourceKey()));
    entry.setStatus(JournalStatus.POSTED);
    entry.setNarration(trim(req.getNarration()));
    entry.setLines(lines);
    entry.setTotalDebit(totalDebit);
    entry.setTotalCredit(totalCredit);
    entry.setCreatedByUserId(trim(userId));

    try {
      entry = journalEntryRepository.save(entry);
    } catch (DuplicateKeyException dup) {
      if (sourceType != null && sourceId != null) {
        return journalEntryRepository
            .findByShopIdAndSourceTypeAndSourceId(shopId, sourceType, sourceId)
            .orElseThrow(() -> dup);
      }
      throw dup;
    }

    persistLedger(entry, accountCache);
    return entry;
  }

  @Transactional
  public JournalEntry reverse(
      String shopId, String userId, String originalEntryId, String reason) {
    JournalEntry original =
        journalEntryRepository
            .findByShopIdAndId(shopId, originalEntryId)
            .orElseThrow(
                () -> new ResourceNotFoundException("JournalEntry", "id", originalEntryId));
    if (original.getStatus() == JournalStatus.REVERSED) {
      throw new ValidationException("Journal entry already reversed");
    }

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.REVERSAL);
    req.setSourceId("REV:" + original.getId());
    req.setSourceKey(req.getSourceId());
    req.setNarration(
        "Reversal of "
            + (StringUtils.hasText(original.getEntryNo()) ? original.getEntryNo() : original.getId())
            + (StringUtils.hasText(reason) ? " — " + reason.trim() : ""));
    req.setTxnDate(original.getTxnDate() != null ? original.getTxnDate() : LocalDate.now());

    List<PostJournalLine> rev = new ArrayList<>();
    for (JournalLine l : original.getLines()) {
      PostJournalLine rl = new PostJournalLine();
      rl.setAccountId(l.getAccountId());
      rl.setAccountCode(l.getAccountCode());
      rl.setDebit(nz(l.getCredit()));
      rl.setCredit(nz(l.getDebit()));
      rl.setPartyType(l.getPartyType());
      rl.setPartyRefId(l.getPartyRefId());
      rl.setPartyDisplayName(l.getPartyDisplayName());
      rl.setMemo(l.getMemo());
      rev.add(rl);
    }
    req.setLines(rev);

    JournalEntry reversal = post(shopId, userId, req);
    reversal.setReversesEntryId(original.getId());
    journalEntryRepository.save(reversal);

    original.setStatus(JournalStatus.REVERSED);
    original.setReversedByEntryId(reversal.getId());
    journalEntryRepository.save(original);

    return reversal;
  }

  private void persistLedger(JournalEntry entry, Map<String, Account> accountCache) {
    Map<String, BigDecimal> runningByAccount = new HashMap<>();
    List<LedgerEntry> rows = new ArrayList<>(entry.getLines().size());
    for (JournalLine l : entry.getLines()) {
      Account a = accountCache.get(l.getAccountId());
      if (a == null) {
        a = accountService.findByIdOrThrow(entry.getShopId(), l.getAccountId());
        accountCache.put(a.getId(), a);
      }
      final Account account = a;
      BigDecimal prior =
          runningByAccount.computeIfAbsent(
              account.getId(),
              key -> latestBalanceFor(entry.getShopId(), key, account.getNormalBalance()));
      BigDecimal delta = signedDelta(a.getNormalBalance(), nz(l.getDebit()), nz(l.getCredit()));
      BigDecimal balanceAfter = scale(prior.add(delta));
      runningByAccount.put(a.getId(), balanceAfter);

      LedgerEntry row =
          LedgerEntry.builder()
              .shopId(entry.getShopId())
              .accountId(a.getId())
              .accountCode(a.getCode())
              .accountName(a.getName())
              .accountType(a.getType())
              .normalBalance(a.getNormalBalance())
              .journalEntryId(entry.getId())
              .journalEntryNo(entry.getEntryNo())
              .lineIndex(l.getLineIndex())
              .sourceType(entry.getSourceType())
              .sourceId(entry.getSourceId())
              .txnDate(entry.getTxnDate())
              .postedAt(entry.getPostedAt())
              .debit(nz(l.getDebit()))
              .credit(nz(l.getCredit()))
              .balanceAfter(balanceAfter)
              .partyType(l.getPartyType())
              .partyRefId(l.getPartyRefId())
              .partyDisplayName(l.getPartyDisplayName())
              .narration(StringUtils.hasText(l.getMemo()) ? l.getMemo() : entry.getNarration())
              .build();
      rows.add(row);
    }
    ledgerEntryRepository.saveAll(rows);
  }

  private BigDecimal latestBalanceFor(String shopId, String accountId, NormalBalance nb) {
    var page =
        ledgerEntryRepository.findByShopIdAndAccountIdOrderByPostedAtAsc(
            shopId, accountId, PageRequest.of(0, 1, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "postedAt")));
    if (page.isEmpty()) {
      return zero();
    }
    BigDecimal balanceAfter = page.getContent().get(0).getBalanceAfter();
    return balanceAfter != null ? scale(balanceAfter) : zero();
  }

  private static BigDecimal signedDelta(
      NormalBalance normalBalance, BigDecimal debit, BigDecimal credit) {
    BigDecimal positiveSide = normalBalance == NormalBalance.DEBIT ? debit : credit;
    BigDecimal negativeSide = normalBalance == NormalBalance.DEBIT ? credit : debit;
    return positiveSide.subtract(negativeSide);
  }

  private Account resolveAccount(
      String shopId, PostJournalLine in, Map<String, Account> cache) {
    String id = trim(in.getAccountId());
    if (id != null) {
      Account a = cache.get(id);
      if (a == null) {
        a = accountService.findByIdOrThrow(shopId, id);
        cache.put(a.getId(), a);
      }
      return a;
    }
    String code = trim(in.getAccountCode());
    if (code == null) {
      throw new ValidationException("Either accountCode or accountId is required for every line");
    }
    Account a = accountService.findByCodeOrThrow(shopId, code);
    cache.put(a.getId(), a);
    return a;
  }

  private static void validateRequest(PostJournalRequest req) {
    if (req == null) {
      throw new ValidationException("Posting request is required");
    }
    if (req.getLines() == null || req.getLines().size() < 2) {
      throw new ValidationException("Journal entry needs at least two lines");
    }
  }

  private Instant now() {
    return clock != null ? Instant.now(clock) : Instant.now();
  }

  static String trim(String v) {
    if (v == null) return null;
    String t = v.trim();
    return t.isEmpty() ? null : t;
  }

  static PartyType safeParty(PartyType p) {
    return p == null ? PartyType.SHOP : p;
  }
}
