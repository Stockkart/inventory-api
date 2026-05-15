package com.inventory.product.service;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PartySettlementPostingRequest;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditEntryType;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.credit.rest.dto.request.CreateCreditSettlementRequest;
import com.inventory.credit.service.CreditService;
import com.inventory.credit.service.CreditSettlementFacade;
import com.inventory.common.exception.ValidationException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Posts vendor payment / customer settlement journal entries and credit ledger rows in one
 * transaction so the books and the credit module stay aligned.
 */
@Service
@Primary
@RequiredArgsConstructor
public class AccountingCreditSettlementFacade implements CreditSettlementFacade {

  private final CreditService creditService;
  private final CreditEntryRepository creditEntryRepository;

  @Autowired(required = false)
  private AccountingFacade accountingFacade;

  @Override
  @Transactional
  public CreditEntry createSettlement(
      String shopId, String userId, CreateCreditSettlementRequest body) {
    String settlementId = resolveSettlementId(body);
    String creditSourceKey = creditSourceKey(body.getPartyType(), settlementId);

    Optional<CreditEntry> existingCredit =
        creditEntryRepository.findFirstByShopIdAndSourceKey(shopId, creditSourceKey);
    if (existingCredit.isPresent()) {
      return existingCredit.get();
    }

    if (accountingFacade != null) {
      JournalSource journalSource = journalSourceFor(body.getPartyType());
      var existingJe =
          accountingFacade.findBySource(shopId, journalSource, settlementId);
      if (existingJe.isPresent()) {
        body.setSourceKey(creditSourceKey);
        return creditService.createSettlement(shopId, userId, body);
      }

      PartySettlementPostingRequest posting =
          PartySettlementPostingRequest.builder()
              .sourceId(settlementId)
              .txnDate(body.getTxnDate())
              .paymentMethod(body.getPaymentMethod())
              .amount(body.getAmount())
              .partyId(body.getPartyId())
              .partyDisplayName(body.getPartyDisplayName())
              .bankRef(body.getBankRef())
              .narration(buildNarration(body))
              .build();

      if (body.getPartyType() == CreditPartyType.VENDOR) {
        accountingFacade.postVendorPayment(shopId, userId, posting);
      } else if (body.getPartyType() == CreditPartyType.CUSTOMER) {
        accountingFacade.postCustomerSettlement(shopId, userId, posting);
      } else {
        throw new ValidationException("Unsupported partyType for settlement");
      }
    }

    body.setSourceKey(creditSourceKey);
    return creditService.createSettlement(shopId, userId, body);
  }

  private static String resolveSettlementId(CreateCreditSettlementRequest body) {
    if (StringUtils.hasText(body.getSourceKey())) {
      String raw = body.getSourceKey().trim();
      int colon = raw.indexOf(':');
      if (colon > 0 && colon < raw.length() - 1) {
        return raw.substring(colon + 1);
      }
      return raw;
    }
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String creditSourceKey(CreditPartyType partyType, String settlementId) {
    return journalSourceFor(partyType).name() + ":" + settlementId;
  }

  private static JournalSource journalSourceFor(CreditPartyType partyType) {
    return partyType == CreditPartyType.VENDOR
        ? JournalSource.VENDOR_PAYMENT
        : JournalSource.CUSTOMER_SETTLEMENT;
  }

  private static String buildNarration(CreateCreditSettlementRequest body) {
    String base =
        body.getPartyType() == CreditPartyType.VENDOR
            ? "Vendor payment · " + body.getPartyDisplayName()
            : "Customer settlement · " + body.getPartyDisplayName();
    if (StringUtils.hasText(body.getNote())) {
      return base + " · " + body.getNote().trim();
    }
    return base;
  }
}
