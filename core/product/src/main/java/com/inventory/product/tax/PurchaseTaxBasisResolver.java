package com.inventory.product.tax;

import com.inventory.common.tax.GstMath;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides what a supplier invoice is worth for tax.
 *
 * <p>A purchase bill states its figures in whichever way its supplier's software prints them, and
 * the shop keys in what it can. Some bills arrive with a header the operator typed in full, some
 * with no header at all, some with the gross where the taxable value belongs. The lines carry a
 * cost price that may or may not have had the bill's scheme and discount recorded against it. No
 * single one of those is reliable enough to be the answer on its own, so this walks them in order
 * of trust and records which one it landed on.
 *
 * <p>The order deliberately puts a consistent stated header first, ahead of landed cost. The
 * header is the operator reading the supplier's own totals off the paper; landed cost is derived
 * from fields the operator may simply not have filled in, and an unrecorded discount silently
 * inflates it. Where the header proves itself — its subtotal and its tax agree at the rates the
 * lines carry — it is the better evidence, and using it keeps every already-correct invoice
 * reporting exactly what it reports today.
 */
public final class PurchaseTaxBasisResolver {

  /**
   * How far the stated tax may sit from the tax the lines imply before the header is disbelieved.
   *
   * <p>Wide enough to absorb the paisa-level drift of a supplier rounding each line while the
   * shop's total rounds once, narrow enough that a whole missing discount cannot hide inside it.
   */
  private static final BigDecimal ABSOLUTE_TOLERANCE = new BigDecimal("1.00");

  private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.005");

  private PurchaseTaxBasisResolver() {}

  /**
   * Resolves the taxable value and tax of every line on an invoice.
   *
   * @param invoice the supplier invoice, whose lines are read in order
   * @param pricingByInventoryId pricing for each line's lot, giving the rate and any discount
   * @param treatment whether line amounts already contain tax
   * @param interstate true when the supply crosses a state border, so the tax is IGST
   */
  public static PurchaseTaxBasis resolve(
      VendorPurchaseInvoice invoice,
      Function<String, Pricing> pricingByInventoryId,
      PurchaseTaxTreatment treatment,
      boolean interstate) {

    List<VendorPurchaseInvoiceLine> lines =
        invoice.getLines() == null ? List.of() : invoice.getLines();
    if (lines.isEmpty()) {
      return new PurchaseTaxBasis(List.of(), PurchaseTaxBasis.Verdict.MISSING);
    }

    List<BigDecimal> rates = new ArrayList<>(lines.size());
    List<BigDecimal> gross = new ArrayList<>(lines.size());
    List<BigDecimal> landed = new ArrayList<>(lines.size());
    boolean anyLanded = false;

    for (VendorPurchaseInvoiceLine line : lines) {
      Pricing pricing = line.getInventoryId() == null ? null
          : pricingByInventoryId.apply(line.getInventoryId());
      rates.add(rateOf(pricing));

      BigDecimal qty = line.getCount() == null
          ? BigDecimal.ZERO : BigDecimal.valueOf(line.getCount());
      BigDecimal listCost = line.getCostPrice() == null ? BigDecimal.ZERO : line.getCostPrice();
      gross.add(money(listCost.multiply(qty)));

      BigDecimal discounted = discountedUnitCost(pricing);
      if (discounted != null && pricing.getCostPrice() != null
          && discounted.compareTo(pricing.getCostPrice()) < 0) {
        landed.add(money(discounted.multiply(qty)));
        anyLanded = true;
      } else {
        landed.add(null);
      }
    }

    BigDecimal statedSubTotal = invoice.getLineSubTotal();
    BigDecimal statedTax = invoice.getTaxTotal();
    BigDecimal grossSum = sum(gross);

    // 1. A header that agrees with itself. The operator read the supplier's totals off the paper,
    //    and the line rates confirm them, so the figures are used as stated.
    if (statedSubTotal != null && statedTax != null && grossSum.signum() > 0) {
      List<BigDecimal> scaled = prorate(gross, grossSum, statedSubTotal);
      BigDecimal impliedTax = taxOf(scaled, rates);
      if (within(impliedTax, statedTax, statedSubTotal)) {
        return basis(scaled, rates, PurchaseTaxBasis.Source.HEADER_CONSISTENT,
            treatment, interstate, PurchaseTaxBasis.Verdict.OK);
      }

      // 2. The header disagrees with itself. On a single-rate invoice the stated tax is the more
      //    trustworthy half -- a supplier prints it, and it is the figure the credit is claimed
      //    on -- so the taxable value is recovered from it rather than from the subtotal, which
      //    is where a gross-for-net transcription lands.
      Set<BigDecimal> distinctRates = distinct(rates);
      if (distinctRates.size() == 1) {
        BigDecimal rate = distinctRates.iterator().next();
        if (rate.signum() > 0 && statedTax.signum() > 0) {
          BigDecimal derived = money(
              statedTax.multiply(BigDecimal.valueOf(100))
                  .divide(rate, 2, RoundingMode.HALF_UP));
          return basis(prorate(gross, grossSum, derived), rates,
              PurchaseTaxBasis.Source.DERIVED_FROM_TAX, treatment, interstate,
              verdictForInconsistentHeader(statedSubTotal, statedTax, rates));
        }
      }

      // Multi-rate and inconsistent: the stated subtotal is still the best split we have, but the
      // header is flagged so the figures are not mistaken for a reconciled invoice.
      return basis(scaled, rates, PurchaseTaxBasis.Source.HEADER_CONSISTENT, treatment, interstate,
          verdictForInconsistentHeader(statedSubTotal, statedTax, rates));
    }

    // 3. No usable header. The next best basis is the line price after the price reductions the
    //    bill recorded -- a percentage scheme and an additional discount, which is what the
    //    supplier actually took off the charge.
    if (anyLanded) {
      List<BigDecimal> basisValues = new ArrayList<>(lines.size());
      for (int i = 0; i < lines.size(); i++) {
        basisValues.add(landed.get(i) != null ? landed.get(i) : gross.get(i));
      }
      return basis(basisValues, rates, PurchaseTaxBasis.Source.LINE_LANDED, treatment, interstate,
          PurchaseTaxBasis.Verdict.MISSING);
    }

    // 4. Nothing but list prices. Whatever discount the bill gave was never recorded, so this is
    //    gross and reads high -- which is exactly why the verdict says the header is missing.
    return basis(gross, rates, PurchaseTaxBasis.Source.LINE_GROSS, treatment, interstate,
        PurchaseTaxBasis.Verdict.MISSING);
  }

