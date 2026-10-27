package com.inventory.product.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.common.constants.PurchaseTaxTreatment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurchaseTaxBasisResolverTest {

  private final Map<String, Pricing> pricing = new HashMap<>();
  private final List<VendorPurchaseInvoiceLine> lines = new ArrayList<>();

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

  /** A line of {@code count} at list {@code cost}, taxed at {@code halfGst} each side. */
  private void line(int count, String cost, String halfGst, String schemePct, String additional) {
    String inventoryId = "inv-" + lines.size();
    VendorPurchaseInvoiceLine line = new VendorPurchaseInvoiceLine();
    line.setLineIndex(lines.size());
    line.setCount(count);
    line.setCostPrice(bd(cost));
    line.setInventoryId(inventoryId);
    lines.add(line);

    Pricing p = new Pricing();
    p.setCostPrice(bd(cost));
    p.setCgst(halfGst);
    p.setSgst(halfGst);
    if (schemePct != null) {
      Scheme scheme = new Scheme();
      scheme.setSchemeType("PERCENTAGE");
      scheme.setSchemePercentage(bd(schemePct));
      p.setPurchaseScheme(scheme);
    }
    if (additional != null) {
      p.setPurchaseAdditionalDiscount(bd(additional));
    }
    pricing.put(inventoryId, p);
  }

  /** A line of {@code count} at list {@code cost} under a "{@code payFor}+{@code free}" deal. */
  private void dealLine(int count, String cost, String halfGst, int payFor, int free,
      String additional) {
    line(count, cost, halfGst, null, additional);
    Scheme scheme = new Scheme();
    scheme.setSchemeType("FIXED_UNITS");
    scheme.setSchemePayFor(payFor);
    scheme.setSchemeFree(free);
    pricing.get(lines.get(lines.size() - 1).getInventoryId()).setPurchaseScheme(scheme);
  }

  private PurchaseTaxBasis resolve(
      String overallDiscount, PurchaseTaxTreatment treatment, boolean interstate) {
    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    invoice.setOverallDiscount(overallDiscount == null ? null : bd(overallDiscount));
    invoice.setLines(lines);
    return PurchaseTaxBasisResolver.resolve(invoice, pricing::get, treatment, interstate);
  }

  private static BigDecimal central(PurchaseTaxBasis basis) {
    return basis.lines().stream().map(PurchaseTaxBasis.Line::centralTax)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal state(PurchaseTaxBasis basis) {
    return basis.lines().stream().map(PurchaseTaxBasis.Line::stateTax)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void exclusiveLinesHaveTheTaxAddedOnTop() {
    line(4, "250", "9", null, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("1000.00", basis.totalTaxable());
    assertMoney("180.00", basis.totalTax());
    assertMoney("90.00", central(basis));
    assertMoney("90.00", state(basis));
  }

  @Test
  void theSchemeAndAdditionalDiscountComeOffTheLine() {
    line(10, "100", "2.5", "5", "10");

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    // 100 less 5% scheme less 10% discount = 85.50 a unit.
    assertMoney("855.00", basis.totalTaxable());
    assertMoney("42.76", basis.totalTax());
  }

  /**
   * MAHAMAYA A012392: a "19+1" deal is one unit in twenty off the price, so it comes off the line
   * before the trade discount. Each line is the bill's own: 20 × 121.90 less 19+1 less 8% is
   * 2130.81. The paper prints 7050.95 for the three because it rounds the subtotal and the discount
   * column separately; the lines themselves add to 7050.94.
   */
  @Test
  void aDealRatioComesOffTheLine() {
    dealLine(20, "121.90", "2.5", 19, 1, "8");
    dealLine(15, "192.76", "2.5", 19, 1, "9");
    dealLine(15, "186.66", "2.5", 19, 1, "9");

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("2130.81", basis.lines().get(0).taxable());
    assertMoney("2499.62", basis.lines().get(1).taxable());
    assertMoney("2420.51", basis.lines().get(2).taxable());
    assertMoney("176.27", central(basis));
    assertMoney("176.27", state(basis));
  }

  /** 540 bought and 60 free, entered as 600 received under 9+1: tax is on the 540 paid for. */
  @Test
  void freeUnitsReceivedAreNotTaxed() {
    dealLine(600, "10", "6", 9, 1, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("5400.00", basis.totalTaxable());
    assertMoney("648.00", basis.totalTax());
  }

  /** PARAS A00000999: 36 at 99, 24% scheme, rates including 5% GST. */
  @Test
  void inclusiveLinesHaveTheirTaxTakenOut() {
    line(36, "99", "2.5", "24", null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.INCLUSIVE, false);

    assertMoney("2579.66", basis.totalTaxable());
    assertMoney("128.98", basis.totalTax());
    assertMoney("64.49", central(basis));
    assertMoney("64.49", state(basis));
  }

  @Test
  void aBillLevelDiscountIsSharedAcrossTheLinesBeforeTax() {
    line(10, "100", "2.5", null, null);
    line(10, "100", "9", null, null);

    PurchaseTaxBasis basis = resolve("200", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("900.00", basis.lines().get(0).taxable());
    assertMoney("900.00", basis.lines().get(1).taxable());
    assertMoney("207.00", basis.totalTax());
    assertMoney("200.00", basis.overallDiscount());
  }

  @Test
  void aDiscountAsLargeAsTheLinesIsIgnored() {
    line(1, "100", "9", null, null);

    PurchaseTaxBasis basis = resolve("100", PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("100.00", basis.totalTaxable());
    assertMoney("0", basis.overallDiscount());
  }

  @Test
  void interstateSupplyIsAllIntegratedTax() {
    line(10, "100", "2.5", null, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, true);

    assertMoney("50.00", basis.lines().get(0).integratedTax());
    assertMoney("0", central(basis));
    assertMoney("0", state(basis));
  }

  @Test
  void aZeroRatedLineCarriesNoTax() {
    line(3, "33.33", "9", null, null);
    line(1, "50", "0", null, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertMoney("0", basis.lines().get(1).tax());
    assertMoney("18.00", basis.totalTax());
  }

  @Test
  void theJournalSplitIsTheLinesSplit() {
    line(10, "100", "6", null, null);
    line(4, "250", "2.5", null, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);
    PurchaseTaxBasis.IntraStateSplit split = basis.splitStated(basis.totalTax()).orElseThrow();

    assertMoney(central(basis).toPlainString(), split.centralTax());
    assertMoney("170.00", split.centralTax().add(split.stateTax()));
  }

  @Test
  void aBasisWithNoRatesGivesNoSplit() {
    line(1, "100", "0", null, null);

    PurchaseTaxBasis basis = resolve(null, PurchaseTaxTreatment.EXCLUSIVE, false);

    assertEquals(java.util.Optional.empty(), basis.splitStated(bd("18.00")));
  }
}
