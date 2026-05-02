package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountingReadService {

  private final GlAccountRepository glAccountRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final GlBootstrapService glBootstrapService;

  /**
   * Seeds default chart rows and merges duplicate nominal accounts when present — not strictly
   * read-only.
   */
  @Transactional
  public List<GlAccount> listAccounts(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    glBootstrapService.ensureSeedAccountsOnly(shopId);
    return glAccountRepository.findByShopIdOrderByCodeAsc(shopId);
  }

  @Transactional(readOnly = true)
  public Page<JournalEntry> pageJournals(String shopId, int page, int size) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    PageRequest pr =
        PageRequest.of(
            Math.max(0, page),
            Math.min(100, Math.max(1, size)),
            Sort.by(Sort.Direction.DESC, "postedAt"));
    return journalEntryRepository.findByShopIdOrderByPostedAtDesc(shopId, pr);
  }

  /** How many journals exist under this tenant (trial balance scopes the same shop). */
  @Transactional(readOnly = true)
  public long countJournals(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    return journalEntryRepository.countByShopId(shopId.trim());
  }

  /** Chart-of-accounts rows (bootstrap), not journals. */
  @Transactional(readOnly = true)
  public long countChartAccounts(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }
    return glAccountRepository.countByShopId(shopId.trim());
  }

  @Transactional(readOnly = true)
  public JournalEntry getJournal(String shopId, String journalId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(journalId)) {
      throw new ValidationException("shopId and journalId are required");
    }
    JournalEntry j =
        journalEntryRepository.findById(journalId).orElseThrow(() -> journalNotFound(journalId));
    if (!shopId.equals(j.getShopId())) {
      throw new ResourceNotFoundException("JournalEntry", "id", journalId);
    }
    return j;
  }

  private static ResourceNotFoundException journalNotFound(String journalId) {
    return new ResourceNotFoundException("JournalEntry", "id", journalId);
  }
}