  /**
   * Turns line values into taxable values and tax.
   *
   * <p>Where the amounts are tax-inclusive the tax is taken out of them; otherwise it is added on
   * top. The source is reported as the extraction in the first case, since that is what the caller
   * needs to know to read the figure.
   */
  private static PurchaseTaxBasis basis(
      List<BigDecimal> values, List<BigDecimal> rates, PurchaseTaxBasis.Source source,
      PurchaseTaxTreatment treatment, boolean interstate, PurchaseTaxBasis.Verdict verdict) {

    boolean inclusive = PurchaseTaxTreatment.orDefault(treatment) == PurchaseTaxTreatment.INCLUSIVE;
    List<PurchaseTaxBasis.Line> out = new ArrayList<>(values.size());

    for (int i = 0; i < values.size(); i++) {
      BigDecimal rate = rates.get(i);
      BigDecimal taxable = inclusive
          ? GstMath.extractFromInclusive(values.get(i), rate).taxable()
          : values.get(i);

      BigDecimal integrated = interstate ? GstMath.taxOnExclusive(taxable, rate) : BigDecimal.ZERO;
      GstMath.IntraStateTax halves = interstate
          ? new GstMath.IntraStateTax(BigDecimal.ZERO, BigDecimal.ZERO)
          : GstMath.splitIntraState(taxable, rate);

      out.add(new PurchaseTaxBasis.Line(taxable, rate, halves.centralTax(), halves.stateTax(),
          integrated, inclusive ? PurchaseTaxBasis.Source.INCLUSIVE_EXTRACTED : source));
    }
    return new PurchaseTaxBasis(out, verdict);
  }

  /**
   * Distributes a stated total across lines in proportion to what they are worth.
   *
   * <p>The remainder lands on the last line so the parts add back to the whole -- rounding each
   * share independently would leave the invoice a paisa or two short of the figure the shop
   * actually paid.
   */
  private static List<BigDecimal> prorate(
      List<BigDecimal> values, BigDecimal total, BigDecimal target) {
    List<BigDecimal> scaled = new ArrayList<>(values.size());
    BigDecimal running = BigDecimal.ZERO;
    for (BigDecimal value : values) {
      BigDecimal share = value.multiply(target).divide(total, 2, RoundingMode.HALF_UP);
      scaled.add(share);
      running = running.add(share);
    }
    int last = scaled.size() - 1;
    scaled.set(last, scaled.get(last).add(target.subtract(running)));
    return scaled;
  }

