package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.user.domain.model.CreditLedger;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.LedgerEntryType;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.domain.model.LedgerSource;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CreditLedgerRepository;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.user.mapper.CreditLedgerMapper;
import com.inventory.user.rest.dto.request.CreateLedgerEntryRequest;
import com.inventory.user.rest.dto.response.BalanceResponse;
import com.inventory.user.rest.dto.response.CustomerReceivableItemDto;
import com.inventory.user.rest.dto.response.CustomerReceivablesResponse;
import com.inventory.user.rest.dto.response.LedgerEntriesResponse;
import com.inventory.user.rest.dto.response.LedgerEntryDto;
import com.inventory.user.rest.dto.response.PayableItemDto;
import com.inventory.user.rest.dto.response.PayableToShopItemDto;
import com.inventory.user.rest.dto.response.PayablesResponse;
import com.inventory.user.rest.dto.response.PayablesToShopsResponse;
import com.inventory.user.rest.dto.response.ReceivableItemDto;
import com.inventory.user.rest.dto.response.ReceivablesResponse;
import com.inventory.user.validation.CreditLedgerValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

  @Autowired(required = false)
  private UserShopMembershipService userShopMembershipService;

  @Autowired
  private CreditLedgerMapper creditLedgerMapper;

  @Autowired
  private CreditLedgerValidator creditLedgerValidator;

  /**
   * Create a ledger entry (e.g. payment, adjustment).
   */
  public LedgerEntryDto createEntry(CreateLedgerEntryRequest request, String shopId, String userId) {
    creditLedgerValidator.validateCreateLedgerEntryRequest(request);

    String counterpartyShopId = null;
    if (request.getPartyType() == LedgerPartyType.VENDOR && request.getSource() == LedgerSource.PAYMENT) {
      counterpartyShopId = resolveCounterpartyShopForVendor(shopId, request.getPartyId());
    } else if (request.getPartyType() == LedgerPartyType.CUSTOMER && request.getSource() == LedgerSource.PAYMENT) {
      counterpartyShopId = resolveCounterpartyShopForCustomer(request.getPartyId());
    }
    CreditLedger entry = creditLedgerMapper.toCreditLedgerFromParams(request, shopId, userId, counterpartyShopId);
    entry = creditLedgerRepository.save(entry);
    log.info("Created ledger entry id={} shopId={} partyType={} partyId={} amount={} type={}",
        entry.getId(), shopId, request.getPartyType(), request.getPartyId(), entry.getAmount(), request.getType());
    return creditLedgerMapper.toLedgerEntryDto(entry);
  }

  /**
   * Create a ledger entry from internal flows (inventory creation). Vendor purchase on credit.
   *
   * @param counterpartyShopId When vendor is a StockKart user (has shops), the vendor's shop
   *                           so they can see this receivable when logged into that shop. Null when vendor is not a user.
   */
  public void createEntryForVendorPurchase(String shopId, String vendorId, BigDecimal amount,
                                           String referenceId, String userId, String counterpartyShopId) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    try {
      CreditLedger entry = creditLedgerMapper.toCreditLedgerForVendorPurchaseInternal(
          shopId, vendorId, amount, referenceId, userId, counterpartyShopId);
      creditLedgerRepository.save(entry);
      log.debug("Created vendor credit entry shopId={} vendorId={} amount={} counterpartyShopId={}",
          shopId, vendorId, amount, counterpartyShopId);
    } catch (Exception e) {
      log.warn("Failed to create ledger entry for purchase: {}", e.getMessage());
    }
  }

  /**
   * Create a ledger entry for customer sale on credit.
   * When customer is a StockKart user (has shops), sets counterpartyShopId so they see it in Amount to Pay.
   */
  public void createEntryForSale(String shopId, String customerId, BigDecimal amount,
                                 String purchaseId, String userId) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    try {
      String customerShopId = resolveCounterpartyShopForCustomer(customerId);
      CreditLedger entry = creditLedgerMapper.toCreditLedgerForSaleInternal(
          shopId, customerId, amount, purchaseId, userId, customerShopId);
      creditLedgerRepository.save(entry);
      log.debug("Created customer credit entry shopId={} customerId={} amount={} counterpartyShopId={}",
          shopId, customerId, amount, customerShopId);
    } catch (Exception e) {
      log.warn("Failed to create ledger entry for sale: {}", e.getMessage());
    }
  }

  @Transactional(readOnly = true)
  public BalanceResponse getBalance(String shopId, LedgerPartyType partyType, String partyId) {
    creditLedgerValidator.validateGetBalanceParams(shopId, partyType, partyId);

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
        .map(e -> toDtoWithNamesViaMapper(e, shopId))
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

  /**
   * Get amounts to collect from customers (when we sold to them on credit).
   */
  @Transactional(readOnly = true)
  public CustomerReceivablesResponse getCustomerReceivables(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }
    List<CreditLedger> entries = creditLedgerRepository.findByShopIdAndPartyType(shopId, LedgerPartyType.CUSTOMER);
    Map<String, BigDecimal> balanceByCustomer = new HashMap<>();
    for (CreditLedger e : entries) {
      String customerId = e.getPartyId();
      BigDecimal delta = e.getType() == LedgerEntryType.CREDIT ? e.getAmount() : e.getAmount().negate();
      balanceByCustomer.merge(customerId, delta, BigDecimal::add);
    }
    List<CustomerReceivableItemDto> receivables = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : balanceByCustomer.entrySet()) {
      BigDecimal balance = entry.getValue().setScale(2, RoundingMode.HALF_UP);
      if (balance.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String customerId = entry.getKey();
      Customer customer = customerRepository.findById(customerId).orElse(null);
      String customerName = customer != null ? customer.getName() : customerId;
      String customerPhone = customer != null ? customer.getPhone() : null;
      receivables.add(new CustomerReceivableItemDto(customerId, customerName, customerPhone, balance));
    }
    receivables.sort((a, b) -> a.getCustomerName().compareToIgnoreCase(b.getCustomerName()));
    return new CustomerReceivablesResponse(receivables);
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
   * Get amounts to pay to other shops (when we bought from them as customer on credit).
   */
  @Transactional(readOnly = true)
  public PayablesToShopsResponse getPayablesToShops(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not authenticated");
    }
    List<CreditLedger> entries = creditLedgerRepository.findByCounterpartyShopId(shopId);
    Map<String, BigDecimal> balanceBySeller = new HashMap<>();
    Map<String, String> customerIdBySeller = new HashMap<>();
    for (CreditLedger e : entries) {
      if (e.getPartyType() != LedgerPartyType.CUSTOMER || !StringUtils.hasText(e.getShopId())) {
        continue;
      }
      String sellerShopId = e.getShopId();
      BigDecimal delta = e.getType() == LedgerEntryType.CREDIT ? e.getAmount() : e.getAmount().negate();
      balanceBySeller.merge(sellerShopId, delta, BigDecimal::add);
      if (StringUtils.hasText(e.getPartyId())) {
        customerIdBySeller.putIfAbsent(sellerShopId, e.getPartyId());
      }
    }
    List<PayableToShopItemDto> payables = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : balanceBySeller.entrySet()) {
      BigDecimal balance = entry.getValue().setScale(2, RoundingMode.HALF_UP);
      if (balance.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String sellerShopId = entry.getKey();
      String sellerShopName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(sellerShopId) : null;
      if (sellerShopName == null || sellerShopName.trim().isEmpty()) {
        sellerShopName = sellerShopId;
      }
      String customerId = customerIdBySeller.get(sellerShopId);
      payables.add(new PayableToShopItemDto(sellerShopId, sellerShopName, customerId, balance));
    }
    payables.sort((a, b) -> a.getSellerShopName().compareToIgnoreCase(b.getSellerShopName()));
    return new PayablesToShopsResponse(payables);
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

  private LedgerEntryDto toDtoWithNamesViaMapper(CreditLedger e, String currentShopId) {
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
      if (e.getPartyType() == LedgerPartyType.VENDOR) {
        roleInEntry = "BUYER"; // we owe vendor
        displayPartyName = partyName;
      } else {
        roleInEntry = "SELLER"; // customer owes us
        displayPartyName = partyName;
      }
    } else if (StringUtils.hasText(e.getCounterpartyShopId()) && e.getCounterpartyShopId().equals(currentShopId)) {
      if (e.getPartyType() == LedgerPartyType.VENDOR) {
        roleInEntry = "VENDOR"; // they owe us (we're the vendor)
        displayPartyName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(e.getShopId()) : null;
      } else {
        roleInEntry = "BUYER"; // we owe shop (we're the customer)
        displayPartyName = shopServiceAdapter != null ? shopServiceAdapter.getShopName(e.getShopId()) : null;
      }
      if (displayPartyName == null || displayPartyName.trim().isEmpty()) {
        displayPartyName = e.getShopId();
      }
    } else {
      roleInEntry = null;
      displayPartyName = partyName;
    }
    return creditLedgerMapper.toLedgerEntryDtoWithNames(
        e, partyName, counterpartyShopName, displayPartyName, roleInEntry);
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

  /**
   * Resolve customer's shop ID when customer is a StockKart user, so they see the payable in their ledger.
   */
  private String resolveCounterpartyShopForCustomer(String customerId) {
    if (!StringUtils.hasText(customerId) || userShopMembershipService == null) {
      return null;
    }
    return customerRepository.findById(customerId)
        .filter(c -> StringUtils.hasText(c.getUserId()))
        .map(c -> {
          var resp = userShopMembershipService.getShopsForUser(c.getUserId());
          if (resp.getData() != null && !resp.getData().isEmpty()) {
            String firstShopId = resp.getData().get(0).getShopId();
            return StringUtils.hasText(firstShopId) ? firstShopId : null;
          }
          return null;
        })
        .orElse(null);
  }
}
