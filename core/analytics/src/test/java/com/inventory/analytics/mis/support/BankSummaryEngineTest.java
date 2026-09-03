package com.inventory.analytics.mis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.analytics.mis.rest.dto.MisBankSummaryRowDto;
import com.inventory.analytics.mis.rest.dto.MisBankSummaryTotalsDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.InventoryCorrection;
import com.inventory.product.domain.model.InventoryCorrectionLine;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.model.VendorPurchaseInvoiceLine;
import com.inventory.product.domain.model.VendorPurchaseReturn;
import com.inventory.product.domain.model.VendorPurchaseReturnItem;
import com.inventory.product.domain.model.enums.InventoryCorrectionLineStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankSummaryEngineTest {

  private static final Instant JUL_START = instant("2026-07-01");
  private static final Instant AUG_START = instant("2026-08-01");
  private static final Instant SEP_START = instant("2026-09-01");
  private static final Instant JUN_15 = instant("2026-06-15");
  private static final Instant JUL_10 = instant("2026-07-10");
  private static final Instant JUL_20 = instant("2026-07-20");

  @Test
  @DisplayName("every row and the total foot: closing = opening + purchase - sale + adjustment")
  void rowsAndTotalsFoot() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 15, JUN_15);
    Inventory cipla = lot("lot-2", "CIPLA", "50.00", 1, 4, 4, JUN_15);

    BankSummaryEngine.Movements movements =
        movements(
            List.of(),
            List.of(sale(JUL_10, saleLine("lot-1", 5, "500.00", "ABBOT"))),
            List.of(),
            List.of(),
            List.of());

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(List.of(abbot, cipla), movements, null, JUL_START, AUG_START);

    for (MisBankSummaryRowDto row : result.rows()) {
      assertThat(foots(row)).as("row %s foots", row.getCompany()).isTrue();
    }
    MisBankSummaryTotalsDto t = result.totals();
    assertThat(
            t.getOpening().add(t.getPurchase()).subtract(t.getSale()).add(t.getAdjustment()))
        .isEqualByComparingTo(t.getClosing());
  }

  @Test
  @DisplayName("opening is reconstructed from live stock when no prior period was closed")
  void derivesOpeningByRollingBackwards() {
    // 10 units on hand now at 100 each. 5 were sold during July, so July opened with 15.
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 15, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(List.of(), List.of(sale(JUL_10, saleLine("lot-1", 5, "500.00", "ABBOT"))),
                List.of(), List.of(), List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getOpening()).isEqualByComparingTo("1500.00");
    assertThat(row.getSale()).isEqualByComparingTo("500.00");
    assertThat(row.getClosing()).isEqualByComparingTo("1000.00");
  }

  @Test
  @DisplayName("a receipt dated in the period lands in purchase and lifts closing")
  void receiptInPeriodCountsAsPurchase() {
    // Lot received 10 Jul, so its whole received value is July's purchase.
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 12, 12, JUL_10);
    VendorPurchaseInvoice invoice = invoice(JUL_10, "lot-1", 12, "100.00");

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(List.of(invoice), List.of(), List.of(), List.of(), List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getOpening()).isEqualByComparingTo("0.00");
    assertThat(row.getPurchase()).isEqualByComparingTo("1200.00");
    assertThat(row.getClosing()).isEqualByComparingTo("1200.00");
  }

  @Test
  @DisplayName("this period's closing becomes next period's opening, company for company")
  void closingCarriesForwardIntoNextOpening() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 15, JUN_15);
    Inventory cipla = lot("lot-2", "CIPLA", "50.00", 1, 6, 6, JUN_15);

    BankSummaryEngine.Result july =
        BankSummaryEngine.compute(
            List.of(abbot, cipla),
            movements(List.of(), List.of(sale(JUL_10, saleLine("lot-1", 5, "500.00", "ABBOT"))),
                List.of(), List.of(), List.of()),
            null,
            JUL_START,
            AUG_START);

    Map<String, BigDecimal> closed = BankSummaryEngine.closingByCompany(july.rows());

    BankSummaryEngine.Result august =
        BankSummaryEngine.compute(
            List.of(abbot, cipla),
            BankSummaryEngine.Movements.empty(),
            closed,
            AUG_START,
            SEP_START);

    for (MisBankSummaryRowDto augustRow : august.rows()) {
      assertThat(augustRow.getOpening())
          .as("August opening for %s", augustRow.getCompany())
          .isEqualByComparingTo(closed.get(augustRow.getCompany()));
    }
    assertThat(august.totals().getOpening()).isEqualByComparingTo(july.totals().getClosing());
  }

  @Test
  @DisplayName("an approved correction moves closing by exactly its own value")
  void correctionMovesClosingByItsDelta() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 10, JUN_15);
    // Stock counted up from 8 to 10 on 20 Jul: a +2 unit, +200.00 adjustment.
    InventoryCorrection correction = correction("lot-1", 8, 10, JUL_20, InventoryCorrectionLineStatus.APPROVED);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(List.of(), List.of(), List.of(), List.of(), List.of(correction)),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getAdjustment()).isEqualByComparingTo("200.00");
    assertThat(row.getOpening()).isEqualByComparingTo("800.00");
    assertThat(row.getClosing()).isEqualByComparingTo("1000.00");
    assertThat(result.hasAdjustments()).isTrue();
  }

  @Test
  @DisplayName("a rejected correction is not a stock movement and changes nothing")
  void rejectedCorrectionIsIgnored() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 10, JUN_15);
    InventoryCorrection correction = correction("lot-1", 8, 10, JUL_20, InventoryCorrectionLineStatus.REJECTED);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(List.of(), List.of(), List.of(), List.of(), List.of(correction)),
            null,
            JUL_START,
            AUG_START);

    assertThat(only(result.rows()).getAdjustment()).isEqualByComparingTo("0.00");
    assertThat(result.hasAdjustments()).isFalse();
  }

  @Test
  @DisplayName("a sales return nets off the sale column and lifts closing back")
  void salesReturnReducesSale() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 7, 10, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(
                List.of(),
                List.of(sale(JUL_10, saleLine("lot-1", 5, "500.00", "ABBOT"))),
                List.of(refund(JUL_20, refundLine("lot-1", 2, "200.00"))),
                List.of(),
                List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getSale()).isEqualByComparingTo("300.00");
    assertThat(row.getOpening()).isEqualByComparingTo("1000.00");
    assertThat(row.getClosing()).isEqualByComparingTo("700.00");
  }

  @Test
  @DisplayName("a purchase return nets off the purchase column")
  void purchaseReturnReducesPurchase() {
    // 10 received on 10 Jul, 2 sent back on 20 Jul, so 8 are on hand now.
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 8, 10, JUL_10);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(
                List.of(invoice(JUL_10, "lot-1", 10, "100.00")),
                List.of(),
                List.of(),
                List.of(vendorReturn(JUL_20, "lot-1", 2, "200.00")),
                List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getPurchase()).isEqualByComparingTo("800.00");
    assertThat(row.getClosing()).isEqualByComparingTo("800.00");
  }

  @Test
  @DisplayName("movements after the period end stay out of it but still roll opening back")
  void movementsAfterPeriodAreExcludedFromColumns() {
    // Sold 5 in July and 3 more in August; only July's 5 belong in July's sale column,
    // but both have to be undone to get back to what July opened with.
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 7, 15, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(
                List.of(),
                List.of(
                    sale(JUL_10, saleLine("lot-1", 5, "500.00", "ABBOT")),
                    sale(instant("2026-08-05"), saleLine("lot-1", 3, "300.00", "ABBOT"))),
                List.of(),
                List.of(),
                List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getSale()).isEqualByComparingTo("500.00");
    assertThat(row.getOpening()).isEqualByComparingTo("1500.00");
    assertThat(row.getClosing()).isEqualByComparingTo("1000.00");
  }

  @Test
  @DisplayName("a sale whose lot is gone is still attributed, from the company it snapshotted")
  void deletedLotFallsBackToLineSnapshot() {
    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(),
            movements(
                List.of(),
                List.of(sale(JUL_10, saleLine("lot-gone", 5, "250.00", "ORPHAN CO"))),
                List.of(),
                List.of(),
                List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getCompany()).isEqualTo("ORPHAN CO");
    assertThat(row.getSale()).isEqualByComparingTo("250.00");
    assertThat(row.getOpening()).isEqualByComparingTo("250.00");
    assertThat(row.getClosing()).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("a lot with no company name is bucketed rather than dropped")
  void lotWithoutCompanyIsBucketed() {
    Inventory unnamed = lot("lot-1", null, "100.00", 1, 3, 3, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(unnamed), BankSummaryEngine.Movements.empty(), null, JUL_START, AUG_START);

    assertThat(only(result.rows()).getCompany()).isEqualTo(BankSummaryEngine.UNCLASSIFIED);
  }

  @Test
  @DisplayName("pack conversion: sale quantities are base units, lot cost is per pack")
  void convertsPackCostToBaseUnits() {
    // A strip of 10 tablets costs 100, so a tablet costs 10. Selling 30 tablets is 300.
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 10, 70, 100, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(List.of(), List.of(sale(JUL_10, saleLine("lot-1", 30, "300.00", "ABBOT"))),
                List.of(), List.of(), List.of()),
            null,
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getSale()).isEqualByComparingTo("300.00");
    assertThat(row.getOpening()).isEqualByComparingTo("1000.00");
    assertThat(row.getClosing()).isEqualByComparingTo("700.00");
  }

  @Test
  @DisplayName("a shop with no stock and no movement reports nothing, not an error")
  void emptyShopIsEmpty() {
    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(), BankSummaryEngine.Movements.empty(), null, JUL_START, AUG_START);

    assertThat(result.rows()).isEmpty();
    assertThat(result.totals().getCompanyCount()).isZero();
    assertThat(result.totals().getClosing()).isEqualByComparingTo("0.00");
    assertThat(result.hasAdjustments()).isFalse();
  }

  @Test
  @DisplayName("a company that neither held nor moved stock is not printed")
  void allZeroCompaniesAreDropped() {
    Inventory empty = lot("lot-1", "SOLD OUT CO", "100.00", 1, 0, 0, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(empty), BankSummaryEngine.Movements.empty(), null, JUL_START, AUG_START);

    assertThat(result.rows()).isEmpty();
  }

  @Test
  @DisplayName("rows come out sorted by company, as the printed report reads")
  void rowsAreSortedByCompany() {
    List<Inventory> lots =
        List.of(
            lot("lot-1", "ZYDUS", "10.00", 1, 1, 1, JUN_15),
            lot("lot-2", "ABBOT", "10.00", 1, 1, 1, JUN_15),
            lot("lot-3", "cipla", "10.00", 1, 1, 1, JUN_15));

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(lots, BankSummaryEngine.Movements.empty(), null, JUL_START, AUG_START);

    assertThat(result.rows().stream().map(MisBankSummaryRowDto::getCompany))
        .containsExactly("ABBOT", "cipla", "ZYDUS");
  }

  @Test
  @DisplayName("a snapshot opening is used verbatim, not recomputed from live stock")
  void snapshotOpeningWins() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 10, JUN_15);

    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            BankSummaryEngine.Movements.empty(),
            Map.of("ABBOT", new BigDecimal("777.00")),
            JUL_START,
            AUG_START);

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getOpening()).isEqualByComparingTo("777.00");
    assertThat(row.getClosing()).isEqualByComparingTo("777.00");
  }


  @Test
  @DisplayName("the chain runs month after month, including across the year boundary")
  void chainsAcrossManyMonths() {
    // One lot, 100 units at 10.00 = 1000.00, two sold every month from September on.
    Inventory abbot = lot("lot-1", "ABBOT", "10.00", 1, 100, 100, instant("2026-08-15"));

    String[][] months = {
      {"2026-09-01", "2026-10-01"},
      {"2026-10-01", "2026-11-01"},
      {"2026-11-01", "2026-12-01"},
      {"2026-12-01", "2027-01-01"}, // December, closing into a new year
      {"2027-01-01", "2027-02-01"},
      {"2027-02-01", "2027-03-01"}, // February, a short month
      {"2027-03-01", "2027-04-01"}, // Indian financial year end
    };

    Map<String, BigDecimal> carried = null;
    BigDecimal previousClosing = null;

    for (String[] month : months) {
      Instant start = instant(month[0]);
      Instant endExclusive = instant(month[1]);

      BankSummaryEngine.Result result =
          BankSummaryEngine.compute(
              List.of(abbot),
              movements(
                  List.of(),
                  List.of(sale(start.plusSeconds(86_400), saleLine("lot-1", 2, "20.00", "ABBOT"))),
                  List.of(),
                  List.of(),
                  List.of()),
              carried,
              start,
              endExclusive);

      MisBankSummaryRowDto row = only(result.rows());
      if (previousClosing != null) {
        assertThat(row.getOpening())
            .as("%s opens where the previous month closed", month[0])
            .isEqualByComparingTo(previousClosing);
      }
      assertThat(foots(row)).as("%s foots", month[0]).isTrue();

      previousClosing = row.getClosing();
      carried = BankSummaryEngine.closingByCompany(result.rows());
    }

    // September has no prior close, so it derives its opening: 100 live units plus the 2
    // sold that month, or 1020.00. Every later month then opens from the stored close, and
    // each drops 20.00. 1020.00 - 7 x 20.00 = 880.00.
    assertThat(previousClosing).isEqualByComparingTo("880.00");
  }

  @Test
  @DisplayName("a month that was never closed falls back to derivation instead of breaking")
  void unclosedMonthFallsBackToDerivation() {
    Inventory abbot = lot("lot-1", "ABBOT", "100.00", 1, 10, 20, instant("2026-08-15"));

    // December, with no November close to carry forward: opening is reconstructed from
    // live stock by undoing the ten units sold since 1 Dec.
    BankSummaryEngine.Result result =
        BankSummaryEngine.compute(
            List.of(abbot),
            movements(
                List.of(),
                List.of(sale(instant("2026-12-10"), saleLine("lot-1", 10, "1000.00", "ABBOT"))),
                List.of(),
                List.of(),
                List.of()),
            null,
            instant("2026-12-01"),
            instant("2027-01-01"));

    MisBankSummaryRowDto row = only(result.rows());
    assertThat(row.getOpening()).isEqualByComparingTo("2000.00");
    assertThat(row.getClosing()).isEqualByComparingTo("1000.00");
    assertThat(foots(row)).isTrue();
  }

  @Test
  @DisplayName("a company that first trades in a later month joins the report then")
  void newCompanyAppearsInALaterMonth() {
    Inventory abbot = lot("lot-1", "ABBOT", "10.00", 1, 10, 10, instant("2026-08-15"));
    Inventory cipla = lot("lot-2", "CIPLA", "20.00", 1, 5, 5, instant("2026-12-10"));

    // November closed with ABBOT only; CIPLA's first stock arrives in December.
    Map<String, BigDecimal> november = Map.of("ABBOT", new BigDecimal("100.00"));

    BankSummaryEngine.Result december =
        BankSummaryEngine.compute(
            List.of(abbot, cipla),
            movements(
                List.of(invoice(instant("2026-12-10"), "lot-2", 5, "20.00")),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            november,
            instant("2026-12-01"),
            instant("2027-01-01"));

    assertThat(december.rows().stream().map(MisBankSummaryRowDto::getCompany))
        .containsExactly("ABBOT", "CIPLA");
    MisBankSummaryRowDto ciplaRow =
        december.rows().stream().filter(r -> r.getCompany().equals("CIPLA")).findFirst().orElseThrow();
    assertThat(ciplaRow.getOpening()).isEqualByComparingTo("0.00");
    assertThat(ciplaRow.getPurchase()).isEqualByComparingTo("100.00");
    assertThat(ciplaRow.getClosing()).isEqualByComparingTo("100.00");
  }

  // --- helpers ---

  private static boolean foots(MisBankSummaryRowDto row) {
    return row.getOpening()
            .add(row.getPurchase())
            .subtract(row.getSale())
            .add(row.getAdjustment())
            .compareTo(row.getClosing())
        == 0;
  }

  private static MisBankSummaryRowDto only(List<MisBankSummaryRowDto> rows) {
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private static Instant instant(String isoDate) {
    return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.systemDefault()).toInstant();
  }

  private static BankSummaryEngine.Movements movements(
      List<VendorPurchaseInvoice> invoices,
      List<Purchase> sales,
      List<Refund> refunds,
      List<VendorPurchaseReturn> vendorReturns,
      List<InventoryCorrection> corrections) {
    return new BankSummaryEngine.Movements(invoices, sales, refunds, vendorReturns, corrections);
  }

  private static Inventory lot(
      String id,
      String company,
      String costPrice,
      int factor,
      int currentBase,
      int receivedBase,
      Instant receivedDate) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setShopId("shop-1");
    inv.setCompanyName(company);
    inv.setCostPrice(new BigDecimal(costPrice));
    inv.setCurrentBaseCount(currentBase);
    inv.setReceivedBaseCount(receivedBase);
    inv.setReceivedDate(receivedDate);
    if (factor > 1) {
      inv.setUnitConversions(new com.inventory.product.domain.model.UnitConversion("STRIP", factor));
    }
    return inv;
  }

  private static Purchase sale(Instant soldAt, PurchaseItem... items) {
    Purchase purchase = new Purchase();
    purchase.setShopId("shop-1");
    purchase.setSoldAt(soldAt);
    purchase.setItems(List.of(items));
    return purchase;
  }

  private static PurchaseItem saleLine(
      String inventoryId, int baseQuantity, String costTotal, String company) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef("inventory:" + inventoryId);
    item.setStockRef("inventory:" + inventoryId);
    item.setBaseQuantity(baseQuantity);
    item.setCostTotal(new BigDecimal(costTotal));
    item.setCompanyName(company);
    return item;
  }

  private static Refund refund(Instant createdAt, RefundItem... items) {
    Refund refund = new Refund();
    refund.setShopId("shop-1");
    refund.setCreatedAt(createdAt);
    refund.setRefundedItems(List.of(items));
    return refund;
  }

  private static RefundItem refundLine(String inventoryId, int quantity, String cogsAmount) {
    RefundItem item = new RefundItem();
    item.setInventoryId(inventoryId);
    item.setQuantity(quantity);
    item.setCogsAmount(new BigDecimal(cogsAmount));
    return item;
  }

  private static VendorPurchaseInvoice invoice(
      Instant invoiceDate, String inventoryId, int count, String costPrice) {
    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    invoice.setShopId("shop-1");
    invoice.setInvoiceDate(invoiceDate);
    VendorPurchaseInvoiceLine line = new VendorPurchaseInvoiceLine();
    line.setInventoryId(inventoryId);
    line.setCount(count);
    line.setCostPrice(new BigDecimal(costPrice));
    invoice.setLines(List.of(line));
    return invoice;
  }

  private static VendorPurchaseReturn vendorReturn(
      Instant createdAt, String inventoryId, int baseQuantityReturned, String taxableValue) {
    VendorPurchaseReturn ret = new VendorPurchaseReturn();
    ret.setShopId("shop-1");
    ret.setCreatedAt(createdAt);
    VendorPurchaseReturnItem item = new VendorPurchaseReturnItem();
    item.setInventoryId(inventoryId);
    item.setBaseQuantityReturned(baseQuantityReturned);
    item.setTaxableValue(new BigDecimal(taxableValue));
    ret.setItems(List.of(item));
    return ret;
  }

  private static InventoryCorrection correction(
      String inventoryId,
      int previousBase,
      int requestedBase,
      Instant processedAt,
      InventoryCorrectionLineStatus status) {
    InventoryCorrection correction = new InventoryCorrection();
    correction.setShopId("shop-1");
    InventoryCorrectionLine line = new InventoryCorrectionLine();
    line.setInventoryId(inventoryId);
    line.setPreviousCurrentBaseCount(previousBase);
    line.setRequestedCurrentBaseCount(requestedBase);
    line.setProcessedAt(processedAt);
    line.setStatus(status);
    correction.setLines(List.of(line));
    return correction;
  }
}