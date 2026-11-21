package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.RefundItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstDocumentSummaryLine;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.domain.model.GstRefundLine;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A credit note reverses its sale on the same tables the sale was reported on, rate by rate. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Gstr1DataAggregatorCreditNoteTest {

  @Mock private PurchaseRepository purchaseRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private ShopRepository shopRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private HsnSacCatalog hsnSacCatalog;
  @InjectMocks private Gstr1DataAggregator aggregator;

  private final Shop shop = new Shop();
  private final List<Purchase> periodSales = new ArrayList<>();
  private final List<Refund> refunds = new ArrayList<>();
  private final List<Customer> customers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    shop.setGstinNo("10AFBPL7000H1Z8");
    when(shopRepository.findById("s1")).thenReturn(Optional.of(shop));
    when(purchaseRepository.findCompletedPurchasesInPeriod(eq("s1"), any(), any(), any()))
        .thenReturn(periodSales);
    when(refundRepository.findByShopIdAndCreatedAtInPeriod(eq("s1"), any(), any()))
        .thenReturn(refunds);
    when(customerRepository.findAllById(any())).thenReturn(customers);
    when(inventoryRepository.findByIdIn(any())).thenReturn(List.of());
    when(hsnSacCatalog.descriptionFor(anyString())).thenReturn(Optional.empty());
  }

  private static PurchaseItem soldLine(String inventoryId, String hsn, String halfGst, String amount) {
    PurchaseItem item = new PurchaseItem();
    PurchaseItemRefs.applyInventoryLine(item, inventoryId);
    item.setHsn(hsn);
    item.setName("item " + inventoryId);
    item.setQuantity(new BigDecimal("4"));
    item.setCgst(halfGst);
    item.setSgst(halfGst);
    item.setTotalAmount(new BigDecimal(amount));
    return item;
  }

  private Purchase sale(String id, String customerGstin, boolean inPeriod, PurchaseItem... items) {
    Purchase purchase = new Purchase();
    purchase.setId(id);
    purchase.setInvoiceNo(id);
    purchase.setInterstate(false);
    purchase.setSoldAt(Instant.parse(inPeriod ? "2026-10-10T06:00:00Z" : "2026-09-20T06:00:00Z"));
    purchase.setItems(List.of(items));
    BigDecimal grand = BigDecimal.ZERO;
    for (PurchaseItem i : items) grand = grand.add(i.getTotalAmount());
    purchase.setGrandTotal(grand);
    if (customerGstin != null) {
      Customer customer = new Customer();
      customer.setId("c-" + id);
      customer.setName("Buyer " + id);
      customer.setGstin(customerGstin);
      customers.add(customer);
      purchase.setCustomerId(customer.getId());
    }
    if (inPeriod) periodSales.add(purchase);
    when(purchaseRepository.findById(id)).thenReturn(Optional.of(purchase));
    return purchase;
  }

  private static RefundItem returned(String inventoryId, String halfGst, int qty, String amount) {
    RefundItem item = new RefundItem();
    item.setInventoryId(inventoryId);
    item.setName("item " + inventoryId);
    item.setQuantity(qty);
    item.setCgst(halfGst);
    item.setSgst(halfGst);
    item.setItemRefundAmount(new BigDecimal(amount));
    return item;
  }

  private void note(String number, Purchase sale, String value, RefundItem... items) {
    Refund refund = new Refund();
    refund.setId("r-" + number);
    refund.setCreditNoteNo(number);
    refund.setPurchaseId(sale.getId());
    refund.setRefundAmount(new BigDecimal(value));
    refund.setRefundedItems(List.of(items));
    refund.setCreatedAt(Instant.parse("2026-10-15T13:57:43Z"));
    refunds.add(refund);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual);
  }

  @Test
  void aRegisteredBuyersNoteIsReportedPerRateWithItsTax() {
    Purchase sale = sale("T001420", "10BAHPK9801K1ZS", true,
        soldLine("inv-a", "30049011", "2.5", "1050.00"),
        soldLine("inv-b", "33049990", "9", "590.00"));
    note("CN-00003", sale, "640.00",
        returned("inv-a", "2.5", 1, "525.00"),
        returned("inv-b", "9", 1, "118.00"));

    List<GstRefundLine> cdnr = aggregator.buildContext("s1", "2026-10").getCdnrLines();

    assertEquals(2, cdnr.size(), "one line per rate");
    GstRefundLine fivePct = cdnr.get(0);
    assertEquals("CN-00003", fivePct.getNoteNumber());
    assertEquals("10BAHPK9801K1ZS", fivePct.getRecipientGstin());
    assertMoney("500.00", fivePct.getTaxableValue());
    assertMoney("12.50", fivePct.getCentralTaxAmount());
    assertMoney("12.50", fivePct.getStateTaxAmount());
    assertMoney("100.00", cdnr.get(1).getTaxableValue());
    assertMoney("9.00", cdnr.get(1).getCentralTaxAmount());
  }

  @Test
  void aNoteAgainstAnEarlierMonthsSaleStillFindsItsRegisteredBuyer() {
    Purchase september = sale("T001300", "10BAHPK9801K1ZS", false,
        soldLine("inv-a", "30049011", "2.5", "1050.00"));
    note("CN-00004", september, "525.00", returned("inv-a", "2.5", 1, "525.00"));

    Gstr1ReportContext ctx = aggregator.buildContext("s1", "2026-10");

    assertEquals(1, ctx.getCdnrLines().size());
    assertTrue(ctx.getCdnurLines().isEmpty());
  }

  @Test
  void aRegisteredBuyersNoteComesOffTheB2bHsnSummary() {
    Purchase sale = sale("T001420", "10BAHPK9801K1ZS", true,
        soldLine("inv-a", "30049011", "2.5", "1050.00"));
    note("CN-00003", sale, "525.00", returned("inv-a", "2.5", 1, "525.00"));

    List<GstHsnLine> hsn = aggregator.buildContext("s1", "2026-10").getHsnB2bLines();

    assertEquals(1, hsn.size());
    assertMoney("500.00", hsn.get(0).getTaxableValue());
    assertMoney("12.50", hsn.get(0).getCentralTaxAmount());
    assertMoney("3", hsn.get(0).getTotalQuantity());
  }

  @Test
  void anUnregisteredLocalBuyersNoteIsNettedOutOfB2csAndTheB2cHsnSummary() {
    Purchase sale = sale("T001430", null, true, soldLine("inv-a", "30049011", "2.5", "1050.00"));
    note("CN-00005", sale, "210.00", returned("inv-a", "2.5", 1, "210.00"));

    Gstr1ReportContext ctx = aggregator.buildContext("s1", "2026-10");

    assertTrue(ctx.getCdnurLines().isEmpty(), "cdnur is only for B2CL and exports");
    GstInvoiceLine b2cs = ctx.getB2csLines().get(0);
    assertMoney("800.00", b2cs.getTaxableValue());
    assertMoney("20.00", b2cs.getCentralTaxAmount());
    assertMoney("800.00", ctx.getHsnB2cLines().get(0).getTaxableValue());
  }

  @Test
  void aNoteOnALineSoldAtMrpStillReversesItsTax() {
    Purchase sale = sale("T001420", "10BAHPK9801K1ZS", true,
        soldLine("inv-a", "30049011", "2.5", "1050.00"));
    RefundItem storedWithoutTax = returned("inv-a", "2.5", 1, "525.00");
    storedWithoutTax.setTaxableValue(new BigDecimal("525.00"));
    storedWithoutTax.setCgstAmount(BigDecimal.ZERO);
    storedWithoutTax.setSgstAmount(BigDecimal.ZERO);
    note("CN-00003", sale, "525.00", storedWithoutTax);

    GstRefundLine line = aggregator.buildContext("s1", "2026-10").getCdnrLines().get(0);

    assertMoney("500.00", line.getTaxableValue());
    assertMoney("12.50", line.getCentralTaxAmount());
  }

  @Test
  void everyNoteIsOneCreditNoteSeriesInTheDocumentSummary() {
    Purchase registered = sale("T001420", "10BAHPK9801K1ZS", true,
        soldLine("inv-a", "30049011", "2.5", "1050.00"));
    Purchase walkIn = sale("T001430", null, true, soldLine("inv-b", "30049011", "2.5", "1050.00"));
    note("CN-00006", walkIn, "210.00", returned("inv-b", "2.5", 1, "210.00"));
    note("CN-00003", registered, "525.00", returned("inv-a", "2.5", 1, "525.00"));

    List<GstDocumentSummaryLine> docs = aggregator.buildContext("s1", "2026-10").getDocLines();

    assertEquals(2, docs.size());
    GstDocumentSummaryLine notes = docs.get(1);
    assertEquals("Credit Note", notes.getNatureOfDocument());
    assertEquals("CN-00003", notes.getSrNoFrom());
    assertEquals("CN-00006", notes.getSrNoTo());
    assertEquals(2, notes.getTotalNumber());
  }

  @Test
  void theReturnAsksForTheIndianMonthAndDatesBillsInIndianTime() {
    java.util.TimeZone serverZone = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    try {
      Purchase sale = sale("T001337", "10BAHPK9801K1ZS", true,
          soldLine("inv-a", "30049011", "2.5", "105.00"));
      sale.setSoldAt(Instant.parse("2026-09-30T19:00:00Z")); // 00:30 IST on 1 October

      Gstr1ReportContext ctx = aggregator.buildContext("s1", "2026-10");

      org.mockito.Mockito.verify(purchaseRepository).findCompletedPurchasesInPeriod(
          eq("s1"), any(), eq(Instant.parse("2026-09-30T18:30:00Z")),
          eq(Instant.parse("2026-10-31T18:30:00Z")));
      assertEquals(java.time.LocalDate.of(2026, 10, 1), ctx.getB2bLines().get(0).getInvoiceDate());
    } finally {
      java.util.TimeZone.setDefault(serverZone);
    }
  }
}
