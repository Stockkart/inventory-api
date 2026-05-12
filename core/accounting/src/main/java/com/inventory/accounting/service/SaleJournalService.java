package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.JournalPostingSource;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.domain.repository.GlAccountRepository;
import com.inventory.accounting.service.PostingService.PostingLineDraft;
import com.inventory.common.exception.ValidationException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts when checkout completes: credits revenue and GST output; debits assets — cash/bank for the
 * amount received now, and {@link DefaultAccountCodes#ACCOUNTS_RECEIVABLE} for any unpaid balance
 * (same accrual idea as vendor purchases crediting {@code VEN-*} payables). Purchase cost is still
 * recognised on {@link DefaultAccountCodes#PURCHASES_EXPENSE} via {@link PurchaseJournalService}.
 */
@Service
@RequiredArgsConstructor
public class SaleJournalService {

  public static final String SALE_SOURCE_PREFIX = "PRODUCT:SALE:";

  private final PostingService postingService;
  private final GlBootstrapService glBootstrapService;
  private final GlAccountRepository glAccountRepository;

  @Transactional
  public Optional<String> recordRetailSaleLedger(
      String shopId,
      String purchaseId,
      String invoiceNo,
      BigDecimal grandTotal,
      BigDecimal revenueBeforeTax,
      BigDecimal sgstAmount,
      BigDecimal cgstAmount,
      BigDecimal taxTotal,
      String paymentMethod,
      String receiptGlAccountCode,
      Instant journalDate,
      String userId,
      BigDecimal amountReceivedNow,
      String customerIdForReceivable) {
    return recordRetailSaleLedger(
        shopId, purchaseId, invoiceNo, grandTotal, revenueBeforeTax,
        sgstAmount, cgstAmount, taxTotal, paymentMethod, receiptGlAccountCode,
        journalDate, userId, amountReceivedNow, customerIdForReceivable, null);
  }

  @Transactional
  public Optional<String> recordRetailSaleLedger(
      String shopId,
      String purchaseId,
      String invoiceNo,
      BigDecimal grandTotal,
      BigDecimal revenueBeforeTax,
      BigDecimal sgstAmount,
      BigDecimal cgstAmount,
      BigDecimal taxTotal,
      String paymentMethod,
      String receiptGlAccountCode,
      Instant journalDate,
      String userId,
      BigDecimal amountReceivedNow,
      String customerIdForReceivable,
      Map<String, BigDecimal> splitAmounts) {
    return recordRetailSaleLedger(
        shopId, purchaseId, invoiceNo, grandTotal, revenueBeforeTax,
        sgstAmount, cgstAmount, taxTotal, paymentMethod, receiptGlAccountCode,
        journalDate, userId, amountReceivedNow, customerIdForReceivable, splitAmounts, null);
  }

  @Transactional
  public Optional<String> recordRetailSaleLedger(
      String shopId,
      String purchaseId,
      String invoiceNo,
      BigDecimal grandTotal,
      BigDecimal revenueBeforeTax,
      BigDecimal sgstAmount,
      BigDecimal cgstAmount,
      BigDecimal taxTotal,
      String paymentMethod,
      String receiptGlAccountCode,
      Instant journalDate,
      String userId,
      BigDecimal amountReceivedNow,
      String customerIdForReceivable,
      Map<String, BigDecimal> splitAmounts,
      String bankGlAccountCode) {

    glBootstrapService.ensureDefaultsForShop(shopId);

    BigDecimal gt = scale(nz(grandTotal));
    if (!StringUtils.hasText(shopId) || gt.signum() <= 0) {
      return Optional.empty();
    }
    String sourceKey =
        SALE_SOURCE_PREFIX + (StringUtils.hasText(purchaseId) ? purchaseId : "UNKNOWN");

    BigDecimal gstS = scalePositive(sgstAmount);
    BigDecimal gstC = scalePositive(cgstAmount);
    BigDecimal tTotal = scalePositive(taxTotal);

    BigDecimal fromSplit = gstS.add(gstC);
    BigDecimal taxSum;
    if (fromSplit.signum() > 0) {
      taxSum = fromSplit;
      if (tTotal.signum() > 0 && taxSum.compareTo(tTotal) < 0) {
        taxSum = scale(tTotal);
      }
    } else if (tTotal.signum() > 0) {
      taxSum = tTotal;
    } else {
      taxSum = zero();
    }

    List<PostingLineDraft> taxCredits = new ArrayList<>();
    if (taxSum.signum() > 0) {
      taxCredits.add(
          lineCredit(DefaultAccountCodes.GST_OUTPUT_COMBINED, taxSum, "GST collected"));
    }

    if (taxSum.compareTo(gt) > 0) {
      taxCredits.clear();
      taxSum = zero();
    }

    BigDecimal salesCred = scale(gt.subtract(taxSum));
    BigDecimal hinted = nz(revenueBeforeTax);
    if (hinted.signum() > 0) {
      BigDecimal hintedScaled = scale(hinted);
      BigDecimal drift = hintedScaled.subtract(salesCred).abs();
      BigDecimal tolerate =
          BigDecimal.valueOf(0.05).setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
      if (drift.compareTo(tolerate) <= 0 && hintedScaled.add(taxSum).compareTo(gt) == 0) {
        salesCred = hintedScaled;
      }
    }
    if (salesCred.signum() < 0) {
      taxCredits.clear();
      taxSum = zero();
      salesCred = gt;
    }

    String payMemo =
        ("Received via "
                + (StringUtils.hasText(paymentMethod) ? paymentMethod.trim() : "?")
                + (StringUtils.hasText(invoiceNo) ? " · Inv " + invoiceNo : ""));
    if (payMemo.length() > 280) {
      payMemo = payMemo.substring(0, 280);
    }

    String effectiveBankCode = StringUtils.hasText(bankGlAccountCode)
        ? bankGlAccountCode.trim().toUpperCase(Locale.ROOT)
        : (StringUtils.hasText(receiptGlAccountCode) ? receiptGlAccountCode.trim().toUpperCase(Locale.ROOT) : null);

    List<PaymentMethodResolver.ReceiptAllocation> allocations =
        PaymentMethodResolver.resolveAllocations(
            paymentMethod, gt, splitAmounts, amountReceivedNow, effectiveBankCode);

    BigDecimal immediate = PaymentMethodResolver.computePaidNow(allocations);
    BigDecimal arAmt = PaymentMethodResolver.computeCreditAmount(allocations);

    if (arAmt.signum() > 0 && !StringUtils.hasText(customerIdForReceivable)) {
      throw new ValidationException(
          "Customer is required on the sale to post accounts receivable for the unpaid portion.");
    }

    List<PostingLineDraft> debitDrafts = new ArrayList<>();
    for (PaymentMethodResolver.ReceiptAllocation alloc : allocations) {
      final String debitCode = alloc.glAccountCode();

      GlAccount acc =
          glAccountRepository
              .findFirstByShopIdAndCodeOrderByIdAsc(shopId, debitCode)
              .orElseThrow(
                  () ->
                      new ValidationException(
                          "Unknown GL account \""
                              + debitCode
                              + "\". Ensure it exists under Chart of accounts."));

      if (!acc.isActive()) {
        throw new ValidationException("GL account is inactive: " + debitCode);
      }
      if (acc.getAccountType() != AccountType.ASSET) {
        throw new ValidationException(
            "Sale receipts must debit an ASSET account. Got: "
                + acc.getAccountType() + " for " + debitCode);
      }

      String memo;
      if (alloc.isCredit()) {
        memo = "Customer balance due"
            + (StringUtils.hasText(invoiceNo) ? " · Inv " + invoiceNo.trim() : "");
      } else {
        String methodLabel = debitCode.equals(DefaultAccountCodes.BANK) ? "online" : "cash";
        memo = "Received via " + methodLabel
            + (StringUtils.hasText(invoiceNo) ? " · Inv " + invoiceNo : "");
      }
      if (memo.length() > 280) {
        memo = memo.substring(0, 280);
      }

      if (alloc.isCredit()) {
        debitDrafts.add(
            new PostingLineDraft(
                debitCode, alloc.amount(), null, memo,
                PartyType.CUSTOMER, customerIdForReceivable.trim()));
      } else {
        debitDrafts.add(
            new PostingLineDraft(debitCode, alloc.amount(), null, memo, null, null));
      }
    }

    List<PostingLineDraft> drafts = new ArrayList<>();
    drafts.addAll(debitDrafts);

    drafts.add(
        new PostingLineDraft(
            DefaultAccountCodes.SALES_REVENUE,
            null,
            salesCred,
            "Retail sale (tax-exclusive value)",
            null,
            null));
    drafts.addAll(taxCredits);

    String desc =
        "Sale · "
            + (StringUtils.hasText(invoiceNo) ? invoiceNo.trim() : "Checkout")
            + (StringUtils.hasText(paymentMethod) ? " · " + paymentMethod.trim() : "");

    var journal =
        postingService.postJournal(
            shopId,
            journalDate,
            desc.trim(),
            drafts,
            userId,
            sourceKey,
            JournalPostingSource.SALE);
    return Optional.ofNullable(journal.getId());
  }

  private static PostingLineDraft lineCredit(String code, BigDecimal amt, String memo) {
    return new PostingLineDraft(code, null, scale(amt), memo, null, null);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal scalePositive(BigDecimal v) {
    if (v == null || v.signum() <= 0) {
      return zero();
    }
    return scale(v);
  }

}
