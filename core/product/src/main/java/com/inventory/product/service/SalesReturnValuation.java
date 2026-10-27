package com.inventory.product.service;

import com.inventory.common.util.GstMath;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.utils.CheckoutUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.util.StringUtils;

/** Computes taxable, tax, COGS, and refund tender splits for customer returns. */
final class SalesReturnValuation {

  private SalesReturnValuation() {}

  record LineAmounts(
      BigDecimal taxable,
      BigDecimal cgst,
      BigDecimal sgst,
      BigDecimal igst,
      BigDecimal cogs,
      BigDecimal lineTotal) {}

  record AmountTotals(
      BigDecimal taxableTotal,
      BigDecimal cgstTotal,
      BigDecimal sgstTotal,
      BigDecimal igstTotal,
      BigDecimal cogsTotal,
      BigDecimal returnTotal,
      BigDecimal roundOff) {}

  /**
   * A returned line's taxable value and tax. A return reverses the sale's tax under the head the
   * sale charged it: IGST at the combined rate when the sale was interstate, else CGST and SGST.
   * A line sold at MRP reverses the GST inside its price (see {@link #atMrpLineAmounts}), but
   * only when {@code atMrpGstStated}: the sale stated that GST ({@link #statedAtMrpGst}). A sale
   * that never stated it keeps the old return, zero GST and the whole amount taxable.
   *
   * <p>This form is for a line nothing of which was returned before; see the form taking
   * {@code returnedBeforeBaseQty}.
   */
  static LineAmounts lineAmounts(
      PurchaseItem purchaseItem,
      int refundBaseQty,
      int refundDisplayQty,
      BillingMode billingMode,
      boolean interstate,
      boolean atMrpGstStated) {
    return lineAmounts(
        purchaseItem, refundBaseQty, refundDisplayQty, billingMode, interstate, atMrpGstStated, 0);
  }

  /**
   * As {@link #lineAmounts(PurchaseItem, int, int, BillingMode, boolean, boolean)}, for a line of
   * which {@code returnedBeforeBaseQty} base units were taken back by earlier returns. Every
   * amount is the sold line's amount for everything returned so far, this return included, less
   * its amount for what was returned before ({@link #returnedUpTo}). Each is a share of the sold
   * line (its billable quantity × returned ÷ sold), never the scheme worked out again on the
   * returned quantity, and is rounded once. So a line returned in pieces refunds, in total, what
   * returning it whole does, and an at-MRP line reverses head by head the GST the sale stated.
   */
  static LineAmounts lineAmounts(
      PurchaseItem purchaseItem,
      int refundBaseQty,
      int refundDisplayQty,
      BillingMode billingMode,
      boolean interstate,
      boolean atMrpGstStated,
      int returnedBeforeBaseQty) {
    int before = Math.max(returnedBeforeBaseQty, 0);
    LineAmounts upTo =
        returnedUpTo(
            purchaseItem, before + refundBaseQty, before + refundDisplayQty, billingMode,
            interstate, atMrpGstStated);
    if (before == 0) {
      return upTo;
    }
    LineAmounts earlier =
        returnedUpTo(purchaseItem, before, before, billingMode, interstate, atMrpGstStated);
    return new LineAmounts(
        upTo.taxable().subtract(earlier.taxable()),
        upTo.cgst().subtract(earlier.cgst()),
        upTo.sgst().subtract(earlier.sgst()),
        upTo.igst().subtract(earlier.igst()),
        upTo.cogs().subtract(earlier.cogs()),
        upTo.lineTotal().subtract(earlier.lineTotal()));
  }

