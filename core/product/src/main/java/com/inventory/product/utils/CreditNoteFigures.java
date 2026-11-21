package com.inventory.product.utils;

import com.inventory.common.util.GstMath;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The figures a customer credit note states, worked out once for every place that shows one.
 *
 * <p>The printed note and the return history both read these, so the screen and the paper cannot
 * disagree on the same note. Newer refunds carry their totals on the header; older ones only on
 * the lines, and then the lines are summed. A round-off is whatever separates the refund from
 * taxable value plus tax, when the header did not record one.
 */
public final class CreditNoteFigures {

  private CreditNoteFigures() {}

  /**
   * Header totals of one credit note, at 2dp. {@code grandTotal} is the refund amount. A return of
   * an interstate sale carries its tax in {@code igstAmount}; CGST and SGST are then zero.
   */
  public record Totals(
      BigDecimal taxableTotal,
      BigDecimal cgstAmount,
      BigDecimal sgstAmount,
      BigDecimal igstAmount,
      BigDecimal taxTotal,
      BigDecimal roundOff,
      BigDecimal grandTotal) {}

  public static Totals totals(Refund refund, List<RefundItem> lines) {
    BigDecimal taxable = nz(refund.getTaxableTotal());
    BigDecimal cgst = nz(refund.getCgstAmount());
    BigDecimal sgst = nz(refund.getSgstAmount());
    BigDecimal igst = nz(refund.getIgstAmount());
    if (taxable.signum() == 0 && lines != null && !lines.isEmpty()) {
      cgst = BigDecimal.ZERO;
      sgst = BigDecimal.ZERO;
      igst = BigDecimal.ZERO;
      for (RefundItem line : lines) {
        if (line == null) {
          continue;
        }
        taxable = taxable.add(nz(line.getTaxableValue()));
        cgst = cgst.add(nz(line.getCgstAmount()));
        sgst = sgst.add(nz(line.getSgstAmount()));
        igst = igst.add(nz(line.getIgstAmount()));
      }
    }
    BigDecimal grand = nz(refund.getRefundAmount());
    BigDecimal taxTotal = cgst.add(sgst).add(igst);
    BigDecimal roundOff = nz(refund.getRoundOff());
    if (roundOff.signum() == 0 && taxable.signum() > 0) {
      roundOff = grand.subtract(taxable.add(taxTotal));
    }
    return new Totals(
        money(taxable),
        money(cgst),
        money(sgst),
        money(igst),
        money(taxTotal),
        money(roundOff),
        money(grand));
  }

  /** The GST rate a line was billed at: its CGST and SGST rates together, e.g. 2.5 + 2.5 = 5. */
  public static BigDecimal lineRatePct(String cgst, String sgst) {
    return GstMath.parseGstRate(cgst).add(GstMath.parseGstRate(sgst));
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
