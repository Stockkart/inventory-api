package com.inventory.user.mapper;

import com.inventory.user.domain.model.CreditLedger;
import com.inventory.user.domain.model.LedgerEntryType;
import com.inventory.user.domain.model.LedgerPartyType;
import com.inventory.user.domain.model.LedgerReferenceType;
import com.inventory.user.domain.model.LedgerSource;
import com.inventory.user.rest.dto.request.CreateLedgerEntryRequest;
import com.inventory.user.rest.dto.response.LedgerEntryDto;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CreditLedgerMapper {

  default LedgerEntryDto toLedgerEntryDto(CreditLedger e) {
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

  default LedgerEntryDto toLedgerEntryDtoWithNames(CreditLedger e, String partyName,
      String counterpartyShopName, String displayPartyName, String roleInEntry) {
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

  default CreditLedger toCreditLedgerFromParams(CreateLedgerEntryRequest request, String shopId,
      String userId, String counterpartyShopId) {
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
    entry.setCounterpartyShopId(StringUtils.hasText(counterpartyShopId) ? counterpartyShopId : null);
    return entry;
  }

  default CreditLedger toCreditLedgerForVendorPurchaseInternal(String shopId, String vendorId,
      BigDecimal amount, String referenceId, String userId, String counterpartyShopId) {
    CreditLedger entry = new CreditLedger();
    entry.setShopId(shopId);
    entry.setPartyType(LedgerPartyType.VENDOR);
    entry.setPartyId(vendorId);
    entry.setCounterpartyShopId(StringUtils.hasText(counterpartyShopId) ? counterpartyShopId : null);
    entry.setAmount(amount);
    entry.setType(LedgerEntryType.DEBIT);
    entry.setSource(LedgerSource.PURCHASE);
    entry.setReferenceId(referenceId);
    entry.setReferenceType(LedgerReferenceType.INVENTORY);
    entry.setCreatedByUserId(userId);
    entry.setCreatedAt(Instant.now());
    return entry;
  }

  default CreditLedger toCreditLedgerForSaleInternal(String shopId, String customerId,
      BigDecimal amount, String purchaseId, String userId, String counterpartyShopId) {
    CreditLedger entry = new CreditLedger();
    entry.setShopId(shopId);
    entry.setPartyType(LedgerPartyType.CUSTOMER);
    entry.setPartyId(customerId);
    entry.setAmount(amount);
    entry.setType(LedgerEntryType.CREDIT);
    entry.setSource(LedgerSource.SALE);
    entry.setReferenceId(purchaseId);
    entry.setReferenceType(LedgerReferenceType.PURCHASE);
    entry.setCreatedByUserId(userId);
    entry.setCreatedAt(Instant.now());
    entry.setCounterpartyShopId(StringUtils.hasText(counterpartyShopId) ? counterpartyShopId : null);
    return entry;
  }
}
