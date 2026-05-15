package com.inventory.product.service;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PartyCreditChargePostingRequest;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import com.inventory.credit.service.CreditChargeFacade;
import com.inventory.credit.service.CreditService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Posts journal entries for credit charges that are not already represented by a purchase or
 * sale invoice JE, then records the matching credit ledger row.
 */
@Service
@Primary
@RequiredArgsConstructor
public class AccountingCreditChargeFacade implements CreditChargeFacade {

  private static final String PURCHASE_CREDIT_PREFIX = "PURCHASE:CREDIT:";
  private static final String SALE_CREDIT_PREFIX = "SALE:CREDIT:";

  private final CreditService creditService;
  private final CreditEntryRepository creditEntryRepository;

  @Autowired(required = false)
  private AccountingFacade accountingFacade;

  @Override
  @Transactional
  public CreditEntry createCharge(String shopId, String userId, CreateCreditChargeRequest body) {
    String chargeId = resolveChargeId(body);
    JournalSource journalSource = journalSourceFor(body.getPartyType());
    String creditSourceKey = journalSource.name() + ":" + chargeId;

    Optional<CreditEntry> existingCredit =
        creditEntryRepository.findFirstByShopIdAndSourceKey(shopId, creditSourceKey);
    if (existingCredit.isPresent()) {
      return existingCredit.get();
    }

    if (accountingFacade != null && shouldPostAccounting(shopId, body)) {
      var existingJe = accountingFacade.findBySource(shopId, journalSource, chargeId);
      if (!existingJe.isPresent()) {
        PartyCreditChargePostingRequest posting =
            PartyCreditChargePostingRequest.builder()
                .sourceId(chargeId)
                .txnDate(body.getTxnDate())
                .amount(body.getAmount())
                .partyId(body.getPartyId())
                .partyDisplayName(body.getPartyDisplayName())
                .narration(buildNarration(body))
                .build();
        if (body.getPartyType() == CreditPartyType.VENDOR) {
          accountingFacade.postVendorCreditCharge(shopId, userId, posting);
        } else {
          accountingFacade.postCustomerCreditCharge(shopId, userId, posting);
        }
      }
    }

    body.setSourceKey(creditSourceKey);
    return creditService.createCharge(shopId, userId, body);
  }

  /**
   * Invoice- and sale-linked charges already have AP/AR on their source journal entry; posting
   * again would double-count Sundry Creditors / Debtors.
   */
  private boolean shouldPostAccounting(String shopId, CreateCreditChargeRequest body) {
    String sourceKey = body.getSourceKey();
    if (StringUtils.hasText(sourceKey)) {
      String sk = sourceKey.trim().toUpperCase();
      if (sk.startsWith(PURCHASE_CREDIT_PREFIX) || sk.startsWith(SALE_CREDIT_PREFIX)) {
        return false;
      }
    }
    if ("PURCHASE".equalsIgnoreCase(body.getReferenceType())
        && StringUtils.hasText(body.getReferenceId())
        && accountingFacade != null) {
      return accountingFacade
          .findBySource(shopId, JournalSource.VENDOR_PURCHASE_INVOICE, body.getReferenceId().trim())
          .isEmpty();
    }
    if ("SALE".equalsIgnoreCase(body.getReferenceType())
        && StringUtils.hasText(body.getReferenceId())
        && accountingFacade != null) {
      return accountingFacade
          .findBySource(shopId, JournalSource.SALE, body.getReferenceId().trim())
          .isEmpty();
    }
    return true;
  }

  private static String resolveChargeId(CreateCreditChargeRequest body) {
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

  private static JournalSource journalSourceFor(CreditPartyType partyType) {
    return partyType == CreditPartyType.VENDOR
        ? JournalSource.VENDOR_CREDIT_CHARGE
        : JournalSource.CUSTOMER_CREDIT_CHARGE;
  }

  private static String buildNarration(CreateCreditChargeRequest body) {
    String base =
        body.getPartyType() == CreditPartyType.VENDOR
            ? "Vendor payable · " + body.getPartyDisplayName()
            : "Customer receivable · " + body.getPartyDisplayName();
    if (StringUtils.hasText(body.getNote())) {
      return base + " · " + body.getNote().trim();
    }
    return base;
  }
}
