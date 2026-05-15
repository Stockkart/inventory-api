package com.inventory.accounting.service;

import static com.inventory.accounting.domain.model.SystemAccountCode.*;
import static com.inventory.accounting.service.MoneyUtil.nz;
import static com.inventory.accounting.service.MoneyUtil.scale;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.api.PartyCreditChargePostingRequest;
import com.inventory.accounting.api.PartySettlementPostingRequest;
import com.inventory.accounting.api.VendorPurchaseInvoicePostingRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.repository.JournalEntryRepository;
import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridge between business modules (product, credit, etc.) and the posting engine. Encapsulates the
 * canonical journal templates so callers only supply numbers; the chart-of-account selection and
 * sign discipline are managed here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingFacadeImpl implements AccountingFacade {

  private final AccountingPostingService postingService;
  private final JournalEntryRepository journalEntryRepository;

  @Override
  @Transactional
  public JournalEntry post(String shopId, String userId, PostJournalRequest req) {
    return postingService.post(shopId, userId, req);
  }

  @Override
  @Transactional
  public JournalEntry reverse(
      String shopId, String userId, String originalEntryId, String reason) {
    return postingService.reverse(shopId, userId, originalEntryId, reason);
  }

  @Override
  public Optional<JournalEntry> findBySource(
      String shopId, JournalSource sourceType, String sourceId) {
    if (sourceType == null || sourceId == null || sourceId.isBlank()) {
      return Optional.empty();
    }
    return journalEntryRepository.findByShopIdAndSourceTypeAndSourceId(
        shopId, sourceType, sourceId.trim());
  }

  /**
   * Posts the standard journal for a vendor purchase invoice (perpetual inventory + GST):
   *
   * <pre>
   * Dr Inventory                    = goodsValue
   * Dr Input CGST/SGST/IGST         = tax components (where positive)
   * Dr Shipping &amp; Freight       = shippingCharge (optional)
   * Dr Other Operating Expenses     = otherCharges (optional)
   * Dr Round-off Expense            = max(roundOff, 0)
   *     Cr Cash in Hand             = paidAmount when paymentMethod=CASH
   *     Cr Bank                     = paidAmount when paymentMethod=UPI|BANK|CARD
   *     Cr Sundry Creditors  [VENDOR:vendorId] = invoiceTotal - paidAmount
   *     Cr Round-off Payable        = max(-roundOff, 0)
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postVendorPurchaseInvoice(
      String shopId, String userId, VendorPurchaseInvoicePostingRequest in) {
    if (in == null) {
      throw new ValidationException("Vendor purchase invoice posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId (vendor purchase invoice id) is required");
    }
    if (in.getVendorId() == null || in.getVendorId().isBlank()) {
      throw new ValidationException("vendorId is required for vendor purchase invoice posting");
    }

    BigDecimal goodsValue = nz(in.getGoodsValue());
    BigDecimal cgst = nz(in.getInputCgst());
    BigDecimal sgst = nz(in.getInputSgst());
    BigDecimal igst = nz(in.getInputIgst());
    BigDecimal shipping = nz(in.getShippingCharge());
    BigDecimal otherCharges = nz(in.getOtherCharges());
    BigDecimal roundOff = nz(in.getRoundOff());

    BigDecimal computedTotal =
        scale(goodsValue.add(cgst).add(sgst).add(igst).add(shipping).add(otherCharges));
    BigDecimal invoiceTotal =
        in.getInvoiceTotal() != null && in.getInvoiceTotal().signum() > 0
            ? scale(in.getInvoiceTotal())
            : computedTotal;

    if (invoiceTotal.signum() <= 0) {
      throw new ValidationException("Vendor invoice total must be greater than zero to post");
    }

    BigDecimal paid =
        in.getPaidAmount() != null && in.getPaidAmount().signum() > 0
            ? scale(in.getPaidAmount())
            : MoneyUtil.zero();
    if (paid.compareTo(invoiceTotal) > 0) {
      paid = invoiceTotal;
    }
    BigDecimal outstanding = scale(invoiceTotal.subtract(paid));
    BigDecimal roundLoss = roundOff.signum() > 0 ? roundOff : MoneyUtil.zero();
    BigDecimal roundGain = roundOff.signum() < 0 ? roundOff.negate() : MoneyUtil.zero();

    List<PostJournalLine> lines = new ArrayList<>();
    if (goodsValue.signum() > 0) {
      lines.add(debit(INVENTORY, goodsValue));
    }
    if (cgst.signum() > 0) lines.add(debit(INPUT_CGST, cgst));
    if (sgst.signum() > 0) lines.add(debit(INPUT_SGST, sgst));
    if (igst.signum() > 0) lines.add(debit(INPUT_IGST, igst));
    if (shipping.signum() > 0) lines.add(debit(SHIPPING_FREIGHT, shipping));
    if (otherCharges.signum() > 0) lines.add(debit(OTHER_OPERATING_EXPENSES, otherCharges));
    if (roundLoss.signum() > 0) lines.add(debit(ROUND_OFF_EXPENSE, roundLoss));

    if (paid.signum() > 0) {
      lines.add(credit(resolvePaidAccount(in.getPaymentMethod()), paid));
    }
    if (outstanding.signum() > 0) {
      PostJournalLine line = credit(SUNDRY_CREDITORS, outstanding);
      line.setPartyType(PartyType.VENDOR);
      line.setPartyRefId(in.getVendorId().trim());
      line.setPartyDisplayName(
          in.getVendorDisplayName() != null ? in.getVendorDisplayName().trim() : null);
      lines.add(line);
    }
    if (roundGain.signum() > 0) lines.add(credit(ROUND_OFF_PAYABLE, roundGain));

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.VENDOR_PURCHASE_INVOICE);
    req.setSourceId(in.getSourceId().trim());
    req.setSourceKey("VENDOR_PURCHASE_INVOICE:" + in.getSourceId().trim());
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    req.setNarration(
        "Vendor purchase invoice"
            + (in.getInvoiceNo() != null && !in.getInvoiceNo().isBlank()
                ? " · " + in.getInvoiceNo().trim()
                : ""));
    req.setLines(lines);

    return postingService.post(shopId, userId, req);
  }

  /**
   * Vendor payment against Sundry Creditors:
   *
   * <pre>
   * Dr Sundry Creditors [VENDOR]  = amount
   *     Cr Cash / Bank            = amount (tender)
   *     — or Cr Discount Received = amount (ADJUSTMENT)
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postVendorPayment(
      String shopId, String userId, PartySettlementPostingRequest in) {
    return postPartySettlement(
        shopId,
        userId,
        in,
        JournalSource.VENDOR_PAYMENT,
        PartyType.VENDOR,
        SUNDRY_CREDITORS,
        true);
  }

  /**
   * Customer settlement against Sundry Debtors:
   *
   * <pre>
   * Dr Cash / Bank            = amount (tender)
   *     — or Dr Bad Debts       = amount (ADJUSTMENT)
   *     Cr Sundry Debtors [CUSTOMER] = amount
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postCustomerSettlement(
      String shopId, String userId, PartySettlementPostingRequest in) {
    return postPartySettlement(
        shopId,
        userId,
        in,
        JournalSource.CUSTOMER_SETTLEMENT,
        PartyType.CUSTOMER,
        SUNDRY_DEBTORS,
        false);
  }

  /**
   * Standalone vendor payable increase (UI “You owe more”):
   *
   * <pre>
   * Dr Purchases                 = amount
   *     Cr Sundry Creditors [VENDOR] = amount
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postVendorCreditCharge(
      String shopId, String userId, PartyCreditChargePostingRequest in) {
    return postPartyCreditCharge(shopId, userId, in, JournalSource.VENDOR_CREDIT_CHARGE, true);
  }

  /**
   * Standalone customer receivable increase (UI “They owe more”):
   *
   * <pre>
   * Dr Sundry Debtors [CUSTOMER] = amount
   *     Cr Sales                   = amount
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postCustomerCreditCharge(
      String shopId, String userId, PartyCreditChargePostingRequest in) {
    return postPartyCreditCharge(shopId, userId, in, JournalSource.CUSTOMER_CREDIT_CHARGE, false);
  }

  private JournalEntry postPartyCreditCharge(
      String shopId,
      String userId,
      PartyCreditChargePostingRequest in,
      JournalSource sourceType,
      boolean vendorCharge) {
    if (in == null) {
      throw new ValidationException("Credit charge posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId is required for credit charge posting");
    }
    if (in.getPartyId() == null || in.getPartyId().isBlank()) {
      throw new ValidationException("partyId is required for credit charge posting");
    }
    BigDecimal amount = nz(in.getAmount());
    if (amount.signum() <= 0) {
      throw new ValidationException("Credit charge amount must be greater than zero");
    }

    List<PostJournalLine> lines = new ArrayList<>();
    if (vendorCharge) {
      lines.add(debit(PURCHASES, amount));
      PostJournalLine creditors = credit(SUNDRY_CREDITORS, amount);
      creditors.setPartyType(PartyType.VENDOR);
      creditors.setPartyRefId(in.getPartyId().trim());
      creditors.setPartyDisplayName(
          in.getPartyDisplayName() != null ? in.getPartyDisplayName().trim() : null);
      lines.add(creditors);
    } else {
      PostJournalLine debtors = debit(SUNDRY_DEBTORS, amount);
      debtors.setPartyType(PartyType.CUSTOMER);
      debtors.setPartyRefId(in.getPartyId().trim());
      debtors.setPartyDisplayName(
          in.getPartyDisplayName() != null ? in.getPartyDisplayName().trim() : null);
      lines.add(debtors);
      lines.add(credit(SALES, amount));
    }

    String sourceId = in.getSourceId().trim();
    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(sourceType);
    req.setSourceId(sourceId);
    req.setSourceKey(sourceType.name() + ":" + sourceId);
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    req.setNarration(
        in.getNarration() != null && !in.getNarration().isBlank()
            ? in.getNarration().trim()
            : (vendorCharge ? "Vendor payable increase" : "Customer receivable increase"));
    req.setLines(lines);
    return postingService.post(shopId, userId, req);
  }

  private JournalEntry postPartySettlement(
      String shopId,
      String userId,
      PartySettlementPostingRequest in,
      JournalSource sourceType,
      PartyType partyType,
      String controlAccountCode,
      boolean vendorPayment) {
    if (in == null) {
      throw new ValidationException("Settlement posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId is required for settlement posting");
    }
    if (in.getPartyId() == null || in.getPartyId().isBlank()) {
      throw new ValidationException("partyId is required for settlement posting");
    }
    BigDecimal amount = nz(in.getAmount());
    if (amount.signum() <= 0) {
      throw new ValidationException("Settlement amount must be greater than zero");
    }

    String method =
        in.getPaymentMethod() != null ? in.getPaymentMethod().trim().toUpperCase() : "CASH";
    boolean adjustment = "ADJUSTMENT".equals(method);

    List<PostJournalLine> lines = new ArrayList<>();
    PostJournalLine controlLine;
    if (vendorPayment) {
      controlLine = debit(controlAccountCode, amount);
    } else {
      controlLine = credit(controlAccountCode, amount);
    }
    controlLine.setPartyType(partyType);
    controlLine.setPartyRefId(in.getPartyId().trim());
    controlLine.setPartyDisplayName(
        in.getPartyDisplayName() != null ? in.getPartyDisplayName().trim() : null);
    lines.add(controlLine);

    if (vendorPayment) {
      if (adjustment) {
        lines.add(credit(DISCOUNT_RECEIVED, amount));
      } else {
        lines.add(credit(resolvePaidAccount(method), amount));
      }
    } else {
      if (adjustment) {
        lines.add(debit(BAD_DEBTS_WRITTEN_OFF, amount));
      } else {
        lines.add(debit(resolvePaidAccount(method), amount));
      }
    }

    String sourceId = in.getSourceId().trim();
    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(sourceType);
    req.setSourceId(sourceId);
    req.setSourceKey(sourceType.name() + ":" + sourceId);
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    String narration = in.getNarration();
    if (narration == null || narration.isBlank()) {
      narration =
          vendorPayment
              ? "Vendor payment"
              : "Customer settlement";
    }
    if (in.getBankRef() != null && !in.getBankRef().isBlank()) {
      narration = narration + " · Ref " + in.getBankRef().trim();
    }
    req.setNarration(narration);
    req.setLines(lines);
    return postingService.post(shopId, userId, req);
  }

  private static LocalDate resolveTxnDate(LocalDate provided) {
    if (provided != null) return provided;
    return LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  /**
   * Maps the caller-supplied payment tender to a system asset account. Anything that moves through
   * a bank rail (UPI / NEFT / card swipes / direct bank transfer) credits {@code Bank}; only
   * physical currency credits {@code Cash on Hand}. Unknown methods fall back to {@code Cash} so a
   * misconfigured caller doesn't silently inflate Bank — callers are expected to default
   * unspecified methods to {@code CREDIT} upstream so this branch is rarely hit in practice.
   */
  private static String resolvePaidAccount(String paymentMethod) {
    if (paymentMethod == null) return CASH;
    switch (paymentMethod.trim().toUpperCase()) {
      case "UPI":
      case "BANK":
      case "CARD":
      case "NEFT":
      case "RTGS":
      case "IMPS":
      case "ONLINE":
        return BANK;
      case "CASH":
      default:
        return CASH;
    }
  }

  private static PostJournalLine debit(String code, BigDecimal amount) {
    PostJournalLine l = new PostJournalLine();
    l.setAccountCode(code);
    l.setDebit(amount);
    return l;
  }

  private static PostJournalLine credit(String code, BigDecimal amount) {
    PostJournalLine l = new PostJournalLine();
    l.setAccountCode(code);
    l.setCredit(amount);
    return l;
  }
}
