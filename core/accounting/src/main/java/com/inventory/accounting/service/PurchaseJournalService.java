package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.DefaultAccountCodes;
import com.inventory.accounting.domain.model.JournalPostingSource;
import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.PartyType;
import com.inventory.accounting.service.PostingService.PostingLineDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Vendor stock-in on credit: Dr {@link DefaultAccountCodes#PURCHASES_EXPENSE} (ex-GST cost), Dr
 * GST input when applicable, Cr per-vendor payable ({@code VEN-*}). Does not debit cash —
 * obligation is to the supplier. Same-day cash purchases would substitute Cr {@link
 * DefaultAccountCodes#CASH} (and optionally a different sourcing flow) instead of payable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseJournalService {

  public static final String PURCHASE_SOURCE_PREFIX = "PRODUCT:PURCHASE_INV:";

  private final PostingService postingService;
  private final GlBootstrapService glBootstrapService;
  private final VendorPayableNominalService vendorPayableNominalService;

  @Transactional
  public Optional<String> recordVendorPurchaseLedger(
      String shopId, String userId, VendorPurchaseLedgerInput inv) {

    glBootstrapService.ensureDefaultsForShop(shopId);

    if (inv == null
        || !StringUtils.hasText(inv.invoiceMongoId())
        || !StringUtils.hasText(inv.vendorId())
        || inv.lines() == null
        || inv.lines().isEmpty()) {
      return Optional.empty();
    }

    BigDecimal sumLines = sumLineCostValues(inv.lines());
    BigDecimal tax = scalePositive(inv.taxTotal());
    BigDecimal extras =
        scale(
            nz(inv.shippingCharge())
                .add(nz(inv.otherCharges()))
                .add(nz(inv.roundOff())));
    BigDecimal lineSub = nz(inv.lineSubTotal());
    BigDecimal invTotal = nz(inv.invoiceTotal());

    BigDecimal payable;
    if (invTotal.signum() > 0) {
      payable = scale(invTotal);
    } else {
      BigDecimal base =
          lineSub.signum() > 0 ? scale(lineSub) : (sumLines.signum() > 0 ? scale(sumLines) : zero());
      if (base.signum() <= 0 && tax.signum() <= 0 && extras.signum() <= 0) {
        warnSkipNoBasis(shopId, inv.invoiceMongoId(), sumLines, lineSub, invTotal);
        return Optional.empty();
      }
      payable = scale(base.add(tax).add(extras));
    }

    if (payable.signum() <= 0) {
      warnSkipNoBasis(shopId, inv.invoiceMongoId(), sumLines, lineSub, invTotal);
      return Optional.empty();
    }

    if (tax.compareTo(payable) > 0) {
      tax = scale(payable);
    }
    BigDecimal purchasesExclGst = scale(payable.subtract(tax));
    if (purchasesExclGst.signum() < 0) {
      purchasesExclGst = zero();
    }

    String invNo = inv.invoiceNo();

    List<PostingLineDraft> drafts = new ArrayList<>();
    drafts.add(
        new PostingLineDraft(
            DefaultAccountCodes.PURCHASES_EXPENSE,
            purchasesExclGst,
            null,
            "Purchase (ex-GST) · "
                + (StringUtils.hasText(invNo) ? invNo.trim() : inv.invoiceMongoId()),
            null,
            null));

    if (tax.signum() > 0) {
      drafts.add(
          new PostingLineDraft(
              DefaultAccountCodes.GST_INPUT_COMBINED,
              tax,
              null,
              "GST input (combined)",
              null,
              null));
    }

    List<PaymentMethodResolver.ReceiptAllocation> allocations =
        PaymentMethodResolver.resolveAllocations(
            inv.paymentMethod(), payable, inv.splitAmounts(), inv.paidAmount(),
            inv.bankGlAccountCode());

    BigDecimal creditPortion = PaymentMethodResolver.computeCreditAmount(allocations);
    BigDecimal paidPortion = PaymentMethodResolver.computePaidNow(allocations);

    if (paidPortion.signum() > 0) {
      for (PaymentMethodResolver.ReceiptAllocation alloc : allocations) {
        if (alloc.isCredit()) continue;
        String creditCode = alloc.glAccountCode();
        String creditMemo = "Paid to vendor (" + creditCode.toLowerCase() + ")"
            + (StringUtils.hasText(invNo) ? " · Inv " + invNo.trim().replace('\n', ' ') : "");
        if (creditMemo.length() > 280) creditMemo = creditMemo.substring(0, 280);
        drafts.add(
            new PostingLineDraft(creditCode, null, alloc.amount(), creditMemo, null, null));
      }
    }

    if (creditPortion.signum() > 0) {
      GlAccount vendorAp =
          vendorPayableNominalService.resolveOrCreateVendorPayable(
              shopId, inv.vendorId(), inv.vendorDisplayName());
      String apMemo =
          "Purchase / stock-in (credit)"
              + (StringUtils.hasText(invNo) ? " · Inv " + invNo.trim().replace('\n', ' ') : "");
      if (apMemo.length() > 280) apMemo = apMemo.substring(0, 280);
      drafts.add(
          new PostingLineDraft(
              vendorAp.getCode(), null, creditPortion, apMemo,
              PartyType.VENDOR, inv.vendorId().trim()));
    }

    if (paidPortion.signum() <= 0 && creditPortion.signum() <= 0) {
      GlAccount vendorAp =
          vendorPayableNominalService.resolveOrCreateVendorPayable(
              shopId, inv.vendorId(), inv.vendorDisplayName());
      String apMemo =
          "Purchase / stock-in"
              + (StringUtils.hasText(invNo) ? " · Inv " + invNo.trim().replace('\n', ' ') : "");
      if (apMemo.length() > 280) apMemo = apMemo.substring(0, 280);
      drafts.add(
          new PostingLineDraft(
              vendorAp.getCode(), null, payable, apMemo,
              PartyType.VENDOR, inv.vendorId().trim()));
    }

    String methodLabel = StringUtils.hasText(inv.paymentMethod())
        ? " · " + inv.paymentMethod().trim() : "";
    String desc =
        "Purchase/stock-in"
            + (StringUtils.hasText(invNo) ? " · " + invNo.trim().replace('\n', ' ') : "")
            + methodLabel;

    Instant journalDate =
        inv.invoiceDate() != null ? inv.invoiceDate() : nzInstant(inv.createdAt());

    var journal =
        postingService.postJournal(
            shopId,
            journalDate,
            desc.trim(),
            drafts,
            userId,
            PURCHASE_SOURCE_PREFIX + inv.invoiceMongoId(),
            JournalPostingSource.PURCHASE);

    return Optional.ofNullable(journal.getId());
  }

  private static BigDecimal unitPurchasePrice(VendorPurchaseLedgerInput.Line ln) {
    if (ln.costPrice() != null && ln.costPrice().signum() > 0) {
      return ln.costPrice();
    }
    if (ln.priceToRetail() != null && ln.priceToRetail().signum() > 0) {
      return ln.priceToRetail();
    }
    return null;
  }

  /** Line-level purchase value ≈ qty × (cost or PTR), same convention as OCR / registration UI. */
  private static BigDecimal sumLineCostValues(List<VendorPurchaseLedgerInput.Line> lines) {
    BigDecimal s = BigDecimal.ZERO.setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
    for (VendorPurchaseLedgerInput.Line ln : lines) {
      if (ln.count() == null || ln.count() <= 0) {
        continue;
      }
      BigDecimal unit = unitPurchasePrice(ln);
      if (unit == null) {
        continue;
      }
      BigDecimal amt =
          BigDecimal.valueOf(ln.count().longValue())
              .multiply(unit)
              .setScale(PostingService.MONEY_SCALE, RoundingMode.HALF_UP);
      s = s.add(amt);
    }
    return s;
  }

  private void warnSkipNoBasis(
      String shopId,
      String invoiceId,
      BigDecimal sumLines,
      BigDecimal lineSub,
      BigDecimal invoiceTotalFilled) {
    log.warn(
        "Skipping PURCHASE journal (shop={}, invoice={}): no positive payable "
            + "(lines sum={}, header lineSubTotal={}, invoiceTotal={}). "
            + "Ensure cost or PTR × qty > 0, or fill invoice totals.",
        shopId,
        invoiceId,
        sumLines,
        lineSub,
        invoiceTotalFilled);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static Instant nzInstant(Instant i) {
    return i != null ? i : Instant.now();
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
