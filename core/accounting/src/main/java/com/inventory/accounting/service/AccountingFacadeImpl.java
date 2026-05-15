package com.inventory.accounting.service;

import static com.inventory.accounting.domain.model.SystemAccountCode.*;
import static com.inventory.accounting.service.MoneyUtil.nz;
import static com.inventory.accounting.service.MoneyUtil.scale;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.api.PartyCreditChargePostingRequest;
import com.inventory.accounting.api.PartySettlementPostingRequest;
import com.inventory.accounting.api.SaleInvoicePostingRequest;
import com.inventory.accounting.api.SalesReturnPostingRequest;
import com.inventory.accounting.api.VendorPurchaseInvoicePostingRequest;
import com.inventory.accounting.api.VendorPurchaseReturnPostingRequest;
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
   * Posts the standard journal for a completed sale (perpetual inventory + output GST):
   *
   * <pre>
   * Dr Cash on Hand                    = paidCash
   * Dr Bank                            = paidOnline
   * Dr Sundry Debtors [CUSTOMER]       = receivable (credit leg)
   * — or legacy single Dr Cash/Bank     = paidAmount when split fields absent
   * Dr Round-off Expense               = max(roundOff, 0)
   * Dr COGS                            = cogsAmount
   *     Cr Sales                       = taxableRevenue
   *     Cr Output CGST/SGST/IGST       = tax components (where positive)
   *     Cr Round-off Payable           = max(−roundOff, 0)
   *     Cr Inventory                   = cogsAmount
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postSale(String shopId, String userId, SaleInvoicePostingRequest in) {
    if (in == null) {
      throw new ValidationException("Sale posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId (sale / purchase id) is required");
    }

    BigDecimal revenue = nz(in.getTaxableRevenue());
    BigDecimal cgst = nz(in.getOutputCgst());
    BigDecimal sgst = nz(in.getOutputSgst());
    BigDecimal igst = nz(in.getOutputIgst());
    BigDecimal cogs = nz(in.getCogsAmount());
    BigDecimal roundOff = nz(in.getRoundOff());

    BigDecimal computedTotal = scale(revenue.add(cgst).add(sgst).add(igst).add(roundOff));
    BigDecimal saleTotal =
        in.getSaleTotal() != null && in.getSaleTotal().signum() > 0
            ? scale(in.getSaleTotal())
            : computedTotal;

    if (saleTotal.signum() <= 0) {
      throw new ValidationException("Sale total must be greater than zero to post");
    }

    BigDecimal paidCash = nz(in.getPaidCash());
    BigDecimal paidOnline = nz(in.getPaidOnline());
    boolean splitTender = in.getPaidCash() != null || in.getPaidOnline() != null;

    BigDecimal outstanding;
    if (in.getReceivableAmount() != null) {
      outstanding = scale(in.getReceivableAmount());
    } else if (splitTender) {
      outstanding = scale(saleTotal.subtract(paidCash).subtract(paidOnline));
    } else {
      BigDecimal paid =
          in.getPaidAmount() != null && in.getPaidAmount().signum() > 0
              ? scale(in.getPaidAmount())
              : MoneyUtil.zero();
      if (paid.compareTo(saleTotal) > 0) {
        paid = saleTotal;
      }
      outstanding = scale(saleTotal.subtract(paid));
      if (paid.signum() > 0) {
        String legacyMethod =
            in.getPaymentMethod() != null ? in.getPaymentMethod().trim().toUpperCase() : "CASH";
        if (resolvePaidAccount(legacyMethod).equals(BANK)) {
          paidOnline = paid;
        } else {
          paidCash = paid;
        }
      }
    }

    if (paidCash.add(paidOnline).add(outstanding).compareTo(saleTotal) > 0) {
      BigDecimal excess = paidCash.add(paidOnline).add(outstanding).subtract(saleTotal);
      if (paidCash.compareTo(excess) >= 0) {
        paidCash = paidCash.subtract(excess);
      } else {
        excess = excess.subtract(paidCash);
        paidCash = MoneyUtil.zero();
        paidOnline = paidOnline.subtract(excess).max(MoneyUtil.zero());
      }
      outstanding = scale(saleTotal.subtract(paidCash).subtract(paidOnline));
    } else if (paidCash.add(paidOnline).add(outstanding).compareTo(saleTotal) < 0) {
      outstanding = scale(saleTotal.subtract(paidCash).subtract(paidOnline));
    }

    // Sale round-off is opposite to purchase: grand total rounded *down* (negative roundOff)
    // means the customer paid less than revenue + tax → Dr round-off expense. Rounded *up*
    // → Cr round-off payable. (Purchase credits payable on a discount; sale debits expense.)
    BigDecimal roundLoss = roundOff.signum() < 0 ? roundOff.negate() : MoneyUtil.zero();
    BigDecimal roundGain = roundOff.signum() > 0 ? roundOff : MoneyUtil.zero();

    // Absorb sub-paisa drift between 2-decimal checkout totals and 4-decimal ledger lines.
    BigDecimal tenderAndRoundDr =
        scale(paidCash.add(paidOnline).add(outstanding).add(roundLoss));
    BigDecimal revenueAndRoundCr =
        scale(revenue.add(cgst).add(sgst).add(igst).add(roundGain));
    BigDecimal drift = revenueAndRoundCr.subtract(tenderAndRoundDr);
    if (drift.signum() != 0) {
      if (drift.signum() > 0) {
        roundLoss = scale(roundLoss.add(drift));
      } else {
        roundGain = scale(roundGain.add(drift.negate()));
      }
    }

    List<PostJournalLine> lines = new ArrayList<>();
    if (paidCash.signum() > 0) {
      lines.add(debit(CASH, scale(paidCash)));
    }
    if (paidOnline.signum() > 0) {
      lines.add(debit(BANK, scale(paidOnline)));
    }
    if (outstanding.signum() > 0) {
      if (in.getCustomerId() == null || in.getCustomerId().isBlank()) {
        throw new ValidationException(
            "customerId is required when sale has an outstanding receivable");
      }
      PostJournalLine debtors = debit(SUNDRY_DEBTORS, outstanding);
      debtors.setPartyType(PartyType.CUSTOMER);
      debtors.setPartyRefId(in.getCustomerId().trim());
      debtors.setPartyDisplayName(
          in.getCustomerDisplayName() != null ? in.getCustomerDisplayName().trim() : null);
      lines.add(debtors);
    }
    if (roundLoss.signum() > 0) {
      lines.add(debit(ROUND_OFF_EXPENSE, roundLoss));
    }
    if (cogs.signum() > 0) {
      lines.add(debit(COGS, cogs));
    }

    if (revenue.signum() > 0) {
      lines.add(credit(SALES, revenue));
    }
    if (cgst.signum() > 0) lines.add(credit(OUTPUT_CGST, cgst));
    if (sgst.signum() > 0) lines.add(credit(OUTPUT_SGST, sgst));
    if (igst.signum() > 0) lines.add(credit(OUTPUT_IGST, igst));
    if (roundGain.signum() > 0) lines.add(credit(ROUND_OFF_PAYABLE, roundGain));
    if (cogs.signum() > 0) lines.add(credit(INVENTORY, cogs));

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.SALE);
    req.setSourceId(in.getSourceId().trim());
    req.setSourceKey("SALE:" + in.getSourceId().trim());
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    req.setNarration(
        "Sale"
            + (in.getInvoiceNo() != null && !in.getInvoiceNo().isBlank()
                ? " · " + in.getInvoiceNo().trim()
                : ""));
    req.setLines(lines);

    return postingService.post(shopId, userId, req);
  }

  /**
   * Customer sales return / credit note (reverses sale revenue, output GST, and COGS):
   *
   * <pre>
   * Dr Sales Returns              = taxableRevenue
   * Dr Output CGST/SGST/IGST      = tax
   * Dr Inventory                  = cogsAmount
   *     Cr COGS                       = cogsAmount
   *     Cr Cash / Bank                = refundCash / refundOnline
   *     Cr Sundry Debtors [CUSTOMER]  = refundToCredit
   *     Cr Round-off Payable          = roundGain (if CN rounded down)
   * Dr Round-off Expense            = roundLoss (if CN rounded up)
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postSalesReturn(String shopId, String userId, SalesReturnPostingRequest in) {
    if (in == null) {
      throw new ValidationException("Sales return posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId (refund id) is required");
    }

    BigDecimal taxable = nz(in.getTaxableRevenue());
    BigDecimal cgst = nz(in.getOutputCgst());
    BigDecimal sgst = nz(in.getOutputSgst());
    BigDecimal igst = nz(in.getOutputIgst());
    BigDecimal cogs = nz(in.getCogsAmount());
    BigDecimal roundOff = nz(in.getRoundOff());

    BigDecimal computedTotal = scale(taxable.add(cgst).add(sgst).add(igst).add(roundOff));
    BigDecimal returnTotal =
        in.getReturnTotal() != null && in.getReturnTotal().signum() > 0
            ? scale(in.getReturnTotal())
            : computedTotal;

    if (returnTotal.signum() <= 0) {
      throw new ValidationException("Return total must be greater than zero to post");
    }

    BigDecimal refundCash = nz(in.getRefundCash());
    BigDecimal refundOnline = nz(in.getRefundOnline());
    BigDecimal refundToCredit = nz(in.getRefundToCredit());
    if (refundCash.add(refundOnline).add(refundToCredit).compareTo(returnTotal) > 0) {
      BigDecimal excess = refundCash.add(refundOnline).add(refundToCredit).subtract(returnTotal);
      if (refundCash.compareTo(excess) >= 0) {
        refundCash = refundCash.subtract(excess);
      } else {
        excess = excess.subtract(refundCash);
        refundCash = MoneyUtil.zero();
        refundOnline = refundOnline.subtract(excess).max(MoneyUtil.zero());
      }
      refundToCredit = scale(returnTotal.subtract(refundCash).subtract(refundOnline));
    } else if (refundCash.add(refundOnline).add(refundToCredit).compareTo(returnTotal) < 0) {
      refundToCredit = scale(returnTotal.subtract(refundCash).subtract(refundOnline));
    }

    BigDecimal roundLoss = roundOff.signum() < 0 ? roundOff.negate() : MoneyUtil.zero();
    BigDecimal roundGain = roundOff.signum() > 0 ? roundOff : MoneyUtil.zero();

    BigDecimal revenueDr = scale(taxable.add(cgst).add(sgst).add(igst).add(roundLoss));
    BigDecimal refundCr = scale(refundCash.add(refundOnline).add(refundToCredit).add(roundGain));
    BigDecimal drift = revenueDr.subtract(refundCr);
    if (drift.signum() != 0) {
      if (drift.signum() > 0) {
        roundLoss = scale(roundLoss.add(drift));
      } else {
        roundGain = scale(roundGain.add(drift.negate()));
      }
    }

    List<PostJournalLine> lines = new ArrayList<>();
    if (taxable.signum() > 0) {
      lines.add(debit(SALES_RETURNS, taxable));
    }
    if (cgst.signum() > 0) lines.add(debit(OUTPUT_CGST, cgst));
    if (sgst.signum() > 0) lines.add(debit(OUTPUT_SGST, sgst));
    if (igst.signum() > 0) lines.add(debit(OUTPUT_IGST, igst));
    if (roundLoss.signum() > 0) lines.add(debit(ROUND_OFF_EXPENSE, roundLoss));
    if (cogs.signum() > 0) {
      lines.add(debit(INVENTORY, cogs));
      lines.add(credit(COGS, cogs));
    }
    if (refundCash.signum() > 0) lines.add(credit(CASH, scale(refundCash)));
    if (refundOnline.signum() > 0) lines.add(credit(BANK, scale(refundOnline)));
    if (refundToCredit.signum() > 0) {
      if (in.getCustomerId() == null || in.getCustomerId().isBlank()) {
        throw new ValidationException(
            "customerId is required when return reduces customer receivable");
      }
      PostJournalLine debtors = credit(SUNDRY_DEBTORS, scale(refundToCredit));
      debtors.setPartyType(PartyType.CUSTOMER);
      debtors.setPartyRefId(in.getCustomerId().trim());
      debtors.setPartyDisplayName(
          in.getCustomerDisplayName() != null ? in.getCustomerDisplayName().trim() : null);
      lines.add(debtors);
    }
    if (roundGain.signum() > 0) lines.add(credit(ROUND_OFF_PAYABLE, roundGain));

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.SALES_RETURN);
    req.setSourceId(in.getSourceId().trim());
    req.setSourceKey("SALES_RETURN:" + in.getSourceId().trim());
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    String narration = "Sales return";
    if (in.getCreditNoteNo() != null && !in.getCreditNoteNo().isBlank()) {
      narration = narration + " · " + in.getCreditNoteNo().trim();
    } else if (in.getOriginalInvoiceNo() != null && !in.getOriginalInvoiceNo().isBlank()) {
      narration = narration + " · vs " + in.getOriginalInvoiceNo().trim();
    }
    req.setNarration(narration);
    req.setLines(lines);
    return postingService.post(shopId, userId, req);
  }

  /**
   * Vendor purchase return (reverses inventory and input GST; reduces payable or records cash in):
   *
   * <pre>
   * Dr Sundry Creditors [VENDOR]  = refundToCredit
   * Dr Cash / Bank                  = refundCash / refundOnline
   *     Cr Inventory                = goodsValue
   *     Cr Input CGST/SGST/IGST     = tax
   *     Dr Round-off Expense        = roundLoss
   *     Cr Round-off Payable        = roundGain
   * </pre>
   */
  @Override
  @Transactional
  public JournalEntry postVendorPurchaseReturn(
      String shopId, String userId, VendorPurchaseReturnPostingRequest in) {
    if (in == null) {
      throw new ValidationException("Vendor purchase return posting request is required");
    }
    if (in.getSourceId() == null || in.getSourceId().isBlank()) {
      throw new ValidationException("sourceId (vendor return id) is required");
    }
    if (in.getVendorId() == null || in.getVendorId().isBlank()) {
      throw new ValidationException("vendorId is required for vendor purchase return posting");
    }

    BigDecimal goods = nz(in.getGoodsValue());
    BigDecimal cgst = nz(in.getInputCgst());
    BigDecimal sgst = nz(in.getInputSgst());
    BigDecimal igst = nz(in.getInputIgst());
    BigDecimal roundOff = nz(in.getRoundOff());

    BigDecimal computedTotal = scale(goods.add(cgst).add(sgst).add(igst).add(roundOff));
    BigDecimal returnTotal =
        in.getReturnTotal() != null && in.getReturnTotal().signum() > 0
            ? scale(in.getReturnTotal())
            : computedTotal;

    if (returnTotal.signum() <= 0) {
      throw new ValidationException("Vendor return total must be greater than zero to post");
    }

    BigDecimal refundCash = nz(in.getRefundCash());
    BigDecimal refundOnline = nz(in.getRefundOnline());
    BigDecimal refundToCredit = nz(in.getRefundToCredit());
    if (refundCash.add(refundOnline).add(refundToCredit).compareTo(returnTotal) > 0) {
      BigDecimal excess = refundCash.add(refundOnline).add(refundToCredit).subtract(returnTotal);
      if (refundCash.compareTo(excess) >= 0) {
        refundCash = refundCash.subtract(excess);
      } else {
        excess = excess.subtract(refundCash);
        refundCash = MoneyUtil.zero();
        refundOnline = refundOnline.subtract(excess).max(MoneyUtil.zero());
      }
      refundToCredit = scale(returnTotal.subtract(refundCash).subtract(refundOnline));
    } else if (refundCash.add(refundOnline).add(refundToCredit).compareTo(returnTotal) < 0) {
      refundToCredit = scale(returnTotal.subtract(refundCash).subtract(refundOnline));
    }

    BigDecimal roundLoss = roundOff.signum() > 0 ? roundOff : MoneyUtil.zero();
    BigDecimal roundGain = roundOff.signum() < 0 ? roundOff.negate() : MoneyUtil.zero();

    List<PostJournalLine> lines = new ArrayList<>();
    if (refundToCredit.signum() > 0) {
      PostJournalLine creditors = debit(SUNDRY_CREDITORS, scale(refundToCredit));
      creditors.setPartyType(PartyType.VENDOR);
      creditors.setPartyRefId(in.getVendorId().trim());
      creditors.setPartyDisplayName(
          in.getVendorDisplayName() != null ? in.getVendorDisplayName().trim() : null);
      lines.add(creditors);
    }
    if (refundCash.signum() > 0) {
      lines.add(debit(CASH, scale(refundCash)));
    }
    if (refundOnline.signum() > 0) {
      lines.add(debit(BANK, scale(refundOnline)));
    }
    if (roundLoss.signum() > 0) lines.add(debit(ROUND_OFF_EXPENSE, roundLoss));
    if (goods.signum() > 0) lines.add(credit(INVENTORY, goods));
    if (cgst.signum() > 0) lines.add(credit(INPUT_CGST, cgst));
    if (sgst.signum() > 0) lines.add(credit(INPUT_SGST, sgst));
    if (igst.signum() > 0) lines.add(credit(INPUT_IGST, igst));
    if (roundGain.signum() > 0) lines.add(credit(ROUND_OFF_PAYABLE, roundGain));

    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(JournalSource.VENDOR_PURCHASE_RETURN);
    req.setSourceId(in.getSourceId().trim());
    req.setSourceKey("VENDOR_PURCHASE_RETURN:" + in.getSourceId().trim());
    req.setTxnDate(resolveTxnDate(in.getTxnDate()));
    req.setNarration(
        "Vendor purchase return"
            + (in.getSupplierCreditNoteNo() != null && !in.getSupplierCreditNoteNo().isBlank()
                ? " · " + in.getSupplierCreditNoteNo().trim()
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
