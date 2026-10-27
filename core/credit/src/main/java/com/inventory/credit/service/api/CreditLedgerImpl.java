package com.inventory.credit.service.api;

import com.inventory.credit.api.CreditAccountView;
import com.inventory.credit.api.CreditCharge;
import com.inventory.credit.api.CreditEntryView;
import com.inventory.credit.api.CreditLedger;
import com.inventory.credit.api.CreditPartyType;
import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import com.inventory.credit.service.CreditChargeFacade;
import com.inventory.credit.service.CreditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link CreditLedger} for other modules. Each method hands the call to the same credit service
 * or charge facade the callers used before, copying api records into and out of the module's
 * own types field for field.
 */
@Service
@RequiredArgsConstructor
public class CreditLedgerImpl implements CreditLedger {

  private final CreditService creditService;
  private final CreditChargeFacade creditChargeFacade;

  @Override
  public String charge(String shopId, String userId, CreditCharge charge) {
    return idOf(creditChargeFacade.createCharge(shopId, userId, toRequest(charge)));
  }

  @Override
  public String recordCharge(String shopId, String userId, CreditCharge charge) {
    return idOf(creditService.createCharge(shopId, userId, toRequest(charge)));
  }

  @Override
  public void recordReturnDueReduction(
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
    creditService.recordReturnDueReduction(
        shopId,
        userId,
        partyType,
        partyId,
        partyDisplayName,
        amount,
        referenceType,
        referenceId,
        sourceKey,
        note,
        txnDate);
  }

  @Override
  public List<CreditAccountView> accounts(String shopId, CreditPartyType partyType) {
    List<CreditAccount> accounts = creditService.listAccountsByPartyType(shopId, partyType);
    List<CreditAccountView> out = new ArrayList<>(accounts.size());
    for (CreditAccount a : accounts) {
      out.add(toView(a));
    }
    return out;
  }

  @Override
  public List<CreditEntryView> entries(String shopId, CreditPartyType partyType) {
    List<CreditEntry> entries = creditService.listEntriesByPartyType(shopId, partyType);
    List<CreditEntryView> out = new ArrayList<>(entries.size());
    for (CreditEntry e : entries) {
      out.add(toView(e));
    }
    return out;
  }

  private static String idOf(CreditEntry entry) {
    return entry != null ? entry.getId() : null;
  }

  static CreateCreditChargeRequest toRequest(CreditCharge c) {
    CreateCreditChargeRequest r = new CreateCreditChargeRequest();
    r.setPartyType(c.partyType());
    r.setPartyId(c.partyId());
    r.setPartyDisplayName(c.partyDisplayName());
    r.setPartyPhone(c.partyPhone());
    r.setAmount(c.amount());
    r.setNote(c.note());
    r.setReferenceType(c.referenceType());
    r.setReferenceId(c.referenceId());
    r.setSourceKey(c.sourceKey());
    r.setTxnDate(c.txnDate());
    return r;
  }

  static CreditAccountView toView(CreditAccount a) {
    if (a == null) {
      return null;
    }
    return new CreditAccountView(
        a.getId(),
        a.getShopId(),
        a.getPartyType(),
        a.getPartyRefId(),
        a.getPartyDisplayName(),
        a.getPartyPhone(),
        a.getCurrentBalance(),
        a.getStatus(),
        a.getCreatedAt(),
        a.getUpdatedAt(),
        a.getLastEntryAt());
  }

  static CreditEntryView toView(CreditEntry e) {
    if (e == null) {
      return null;
    }
    return new CreditEntryView(
        e.getId(),
        e.getTxnId(),
        e.getShopId(),
        e.getAccountId(),
        e.getPartyType(),
        e.getPartyRefId(),
        e.getEntryType(),
        e.getDirection(),
        e.getAmount(),
        e.getBalanceAfter(),
        e.getNote(),
        e.getReferenceType(),
        e.getReferenceId(),
        e.getSourceKey(),
        e.getPaymentMethod(),
        e.getBankRef(),
        e.getTxnDate(),
        e.getCreatedByUserId(),
        e.getCreatedAt());
  }
}
