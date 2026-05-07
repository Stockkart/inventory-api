package com.inventory.credit.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditBalanceStatus;
import com.inventory.credit.domain.model.CreditDirection;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditAccountRepository;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;
import com.inventory.credit.rest.dto.response.CreditAccountResponse;
import com.inventory.credit.rest.dto.response.CreditEntriesPageResponse;
import com.inventory.credit.rest.dto.response.CreditEntryResponse;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CreditService {

  private static final int MONEY_SCALE = 4;

  private final CreditAccountRepository creditAccountRepository;
  private final CreditEntryRepository creditEntryRepository;
  private final CustomerRepository customerRepository;
  private final VendorRepository vendorRepository;

  @Transactional
  public CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body) {
    return applyEntry(
        shopId,
        userId,
        body.getPartyType(),
        body.getPartyId(),
        body.getPartyDisplayName(),
        body.getPartyPhone(),
        body.getAmount(),
        CreditEntryType.CHARGE,
        CreditDirection.INCREASE_DUE,
        body.getNote(),
        body.getReferenceType(),
        body.getReferenceId(),
        body.getSourceKey());
  }

  @Transactional
  public CreditEntry createSettlement(
      String shopId, String userId, CreateCreditSettlementRequest body) {
    return applyEntry(
        shopId,
        userId,
        body.getPartyType(),
        body.getPartyId(),
        body.getPartyDisplayName(),
        body.getPartyPhone(),
        body.getAmount(),
        CreditEntryType.SETTLEMENT,
        CreditDirection.DECREASE_DUE,
        body.getNote(),
        body.getReferenceType(),
        body.getReferenceId(),
        body.getSourceKey());
  }

  @Transactional(readOnly = true)
  public List<CreditAccount> listAccounts(String shopId) {
    return creditAccountRepository.findAll().stream()
        .filter(a -> shopId.equals(a.getShopId()))
        .sorted((a, b) -> {
          Instant ia = a.getLastEntryAt() != null ? a.getLastEntryAt() : a.getUpdatedAt();
          Instant ib = b.getLastEntryAt() != null ? b.getLastEntryAt() : b.getUpdatedAt();
          if (ia == null && ib == null) return 0;
          if (ia == null) return 1;
          if (ib == null) return -1;
          return ib.compareTo(ia);
        })
        .toList();
  }

  /**
   * Accounts for API responses with {@link Customer}/{@link Vendor} names applied when available, and
   * legacy {@code "Customer &lt;id&gt;"} labels cleaned up.
   */
  @Transactional(readOnly = true)
  public List<CreditAccountResponse> listAccountResponses(String shopId) {
    return listAccounts(shopId).stream()
        .map(CreditMapper::toResponse)
        .map(this::enrichAccountResponse)
        .toList();
  }

  private CreditAccountResponse enrichAccountResponse(CreditAccountResponse r) {
    if (r.getPartyType() == CreditPartyType.CUSTOMER && StringUtils.hasText(r.getPartyId())) {
      String pid = r.getPartyId().trim();
      customerRepository
          .findById(pid)
          .map(Customer::getName)
          .filter(StringUtils::hasText)
          .map(String::trim)
          .ifPresentOrElse(
              r::setPartyDisplayName,
              () -> {
                if (isCustomerIdFallbackLabel(r.getPartyDisplayName(), pid)) {
                  r.setPartyDisplayName("Customer");
                }
              });
    } else if (r.getPartyType() == CreditPartyType.VENDOR && StringUtils.hasText(r.getPartyId())) {
      String pid = r.getPartyId().trim();
      vendorRepository
          .findById(pid)
          .map(Vendor::getName)
          .filter(StringUtils::hasText)
          .map(String::trim)
          .ifPresentOrElse(
              r::setPartyDisplayName,
              () -> {
                if (isVendorIdFallbackLabel(r.getPartyDisplayName(), pid)) {
                  r.setPartyDisplayName("Vendor");
                }
              });
    }
    return r;
  }

  private static boolean isCustomerIdFallbackLabel(String displayName, String partyId) {
    if (!StringUtils.hasText(partyId)) {
      return false;
    }
    if (!StringUtils.hasText(displayName)) {
      return true;
    }
    String d = displayName.trim();
    return d.equals("Customer " + partyId);
  }

  private static boolean isVendorIdFallbackLabel(String displayName, String partyId) {
    if (!StringUtils.hasText(partyId)) {
      return false;
    }
    if (!StringUtils.hasText(displayName)) {
      return true;
    }
    String d = displayName.trim();
    return d.equals("Vendor " + partyId);
  }

  @Transactional(readOnly = true)
  public CreditEntriesPageResponse listEntries(
      String shopId, String accountId, int page, int size) {
    CreditAccount acc =
        creditAccountRepository
            .findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("CreditAccount", "id", accountId));
    if (!shopId.equals(acc.getShopId())) {
      throw new ValidationException("Credit account does not belong to authenticated shop");
    }

    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), 100);
    Page<CreditEntry> p =
        creditEntryRepository.findByShopIdAndAccountIdOrderByCreatedAtDesc(
            shopId,
            accountId,
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

    List<CreditEntryResponse> rows = p.getContent().stream().map(CreditMapper::toResponse).toList();
    return new CreditEntriesPageResponse(rows, p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
  }

  private CreditEntry applyEntry(
      String shopId,
      String userId,
      CreditPartyType partyType,
      String partyId,
      String partyDisplayName,
      String partyPhone,
      BigDecimal amount,
      CreditEntryType entryType,
      CreditDirection direction,
      String note,
      String referenceType,
      String referenceId,
      String sourceKey) {
    validateParty(partyType, partyId, partyDisplayName);
    BigDecimal amt = scalePositiveAmount(amount);

    String normalizedSourceKey = normalize(sourceKey);
    if (normalizedSourceKey != null) {
      var existing = creditEntryRepository.findFirstByShopIdAndSourceKey(shopId, normalizedSourceKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    CreditAccount account =
        creditAccountRepository
            .findByShopIdAndPartyTypeAndPartyRefId(shopId, partyType, partyId.trim())
            .orElseGet(() -> newAccount(shopId, partyType, partyId.trim(), partyDisplayName, partyPhone));

    Instant now = Instant.now();
    account.setPartyDisplayName(partyDisplayName.trim());
    account.setPartyPhone(normalize(partyPhone));

    BigDecimal current = scale(account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO);
    BigDecimal delta = direction == CreditDirection.INCREASE_DUE ? amt : scale(amt.negate());
    BigDecimal nextBalance = scale(current.add(delta));

    account.setCurrentBalance(nextBalance);
    account.setStatus(statusOf(nextBalance));
    account.setUpdatedAt(now);
    account.setLastEntryAt(now);
    account = creditAccountRepository.save(account);

    CreditEntry entry = new CreditEntry();
    entry.setShopId(shopId);
    entry.setAccountId(account.getId());
    entry.setPartyType(partyType);
    entry.setPartyRefId(partyId.trim());
    entry.setEntryType(entryType);
    entry.setDirection(direction);
    entry.setAmount(amt);
    entry.setBalanceAfter(nextBalance);
    entry.setNote(limit(normalize(note), 280));
    entry.setReferenceType(limit(normalize(referenceType), 64));
    entry.setReferenceId(limit(normalize(referenceId), 128));
    entry.setSourceKey(normalizedSourceKey);
    entry.setCreatedByUserId(normalize(userId));
    entry.setCreatedAt(now);
    return creditEntryRepository.save(entry);
  }

  private static CreditAccount newAccount(
      String shopId,
      CreditPartyType partyType,
      String partyId,
      String partyDisplayName,
      String partyPhone) {
    Instant now = Instant.now();
    CreditAccount a = new CreditAccount();
    a.setShopId(shopId);
    a.setPartyType(partyType);
    a.setPartyRefId(partyId);
    a.setPartyDisplayName(partyDisplayName.trim());
    a.setPartyPhone(normalize(partyPhone));
    a.setCurrentBalance(scale(BigDecimal.ZERO));
    a.setStatus(CreditBalanceStatus.CLEAR);
    a.setCreatedAt(now);
    a.setUpdatedAt(now);
    a.setLastEntryAt(now);
    return a;
  }

  private static void validateParty(CreditPartyType partyType, String partyId, String partyDisplayName) {
    if (partyType == null) throw new ValidationException("partyType is required");
    if (!StringUtils.hasText(partyId)) throw new ValidationException("partyId is required");
    if (!StringUtils.hasText(partyDisplayName)) {
      throw new ValidationException("partyDisplayName is required");
    }
  }

  private static BigDecimal scalePositiveAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("amount must be greater than zero");
    }
    return scale(amount);
  }

  private static CreditBalanceStatus statusOf(BigDecimal balance) {
    int sign = balance.signum();
    if (sign > 0) return CreditBalanceStatus.DUE;
    if (sign < 0) return CreditBalanceStatus.ADVANCE;
    return CreditBalanceStatus.CLEAR;
  }

  private static BigDecimal scale(BigDecimal amount) {
    return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static String normalize(String raw) {
    if (!StringUtils.hasText(raw)) return null;
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  private static String limit(String v, int max) {
    if (v == null) return null;
    return v.length() > max ? v.substring(0, max) : v;
  }
}
