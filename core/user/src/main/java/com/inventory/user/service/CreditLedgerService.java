package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.CreditLedger;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.LedgerEntryType;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.domain.model.LedgerSource;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CreditLedgerRepository;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.user.rest.dto.ledger.BalanceResponse;
import com.inventory.user.rest.dto.ledger.PayableItemDto;
import com.inventory.user.rest.dto.ledger.PayablesResponse;
import com.inventory.user.rest.dto.ledger.ReceivableItemDto;
import com.inventory.user.rest.dto.ledger.ReceivablesResponse;
import com.inventory.user.rest.dto.ledger.CreateLedgerEntryRequest;
import com.inventory.user.rest.dto.ledger.LedgerEntriesResponse;
import com.inventory.user.rest.dto.ledger.LedgerEntryDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class CreditLedgerService {

  @Autowired
  private CreditLedgerRepository creditLedgerRepository;

  @Autowired
  private VendorRepository vendorRepository;

  @Autowired
  private CustomerRepository customerRepository;

  @Autowired(required = false)
  private ShopServiceAdapter shopServiceAdapter;

  @Autowired(required = false)
  private VendorService vendorService;

  /**
   * Create a ledger entry (e.g. payment, adjustment).
   */
  public LedgerEntryDto createEntry(CreateLedgerEntryRequest request, String shopId, String userId) {
    validateCreateRequest(request);

    CreditLedger entry = new CreditLedger();
    entry.setShopId(shopId);
    entry.setPartyType(request.getPartyType());
    entry.setPartyId(request.getPartyId());
    entry.setAmount(request.getAmount().abs());
    entry.setType(request.getType());
    entry.setSource(request.getSource());
    entry.setReferenceId(request.getReferenceId());
    entry.setReferenceType(request.getReferenceType());
    entry.setDescription(request.getDescription());
    entry.setCreatedByUserId(userId);
    entry.setCreatedAt(Instant.now());

    // When recording payment to vendor, set counterpartyShopId so vendor sees it in their ledger
    if (request.getPartyType() == LedgerPartyType.VENDOR && request.getSource() == LedgerSource.PAYMENT) {
      String cpShopId = resolveCounterpartyShopForVendor(shopId, request.getPartyId());
      entry.setCounterpartyShopId(StringUtils.hasText(cpShopId) ? cpShopId : null);
    }

    entry = creditLedgerRepository.save(entry);
    log.info("Created ledger entry id={} shopId={} partyType={} partyId={} amount={} type={}",
        entry.getId(), shopId, request.getPartyType(), request.getPartyId(), entry.getAmount(), request.getType());
    return toDto(entry);
  }

  /**
   * Create a ledger entry from internal flows (inventory creation). Vendor purchase on credit.
   * @param counterpartyShopId When vendor is a StockKart user (has shops), the vendor's shop
   *   so they can see this receivable when logged into that shop. Null when vendor is not a user.
   */
  public void createEntryForVendorPurchase(String shopId, String vendorId, BigDecimal amount,
      String referenceId, String userId, String counterpartyShopId) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    try {
      CreditLedger entry = new CreditLedger();
      entry.setShopId(shopId);
      entry.setPartyType(LedgerPartyType.VENDOR);
      entry.setPartyId(vendorId);
      entry.setCounterpartyShopId(StringUtils.hasText(counterpartyShopId) ? counterpartyShopId : null);
      entry.setAmount(amount);
      entry.setType(LedgerEntryType.DEBIT);
      entry.setSource(LedgerSource.PURCHASE);
      entry.setReferenceId(referenceId);
      entry.setReferenceType(com.inventory.user.domain.model.LedgerReferenceType.INVENTORY);
      entry.setCreatedByUserId(userId);
      entry.setCreatedAt(Instant.now());
      creditLedgerRepository.save(entry);
      log.debug("Created vendor credit entry shopId={} vendorId={} amount={} counterpartyShopId={}",
          shopId, vendorId, amount, counterpartyShopId);
    } catch (Exception e) {
      log.warn("Failed to create ledger entry for purchase: {}", e.getMessage());
    }
  }

  /**
   * Create a ledger entry for customer sale on credit.
   */
  public void createEntryForSale(String shopId, String customerId, BigDecimal amount,
      String purchaseId, String userId) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    try {
      CreditLedger entry = new CreditLedger();
      entry.setShopId(shopId);
      entry.setPartyType(LedgerPartyType.CUSTOMER);
      entry.setPartyId(customerId);
      entry.setAmount(amount);
      entry.setType(LedgerEntryType.CREDIT);
      entry.setSource(LedgerSource.SALE);
      entry.setReferenceId(purchaseId);
      entry.setReferenceType(com.inventory.user.domain.model.LedgerReferenceType.PURCHASE);
      entry.setCreatedByUserId(userId);
      entry.setCreatedAt(Instant.now());
      creditLedgerRepository.save(entry);
      log.debug("Created customer credit entry shopId={} customerId={} amount={}", shopId, customerId, amount);
    } catch (Exception e) {
      log.warn("Failed to create ledger entry for sale: {}", e.getMessage());
    }
  }

  @Transactional(readOnly = true)
  public BalanceResponse getBalance(String shopId, LedgerPartyType partyType, String partyId) {
    if (!StringUtils.hasText(shopId) || partyType == null || !StringUtils.hasText(partyId)) {
      throw new ValidationException("shopId, partyType, and partyId are required");
    }

    List<CreditLedger> entries = creditLedgerRepository
        .findByShopIdAndPartyTypeAndPartyId(shopId, partyType, partyId);

    BigDecimal balance = BigDecimal.ZERO;
    for (CreditLedger e : entries) {
      if (partyType == LedgerPartyType.VENDOR) {
        balance = e.getType() == LedgerEntryType.DEBIT
            ? balance.add(e.getAmount())
            : balance.subtract(e.getAmount());
      } else {
        balance = e.getType() == LedgerEntryType.CREDIT
            ? balance.add(e.getAmount())
            : balance.subtract(e.getAmount());
      }
    }

    return new BalanceResponse(shopId, partyType, partyId, balance.setScale(2, RoundingMode.HALF_UP));
  }

  @Transactional(readOnly = true)
  public LedgerEntriesResponse listEntries(String shopId, LedgerPartyType partyType,
      String partyId, int page, int size) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }

    Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
    Page<CreditLedger> pageResult;

    if (partyType != null && StringUtils.hasText(partyId)) {
      pageResult = creditLedgerRepository
          .findByShopIdAndPartyTypeAndPartyIdOrderByCreatedAtDesc(shopId, partyType, partyId, pageable);
    } else {
      // Include both: entries where we're the buyer (shopId) and where we're the vendor (counterpartyShopId)
      // So vendors see receivable transactions in Ledger Entries too
      pageResult = creditLedgerRepository
          .findByShopIdOrCounterpartyShopIdOrderByCreatedAtDesc(shopId, shopId, pageable);
    }

    List<LedgerEntryDto> dtos = pageResult.getContent().stream()
        .map(e -> toDtoWithNames(e, shopId))
        .collect(Collectors.toList());

    return new LedgerEntriesResponse(
        dtos,
        pageResult.getNumber(),
        pageResult.getSize(),
        pageResult.getTotalElements(),
        pageResult.getTotalPages());
  }

  /**
   * Get amounts to collect when this shop is the vendor's shop (counterparty).
   * Used when vendor logs into their shop to see what buyer shops owe them.
   */
  @Transactional(readOnly = true)
  public ReceivablesResponse getReceivables(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }

    // Find (buyerShopId, vendorId) pairs that assigned us as vendor's shop
    List<CreditLedger> counterpartyEntries = creditLedgerRepository.findByCounterpartyShopId(shopId);
    Map<String, Boolean> pairsToCollect = new HashMap<>();
    for (CreditLedger e : counterpartyEntries) {
      if (e.getPartyType() != LedgerPartyType.VENDOR || !StringUtils.hasText(e.getShopId()) || !StringUtils.hasText(e.getPartyId())) {
        continue;
      }
      pairsToCollect.put(e.getShopId() + "|" + e.getPartyId(), Boolean.TRUE);
    }

    // For each pair, compute balance from ALL entries (including payments without counterpartyShopId)
    Map<String, BigDecimal> balanceByKey = new HashMap<>();
    for (String key : pairsToCollect.keySet()) {
      String[] parts = key.split("\\|", 2);
      if (parts.length != 2) continue;
      String buyerShopId = parts[0];
      String vendorId = parts[1];
      List<CreditLedger> allEntries = creditLedgerRepository.findByShopIdAndPartyTypeAndPartyId(buyerShopId, LedgerPartyType.VENDOR, vendorId);
      BigDecimal balance = BigDecimal.ZERO;
      for (CreditLedger e : allEntries) {
        BigDecimal delta = e.getType() == LedgerEntryType.DEBIT ? e.getAmount() : e.getAmount().negate();
        balance = balance.add(delta);
      }
      if (balance.compareTo(BigDecimal.ZERO) > 0) {
        balanceByKey.put(key, balance.setScale(2, RoundingMode.HALF_UP));
      }
    }

    List<ReceivableItemDto> receivables = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : balanceByKey.entrySet()) {
      BigDecimal balance = entry.getValue();
      String[] parts = entry.getKey().split("\\|");
      String buyerShopId = parts[0];
      String vendorId = parts[1];
      String buyerShopName = shopServiceAdapter != null
          ? shopServiceAdapter.getShopName(buyerShopId)
          : null;
      if (buyerShopName == null || buyerShopName.trim().isEmpty()) {
        buyerShopName = buyerShopId;
      }
      String buyerOwnerName = shopServiceAdapter != null
          ? shopServiceAdapter.getShopOwnerName(buyerShopId)
          : null;
      String buyerPayerName = buildPayerDisplayName(buyerShopName, buyerOwnerName, buyerShopId);
      String vendorName = vendorRepository.findById(vendorId)
          .map(Vendor::getName)
          .orElse(vendorId);
      receivables.add(new ReceivableItemDto(
          buyerShopId, buyerShopName, buyerPayerName, vendorId, vendorName, balance));
    }
    receivables.sort((a, b) -> a.getBuyerShopName().compareToIgnoreCase(b.getBuyerShopName()));
    return new ReceivablesResponse(receivables);
  }

  private String buildPayerDisplayName(String shopName, String ownerName, String shopId) {
    boolean hasShop = StringUtils.hasText(shopName) && !shopName.equals(shopId);
    boolean hasOwner = StringUtils.hasText(ownerName) && !ownerName.trim().isEmpty();
    if (hasShop && hasOwner) {
      return shopName + " (" + ownerName + ")";
    }
    if (hasOwner) {
      return ownerName;
    }
    return hasShop ? shopName : shopId;
  }

  /**
   * Get amounts to pay when this shop is the buyer (owed to vendors).
   */
  @Transactional(readOnly = true)
  public PayablesResponse getPayables(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }

    List<CreditLedger> entries = creditLedgerRepository.findByShopIdAndPartyType(shopId, LedgerPartyType.VENDOR);
    Map<String, BigDecimal> balanceByVendor = new HashMap<>();
    Map<String, String> counterpartyShopByVendor = new HashMap<>();
    for (CreditLedger e : entries) {
      String vendorId = e.getPartyId();
      BigDecimal delta = e.getType() == LedgerEntryType.DEBIT ? e.getAmount() : e.getAmount().negate();
      balanceByVendor.merge(vendorId, delta, BigDecimal::add);
      if (StringUtils.hasText(e.getCounterpartyShopId())) {
        counterpartyShopByVendor.putIfAbsent(vendorId, e.getCounterpartyShopId());
      }
    }

    List<PayableItemDto> payables = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : balanceByVendor.entrySet()) {
      BigDecimal balance = entry.getValue().setScale(2, RoundingMode.HALF_UP);
      if (balance.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String vendorId = entry.getKey();
      String vendorName = vendorRepository.findById(vendorId)
          .map(Vendor::getName)
          .orElse(vendorId);
      String counterpartyShopName = null;
      String cpShopId = counterpartyShopByVendor.get(vendorId);
      if (StringUtils.hasText(cpShopId) && shopServiceAdapter != null) {
        counterpartyShopName = shopServiceAdapter.getShopName(cpShopId);
        if (counterpartyShopName == null || counterpartyShopName.trim().isEmpty()) {
          counterpartyShopName = cpShopId;
        }
      }
      payables.add(new PayableItemDto(vendorId, vendorName, counterpartyShopName, balance));
    }
    payables.sort((a, b) -> a.getVendorName().compareToIgnoreCase(b.getVendorName()));
    return new PayablesResponse(payables);
  }

  private void validateCreateRequest(CreateLedgerEntryRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getPartyType() == null) {
      throw new ValidationException("partyType is required");
    }
    if (!StringUtils.hasText(request.getPartyId())) {
      throw new ValidationException("partyId is required");
    }
    if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("amount must be positive");
    }
    if (request.getType() == null) {
      throw new ValidationException("type is required");
    }
    if (request.getSource() == null) {
      throw new ValidationException("source is required");
    }
  }

  private LedgerEntryDto toDto(CreditLedger e) {
    return new LedgerEntryDto(
        e.getId(),
        e.getShopId(),
        e.getPartyType(),
        e.getPartyId(),
        null,
        null,
        e.getCounterpartyShopId(),
        null,
        null,
        e.getAmount(),
        e.getType(),
        e.getSource(),
        e.getReferenceId(),
        e.getReferenceType(),
        e.getDescription(),
        e.getCreatedByUserId(),
        e.getCreatedAt());
  }

  private LedgerEntryDto toDtoWithNames(CreditLedger e, String currentShopId) {
    String partyName = resolvePartyName(e.getPartyType(), e.getPartyId());
    String counterpartyShopName = null;
    if (StringUtils.hasText(e.getCounterpartyShopId()) && shopServiceAdapter != null) {
      counterpartyShopName = shopServiceAdapter.getShopName(e.getCounterpartyShopId());
      if (counterpartyShopName == null || counterpartyShopName.trim().isEmpty()) {
        counterpartyShopName = e.getCounterpartyShopId();
      }
    }
    String displayPartyName;
    String roleInEntry;
    if (e.getShopId() != null && e.getShopId().equals(currentShopId)) {
      roleInEntry = "BUYER";
      displayPartyName = partyName;
    } else if (StringUtils.hasText(e.getCounterpartyShopId()) && e.getCounterpartyShopId().equals(currentShopId)) {
      roleInEntry = "VENDOR";
      displayPartyName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(e.getShopId()) : null;
      if (displayPartyName == null || displayPartyName.trim().isEmpty()) {
        displayPartyName = e.getShopId();
      }
    } else {
      roleInEntry = null;
      displayPartyName = partyName;
    }
    return new LedgerEntryDto(
        e.getId(),
        e.getShopId(),
        e.getPartyType(),
        e.getPartyId(),
        partyName,
        counterpartyShopName,
        e.getCounterpartyShopId(),
        displayPartyName,
        roleInEntry,
        e.getAmount(),
        e.getType(),
        e.getSource(),
        e.getReferenceId(),
        e.getReferenceType(),
        e.getDescription(),
        e.getCreatedByUserId(),
        e.getCreatedAt());
  }

  private String resolvePartyName(LedgerPartyType partyType, String partyId) {
    if (!StringUtils.hasText(partyId)) {
      return partyId;
    }
    if (partyType == LedgerPartyType.VENDOR) {
      return vendorRepository.findById(partyId)
          .map(Vendor::getName)
          .orElse(partyId);
    }
    if (partyType == LedgerPartyType.CUSTOMER) {
      return customerRepository.findById(partyId)
          .map(Customer::getName)
          .orElse(partyId);
    }
    return partyId;
  }

  /**
   * Resolve vendor's shop ID so payment can be visible to vendor in their ledger.
   * 1) From existing purchase entries that had counterpartyShopId set
   * 2) Fallback: from VendorService.getShopsForVendor if vendor is a StockKart user
   */
  private String resolveCounterpartyShopForVendor(String shopId, String vendorId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(vendorId)) {
      return null;
    }
    // Prefer counterpartyShopId from existing purchase entries (same as original credit)
    List<CreditLedger> entries = creditLedgerRepository.findByShopIdAndPartyType(shopId, LedgerPartyType.VENDOR);
    for (CreditLedger e : entries) {
      if (vendorId.equals(e.getPartyId()) && StringUtils.hasText(e.getCounterpartyShopId())) {
        return e.getCounterpartyShopId();
      }
    }
    // Fallback: vendor is a user - get their shops and use first one
    if (vendorService != null) {
      try {
        var resp = vendorService.getShopsForVendor(vendorId, shopId);
        if (resp.getData() != null && !resp.getData().isEmpty()) {
          String firstShopId = resp.getData().get(0).getShopId();
          if (StringUtils.hasText(firstShopId)) {
            return firstShopId;
          }
        }
      } catch (Exception ex) {
        log.debug("Could not resolve vendor shop for vendorId={}: {}", vendorId, ex.getMessage());
      }
    }
    return null;
  }
}
