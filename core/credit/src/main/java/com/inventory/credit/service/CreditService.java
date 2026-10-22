package com.inventory.credit.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.common.util.TxnIdGenerator;
import com.inventory.credit.domain.model.CreditAccount;
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
import com.inventory.credit.utils.constants.CreditMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
  private final MetricsWrapper metrics;
  private final CreditBalances creditBalances;

  @Transactional
  public CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body) {
    CreditEntry entry = applyEntry(
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
        body.getSourceKey(),
        null,
        null,
        null);
    recordEntry("charge");
    return entry;
  }

  /**
   * Reduces party due after a return with a credit leg. Does not post cash settlement accounting
   * (the return JE already Cr Debtors / Dr Creditors). Idempotent on {@code sourceKey}.
   */
  @Transactional
  public CreditEntry recordReturnDueReduction(
      String shopId,
      String userId,
      CreditPartyType partyType,
      String partyId,
      String partyDisplayName,
      BigDecimal amount,
      String referenceType,
      String referenceId,
      String sourceKey,
      String note,
      LocalDate txnDate) {
    return applyEntry(
        shopId,
        userId,
        partyType,
        partyId,
        partyDisplayName,
        null,
        amount,
        CreditEntryType.RETURN,
        CreditDirection.DECREASE_DUE,
        note,
        referenceType,
        referenceId,
        sourceKey,
        "CREDIT",
        null,
        txnDate);
  }

  @Transactional
  public CreditEntry createSettlement(
      String shopId, String userId, CreateCreditSettlementRequest body) {
    validateSettlementPaymentMethod(body.getPaymentMethod());
    CreditEntry entry = applyEntry(
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
        body.getSourceKey(),
        normalize(body.getPaymentMethod()),
        normalize(body.getBankRef()),
        body.getTxnDate());
    recordEntry("settlement");
    return entry;
  }

  @Transactional(readOnly = true)
  public List<CreditAccount> listAccounts(String shopId) {
    return creditAccountRepository.findByShopId(shopId).stream()
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

  /** Read API for MIS / reporting — accounts for one party type. */
  @Transactional(readOnly = true)
  public List<CreditAccount> listAccountsByPartyType(String shopId, CreditPartyType partyType) {
    return creditAccountRepository.findByShopIdAndPartyType(shopId, partyType);
  }

  /** All ledger entries for a party type (caller filters by date). */
  @Transactional(readOnly = true)
  public List<CreditEntry> listEntriesByPartyType(String shopId, CreditPartyType partyType) {
    return creditEntryRepository.findByShopIdAndPartyType(shopId, partyType);
  }

  /** All ledger entries for one party. */
  @Transactional(readOnly = true)
  public List<CreditEntry> listEntriesByParty(
      String shopId, CreditPartyType partyType, String partyRefId) {
    return creditEntryRepository.findByShopIdAndPartyTypeAndPartyRefId(
        shopId, partyType, partyRefId);
  }

  /**
   * Accounts for API responses with {@link Customer}/{@link Vendor} names applied when available, and
   * legacy {@code "Customer &lt;id&gt;"} labels cleaned up.
   */
  @Transactional(readOnly = true)
  public List<CreditAccountResponse> listAccountResponses(String shopId) {
    List<CreditAccountResponse> rows =
        listAccounts(shopId).stream().map(CreditMapper::toResponse).toList();
    // one read per party type for the whole list (was one read per account)
    Map<String, String> customerNames =
        namesById(rows, CreditPartyType.CUSTOMER, ids -> customerRepository.findAllById(ids),
            Customer::getId, Customer::getName);
    Map<String, String> vendorNames =
        namesById(rows, CreditPartyType.VENDOR, ids -> vendorRepository.findAllById(ids),
            Vendor::getId, Vendor::getName);
    rows.forEach(r -> enrichAccountResponse(r, customerNames, vendorNames));
    return rows;
  }

  /** Trimmed, non-blank names of the given type's parties, keyed by id; parties not found are absent. */
  private static <T> Map<String, String> namesById(
      List<CreditAccountResponse> rows,
      CreditPartyType type,
      Function<Set<String>, Iterable<T>> load,
      Function<T, String> id,
      Function<T, String> name) {
    Set<String> ids = new HashSet<>();
    for (CreditAccountResponse r : rows) {
      if (r.getPartyType() == type && StringUtils.hasText(r.getPartyId())) {
        ids.add(r.getPartyId().trim());
      }
    }
    Map<String, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    for (T party : load.apply(ids)) {
      String n = name.apply(party);
      if (id.apply(party) != null && StringUtils.hasText(n)) {
        out.put(id.apply(party), n.trim());
      }
    }
    return out;
  }

  private void enrichAccountResponse(
      CreditAccountResponse r, Map<String, String> customerNames, Map<String, String> vendorNames) {
    if (r.getPartyType() == CreditPartyType.CUSTOMER && StringUtils.hasText(r.getPartyId())) {
      String pid = r.getPartyId().trim();
      String found = customerNames.get(pid);
      if (found != null) {
        r.setPartyDisplayName(found);
      } else if (isCustomerIdFallbackLabel(r.getPartyDisplayName(), pid)) {
        r.setPartyDisplayName("Customer");
      }
    } else if (r.getPartyType() == CreditPartyType.VENDOR && StringUtils.hasText(r.getPartyId())) {
      String pid = r.getPartyId().trim();
      String found = vendorNames.get(pid);
      if (found != null) {
        r.setPartyDisplayName(found);
      } else if (isVendorIdFallbackLabel(r.getPartyDisplayName(), pid)) {
        r.setPartyDisplayName("Vendor");
      }
    }
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
      String sourceKey,
      String paymentMethod,
      String bankRef,
      LocalDate txnDate) {
    validateParty(partyType, partyId, partyDisplayName);
    BigDecimal amt = scalePositiveAmount(amount);

    String normalizedSourceKey = normalize(sourceKey);
    if (normalizedSourceKey != null) {
      var existing = creditEntryRepository.findFirstByShopIdAndSourceKey(shopId, normalizedSourceKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    Instant now = Instant.now();
    BigDecimal delta = direction == CreditDirection.INCREASE_DUE ? amt : scale(amt.negate());
    // One atomic update: add to the due, set the status, create the account on a first entry
    CreditBalances.Applied account =
        creditBalances.add(
            shopId, partyType, partyId.trim(), partyDisplayName.trim(), normalize(partyPhone), delta, now);
    BigDecimal nextBalance = account.balance();

    CreditEntry entry = new CreditEntry();
    entry.setShopId(shopId);
    entry.setAccountId(account.accountId());
    entry.setTxnId(TxnIdGenerator.newId());
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
    entry.setPaymentMethod(limit(normalize(paymentMethod), 32));
    entry.setBankRef(limit(normalize(bankRef), 128));
    entry.setTxnDate(txnDate);
    entry.setCreatedByUserId(normalize(userId));
    entry.setCreatedAt(now);
    try {
      return creditEntryRepository.save(entry);
    } catch (RuntimeException e) {
      // no transactions: take the amount back off the due so it matches its entries again
      creditBalances.undo(shopId, partyType, partyId.trim(), delta, Instant.now());
      throw e;
    }
  }

  private static void validateSettlementPaymentMethod(String paymentMethod) {
    if (!StringUtils.hasText(paymentMethod)) {
      throw new ValidationException("paymentMethod is required for settlement");
    }
    String m = paymentMethod.trim().toUpperCase();
    if (!m.equals("CASH")
        && !m.equals("UPI")
        && !m.equals("BANK")
        && !m.equals("CARD")
        && !m.equals("ADJUSTMENT")) {
      throw new ValidationException(
          "paymentMethod must be one of CASH, UPI, BANK, CARD, ADJUSTMENT");
    }
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

  private void recordEntry(String operation) {
    metrics.record(
        CreditMetricsConstants.ENTRIES_TOTAL,
        1,
        "module",
        CreditMetricsConstants.MODULE,
        "operation",
        operation);
  }
}