  /**
   * Whether the tax implied by the lines is close enough to the tax the header states.
   *
   * <p>Relative to the invoice, because a rupee's drift on a lakh is rounding and a rupee's drift
   * on fifty is a mistake.
   */
  private static boolean within(BigDecimal implied, BigDecimal stated, BigDecimal subTotal) {
    BigDecimal allowed = subTotal.abs().multiply(RELATIVE_TOLERANCE);
    if (allowed.compareTo(ABSOLUTE_TOLERANCE) < 0) {
      allowed = ABSOLUTE_TOLERANCE;
    }
    return implied.subtract(stated).abs().compareTo(allowed) <= 0;
  }

  /**
   * Tells a mis-keyed total apart from a mis-recorded rate.
   *
   * <p>When the stated subtotal and tax imply a clean GST slab that no line carries, the rate on
   * the goods is what is wrong, not the arithmetic — the operator keyed the bill faithfully and
   * the product is priced at the wrong slab. That is a different repair from a transcription slip,
   * so it is reported as a different verdict.
   */
  private static PurchaseTaxBasis.Verdict verdictForInconsistentHeader(
      BigDecimal statedSubTotal, BigDecimal statedTax, List<BigDecimal> rates) {
    if (statedSubTotal.signum() > 0) {
      BigDecimal impliedRate = statedTax.multiply(BigDecimal.valueOf(100))
          .divide(statedSubTotal, 2, RoundingMode.HALF_UP);
      if (GstMath.isKnownSlab(impliedRate.stripTrailingZeros())
          && distinct(rates).stream().noneMatch(r -> r.compareTo(impliedRate) == 0)) {
        return PurchaseTaxBasis.Verdict.RATE_CONFLICT;
      }
    }
    return PurchaseTaxBasis.Verdict.MISMATCH;
  }

  private static BigDecimal taxOf(List<BigDecimal> taxables, List<BigDecimal> rates) {
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < taxables.size(); i++) {
      total = total.add(GstMath.taxOnExclusive(taxables.get(i), rates.get(i)));
    }
    return total;
  }

  private static Set<BigDecimal> distinct(List<BigDecimal> rates) {
    Set<BigDecimal> out = new LinkedHashSet<>();
    for (BigDecimal rate : rates) {
      if (out.stream().noneMatch(seen -> seen.compareTo(rate) == 0)) {
        out.add(rate);
      }
    }
    return out;
  }

  /**
   * Unit cost after the price reductions that GST recognises — and only those.
   *
   * <p>Deliberately not {@link PricingUtils#computeEffectiveCostPrice}, which is the landed cost:
   * it also dilutes the price by free goods, so a "19+1" bonus makes each unit held cost a
   * twentieth less. That is the right basis for valuation and margin, and the wrong one for tax.
   * A supplier who ships twenty and charges for nineteen has charged for nineteen; the taxable
   * value is what was charged, and the free unit does not reduce it. Applying the landed figure
   * here understated the tax on every bill carrying a bonus.
   *
   * <p>A percentage scheme and an additional discount are genuine reductions in price, so both
   * apply. Returns null when nothing reduces the cost, or when there is no cost to reduce.
   */
  private static BigDecimal discountedUnitCost(Pricing pricing) {
    if (pricing == null || pricing.getCostPrice() == null) {
      return null;
    }
    BigDecimal cost = pricing.getCostPrice();

    Scheme scheme = pricing.getPurchaseScheme();
    if (scheme != null
        && PricingConstants.SCHEME_TYPE_PERCENTAGE.equalsIgnoreCase(scheme.getSchemeType())
        && scheme.getSchemePercentage() != null) {
      BigDecimal pct = scheme.getSchemePercentage();
      if (pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) < 0) {
        cost = cost.multiply(BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100))));
      }
    }

    BigDecimal additional = pricing.getPurchaseAdditionalDiscount();
    if (additional != null && additional.signum() != 0
        && additional.compareTo(BigDecimal.valueOf(100)) < 0) {
      cost = cost.multiply(BigDecimal.ONE.subtract(
          additional.divide(BigDecimal.valueOf(100))));
    }
    return cost.setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal rateOf(Pricing pricing) {
    return pricing == null ? BigDecimal.ZERO
        : GstMath.parseRatePct(pricing.getSgst()).add(GstMath.parseRatePct(pricing.getCgst()));
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
