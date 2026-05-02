package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.JournalPostingSource;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.model.SubledgerEntry;
import com.inventory.accounting.domain.model.SubledgerEntryKind;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.accounting.domain.repository.SubledgerEntryRepository;
import com.inventory.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PostingService {

  private final GlBootstrapService glBootstrapService;
  private final GlAccountRepository glAccountRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final SubledgerEntryRepository subledgerEntryRepository;

  /** Scale for persisted money amounts on journals and subledger. */
  static final int MONEY_SCALE = 4;

  @Transactional
  public JournalEntry postManualJournal(
      String shopId,
      Instant journalDate,
      String description,
      List<PostingLineDraft> drafts,
      String postedByUserId,
      String sourceKey) {
    return postJournal(
        shopId,
        journalDate,
        description,
        drafts,
        postedByUserId,
        sourceKey,
        JournalPostingSource.MANUAL);
  }

  /**
   * Post a balanced journal. Idempotent when {@code sourceKey} is non-null and already posted for
   * this shop.
   */
  @Transactional
  public JournalEntry postJournal(
      String shopId,
      Instant journalDate,
      String description,
      List<PostingLineDraft> drafts,
      String postedByUserId,
      String sourceKey,
      JournalPostingSource postingSource) {

    validateShop(shopId);
    glBootstrapService.ensureDefaultsForShop(shopId);

    if (!StringUtils.hasText(description)) {
      throw new ValidationException("Journal description is required");
    }
    if (drafts == null || drafts.size() < 2) {
      throw new ValidationException("Provide at least two journal lines");
    }

    final String trimmedKey = normalizeSourceKey(sourceKey);
    if (trimmedKey != null) {
      Optional<JournalEntry> existing =
          journalEntryRepository.findByShopIdAndSourceKey(shopId, trimmedKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    BigDecimal debitTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal creditTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    List<JournalLine> lines = new ArrayList<>();

    int lineNo = 1;
    for (PostingLineDraft d : drafts) {
      if (!StringUtils.hasText(d.accountCode())) {
        throw new ValidationException("Each line requires accountCode");
      }
      BigDecimal debit = scaleMoney(d.debit());
      BigDecimal credit = scaleMoney(d.credit());
      if (sign(debit) == 0 && sign(credit) == 0) {
        throw new ValidationException(
            "Line " + lineNo + ": debit and credit cannot both be empty");
      }
      if (sign(debit) > 0 && sign(credit) > 0) {
        throw new ValidationException(
            "Line "
                + lineNo
                + ": post either a debit OR a credit for that line — not both");
      }
      GlAccount acc =
          glAccountRepository
              .findFirstByShopIdAndCodeOrderByIdAsc(shopId, d.accountCode().trim())
              .orElseThrow(
                  () ->
                      new ValidationException(
                          "Unknown or inactive account code: " + d.accountCode()));

      if (!acc.isActive()) {
        throw new ValidationException("Account is inactive: " + d.accountCode());
      }

      PartyType partyType = d.partyType();
      String partyId = StringUtils.hasText(d.partyId()) ? d.partyId().trim() : null;
      if ((partyType != null) != (partyId != null)) {
        throw new ValidationException(
            "Line " + lineNo + ": set both partyType and partyId together, or omit both");
      }

      JournalLine jl = new JournalLine();
      jl.setLineNo(lineNo);
      jl.setAccountId(acc.getId());
      jl.setAccountCode(acc.getCode());
      jl.setDebit(debit);
      jl.setCredit(credit);
      jl.setMemo(d.memo());
      jl.setPartyType(partyType);
      jl.setPartyId(partyId);
      lines.add(jl);

      debitTotal = debitTotal.add(debit);
      creditTotal = creditTotal.add(credit);
      lineNo++;
    }

    if (debitTotal.compareTo(creditTotal) != 0) {
      throw new ValidationException(
          "Journal must balance: debit total "
              + debitTotal
              + " does not match credit total "
              + creditTotal);
    }

    Instant now = Instant.now();
    Instant entryDate = journalDate != null ? journalDate : now;

    JournalEntry entry = new JournalEntry();
    entry.setShopId(shopId);
    entry.setJournalDate(entryDate);
    entry.setPostedAt(now);
    entry.setDescription(description.trim());
    entry.setSource(
        postingSource != null ? postingSource : JournalPostingSource.MANUAL);
    entry.setSourceKey(trimmedKey);
    entry.setTotalDebitSum(debitTotal);
    entry.setTotalCreditSum(creditTotal);
    entry.setPostedByUserId(postedByUserId);
    entry.setLines(lines);

    entry = journalEntryRepository.save(entry);

    persistSubledgerForJournal(entry);

    return entry;
  }

  private static void validateShop(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
  }

  private static String normalizeSourceKey(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  private void persistSubledgerForJournal(JournalEntry entry) {
    for (JournalLine line : entry.getLines()) {
      deriveSubledgerLine(entry, line)
          .ifPresent(subledgerEntryRepository::save);
    }
  }

  private Optional<SubledgerEntry> deriveSubledgerLine(JournalEntry entry, JournalLine line) {
    if (line.getPartyType() == null || !StringUtils.hasText(line.getPartyId())) {
      return Optional.empty();
    }
    BigDecimal d = line.getDebit();
    BigDecimal c = line.getCredit();
    SubledgerEntryKind kind;
    BigDecimal amt;
    if (sign(d) > 0) {
      kind = SubledgerEntryKind.DEBIT;
      amt = d;
    } else {
      kind = SubledgerEntryKind.CREDIT;
      amt = c;
    }

    SubledgerEntry sl = new SubledgerEntry();
    sl.setShopId(entry.getShopId());
    sl.setJournalEntryId(entry.getId());
    sl.setJournalLineNo(line.getLineNo());
    sl.setPartyType(line.getPartyType());
    sl.setPartyId(line.getPartyId());
    sl.setKind(kind);
    sl.setAmount(amt);
    sl.setMemo(line.getMemo());
    sl.setJournalDate(entry.getJournalDate());
    sl.setPostedAt(entry.getPostedAt());
    sl.setPostedByUserId(entry.getPostedByUserId());
    sl.setJournalSourceKey(entry.getSourceKey());
    return Optional.of(sl);
  }

  private static int sign(BigDecimal val) {
    if (val == null) return 0;
    return val.signum();
  }

  private static BigDecimal scaleMoney(BigDecimal raw) {
    if (raw == null) return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** Internal draft validated by {@link #postManualJournal}. */
  public record PostingLineDraft(
      String accountCode,
      BigDecimal debit,
      BigDecimal credit,
      String memo,
      PartyType partyType,
      String partyId) {}
}