  /** The amounts for the first {@code baseQty} base units of a sold line, taken back at once. */
  private static LineAmounts returnedUpTo(
      PurchaseItem purchaseItem,
      int baseQty,
      int displayQty,
      BillingMode billingMode,
      boolean interstate,
      boolean atMrpGstStated) {
    BigDecimal billableQty = prorateBillableQuantity(purchaseItem, baseQty, displayQty);
    BigDecimal effectivePrice = CheckoutUtils.getEffectiveSellingPricePerUnit(purchaseItem);
    BigDecimal taxable = effectivePrice.multiply(billableQty).setScale(2, RoundingMode.HALF_UP);
    if (purchaseItem.getSaleAdditionalDiscount() != null
        && purchaseItem.getSaleAdditionalDiscount().compareTo(BigDecimal.ZERO) > 0) {
      taxable =
          taxable
              .multiply(
                  BigDecimal.ONE.subtract(
                      purchaseItem
                          .getSaleAdditionalDiscount()
                          .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
              .setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal cogs = prorateCogs(purchaseItem, baseQty);
    // At MRP the amount above is what the customer paid, GST included.
    if (atMrpGstStated
        && CheckoutUtils.isTaxApplicable(billingMode)
        && CheckoutUtils.isSellingAtMrp(purchaseItem)) {
      return atMrpLineAmounts(purchaseItem, billableQty, taxable, interstate, cogs);
    }

    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    BigDecimal igst = BigDecimal.ZERO;
    if (CheckoutUtils.isTaxApplicableForItem(purchaseItem, billingMode)) {
      BigDecimal cgstRate = GstMath.parseGstRate(purchaseItem.getCgst());
      BigDecimal sgstRate = GstMath.parseGstRate(purchaseItem.getSgst());
      if (interstate) {
        igst = GstMath.taxOnExclusive(taxable, cgstRate.add(sgstRate));
      } else {
        cgst = GstMath.taxOnExclusive(taxable, cgstRate);
        sgst = GstMath.taxOnExclusive(taxable, sgstRate);
      }
    }

    BigDecimal lineTotal = taxable.add(cgst).add(sgst).add(igst).setScale(2, RoundingMode.HALF_UP);
    return new LineAmounts(taxable, cgst, sgst, igst, cogs, lineTotal);
  }

  /**
   * A line sold at MRP had its GST inside the price, and the sale stated it
   * ({@code CheckoutService.calculateTax}). The return reverses that same GST, worked the same
   * way: on the price with the GST taken out, each head rounded once on the whole line, and on an
   * interstate return IGST as the two heads' sum. The refund stays what the customer paid, so the
   * taxable value is the balance: per-head rounding can never move the refund by a paisa.
   *
   * <p>MRP 150 − 5% at 12%: paid 142.50, CGST 7.63, SGST 7.63, taxable 127.24.
   */
  private static LineAmounts atMrpLineAmounts(
      PurchaseItem purchaseItem,
      BigDecimal billableQty,
      BigDecimal paid,
      boolean interstate,
      BigDecimal cogs) {
    BigDecimal base = CheckoutUtils.getTaxableLineValue(purchaseItem, billableQty);
    BigDecimal cgst = CheckoutUtils.gstHeadAmount(base, purchaseItem.getCgst());
    BigDecimal sgst = CheckoutUtils.gstHeadAmount(base, purchaseItem.getSgst());
    BigDecimal igst = BigDecimal.ZERO.setScale(2);
    if (interstate) {
      igst = cgst.add(sgst);
      cgst = BigDecimal.ZERO.setScale(2);
      sgst = BigDecimal.ZERO.setScale(2);
    }
    BigDecimal taxable = paid.subtract(cgst).subtract(sgst).subtract(igst);
    return new LineAmounts(taxable, cgst, sgst, igst, cogs, paid);
  }

  /**
   * Whether a sale stated the GST inside its at-MRP lines. Sales made before #229 did not: their
   * header, journal and invoice charged those lines no GST, so a return must not reverse any.
   * No line field records this, so the evidence is the sale header. It holds the GST of the
   * below-MRP lines (on top of the price) plus, since #229, the GST inside the at-MRP lines,
   * each worked by {@link CheckoutUtils#getTaxableLineValue} and {@link
   * CheckoutUtils#gstHeadAmount}. The sale stated its at-MRP GST when the header tax left after
   * the below-MRP lines' GST covers at least half of the at-MRP lines' GST; before #229 that
   * remainder is zero. Half, not all, so a paisa of rounding cannot decide it.
   *
   * <p>A sale with no at-MRP GST to state counts as stated: its at-MRP split is zero either way.
   * Rates are worked as {@code CheckoutService.calculateTax} does: a below-MRP line without its
   * own rate is taxed at the shop's default ({@code shopCgst}, {@code shopSgst}; null when the
   * shop has none), an at-MRP line only at its own.
   */
  static boolean statedAtMrpGst(Purchase sale, String shopCgst, String shopSgst) {
    if (sale == null || sale.getItems() == null) {
      return false;
    }
    BigDecimal atMrpGst = BigDecimal.ZERO;
    BigDecimal belowMrpGst = BigDecimal.ZERO;
    for (PurchaseItem item : sale.getItems()) {
      if (item == null
          || item.getMaximumRetailPrice() == null
          || item.getQuantity() == null
          || item.getPriceToRetail() == null) {
        continue;
      }
      boolean atMrp = CheckoutUtils.isSellingAtMrp(item);
      String cgstRate = StringUtils.hasText(item.getCgst()) ? item.getCgst() : (atMrp ? null : shopCgst);
      String sgstRate = StringUtils.hasText(item.getSgst()) ? item.getSgst() : (atMrp ? null : shopSgst);
      BigDecimal value =
          CheckoutUtils.getTaxableLineValue(item, CheckoutUtils.getBillableQuantityAsDecimal(item));
      BigDecimal gst = BigDecimal.ZERO;
      if (StringUtils.hasText(cgstRate)) {
        gst = gst.add(CheckoutUtils.gstHeadAmount(value, cgstRate));
      }
      if (StringUtils.hasText(sgstRate)) {
        gst = gst.add(CheckoutUtils.gstHeadAmount(value, sgstRate));
      }
      if (atMrp) {
        atMrpGst = atMrpGst.add(gst);
      } else {
        belowMrpGst = belowMrpGst.add(gst);
      }
    }
    if (atMrpGst.signum() <= 0) {
      return true;
    }
    BigDecimal headerTax = headerTax(sale);
    BigDecimal halfAtMrp = atMrpGst.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    return headerTax.subtract(belowMrpGst).compareTo(halfAtMrp) >= 0;
  }

  /** The sale header's GST: taxTotal, else CGST + SGST + IGST; zero when none is stored. */
  private static BigDecimal headerTax(Purchase sale) {
    if (sale.getTaxTotal() != null) {
      return sale.getTaxTotal();
    }
    BigDecimal tax = BigDecimal.ZERO;
    for (BigDecimal head : new BigDecimal[] {
        sale.getCgstAmount(), sale.getSgstAmount(), sale.getIgstAmount()}) {
      if (head != null) {
        tax = tax.add(head);
      }
    }
    return tax;
  }

  static AmountTotals aggregate(List<LineAmounts> lines) {
    BigDecimal taxable =
        lines.stream().map(LineAmounts::taxable).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cgst = lines.stream().map(LineAmounts::cgst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sgst = lines.stream().map(LineAmounts::sgst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal igst = lines.stream().map(LineAmounts::igst).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cogs = lines.stream().map(LineAmounts::cogs).reduce(BigDecimal.ZERO, BigDecimal::add);

    taxable = taxable.setScale(2, RoundingMode.HALF_UP);
    cgst = cgst.setScale(2, RoundingMode.HALF_UP);
    sgst = sgst.setScale(2, RoundingMode.HALF_UP);
    igst = igst.setScale(2, RoundingMode.HALF_UP);
    cogs = cogs.setScale(2, RoundingMode.HALF_UP);

    BigDecimal preRound = taxable.add(cgst).add(sgst).add(igst).setScale(2, RoundingMode.HALF_UP);
    BigDecimal returnTotal = roundToWholeRupee(preRound);
    BigDecimal roundOff = returnTotal.subtract(preRound).setScale(4, RoundingMode.HALF_UP);

    return new AmountTotals(taxable, cgst, sgst, igst, cogs, returnTotal, roundOff);
  }

  private static BigDecimal prorateBillableQuantity(
      PurchaseItem purchaseItem, int refundBaseQty, int refundDisplayQty) {
    int purchasedBase = purchaseItem.getBaseQuantity() != null ? purchaseItem.getBaseQuantity() : 0;
    if (purchasedBase <= 0) {
      return BigDecimal.valueOf(Math.max(refundDisplayQty, 0));
    }
    BigDecimal ratio =
        BigDecimal.valueOf(refundBaseQty)
            .divide(BigDecimal.valueOf(purchasedBase), 8, RoundingMode.HALF_UP);
    return CheckoutUtils.getBillableQuantityAsDecimal(purchaseItem).multiply(ratio);
  }

  private static BigDecimal prorateCogs(PurchaseItem purchaseItem, int refundBaseQty) {
    int purchasedBase = purchaseItem.getBaseQuantity() != null ? purchaseItem.getBaseQuantity() : 0;
    if (purchaseItem.getCostTotal() != null && purchasedBase > 0) {
      return purchaseItem
          .getCostTotal()
          .multiply(BigDecimal.valueOf(refundBaseQty))
          .divide(BigDecimal.valueOf(purchasedBase), 2, RoundingMode.HALF_UP);
    }
    if (purchaseItem.getCostPrice() != null && refundBaseQty > 0) {
      BigDecimal displayQty =
          BigDecimal.valueOf(refundBaseQty)
              .divide(
                  BigDecimal.valueOf(
                      purchaseItem.getUnitFactor() != null && purchaseItem.getUnitFactor() > 0
                          ? purchaseItem.getUnitFactor()
                          : 1),
                  4,
                  RoundingMode.HALF_UP);
      return purchaseItem.getCostPrice().multiply(displayQty).setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
  }


  private static BigDecimal roundToWholeRupee(BigDecimal amount) {
    if (amount == null) return BigDecimal.ZERO;
    return amount.setScale(0, RoundingMode.HALF_UP);
  }

}
