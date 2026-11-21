package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.GstConfigurationException;
import com.inventory.product.domain.model.Location;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.model.GstInvoiceLine;
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

/** GSTR-1 reports a sale under the tax head its invoice charged: IGST when it crossed a border. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Gstr1DataAggregatorInterstateTest {

  @Mock private PurchaseRepository purchaseRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private ShopRepository shopRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private HsnSacCatalog hsnSacCatalog;
  @InjectMocks private Gstr1DataAggregator aggregator;

  private final Shop shop = new Shop();
  private final List<Purchase> purchases = new ArrayList<>();
  private final List<Customer> customers = new ArrayList<>();

  @BeforeEach
  void setUp() {
    shop.setGstinNo("10AFBPL7000H1Z8");
    when(shopRepository.findById("s1")).thenReturn(Optional.of(shop));
    when(purchaseRepository.findCompletedPurchasesInPeriod(eq("s1"), any(), any(), any()))
        .thenReturn(purchases);
    when(refundRepository.findByShopIdAndCreatedAtInPeriod(eq("s1"), any(), any()))
        .thenReturn(List.of());
    when(customerRepository.findAllById(any())).thenReturn(customers);
    when(inventoryRepository.findByIdIn(any())).thenReturn(List.of());
    when(hsnSacCatalog.descriptionFor(anyString())).thenReturn(Optional.empty());
  }

  private static PurchaseItem item(String hsn, String halfGst, String amount) {
    PurchaseItem item = new PurchaseItem();
    item.setHsn(hsn);
    item.setName("item " + hsn);
    item.setQuantity(BigDecimal.ONE);
    item.setCgst(halfGst);
    item.setSgst(halfGst);
    item.setTotalAmount(new BigDecimal(amount));
    return item;
  }

  private void sale(String id, String customerGstin, boolean interstate, PurchaseItem... items) {
    Customer customer = new Customer();
    customer.setId("c-" + id);
    customer.setName("Buyer " + id);
    customer.setGstin(customerGstin);
    customers.add(customer);
    Purchase purchase = new Purchase();
    purchase.setId(id);
    purchase.setInvoiceNo(id);
    purchase.setCustomerId(customer.getId());
    purchase.setInterstate(interstate);
    purchase.setSoldAt(Instant.parse("2026-08-14T06:00:00Z"));
    purchase.setItems(List.of(items));
    BigDecimal grand = BigDecimal.ZERO;
    for (PurchaseItem i : items) grand = grand.add(i.getTotalAmount());
    purchase.setGrandTotal(grand);
    purchases.add(purchase);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual),
        "expected " + expected + " but was " + actual);
  }

  @Test
  void anInterstateSaleIsReportedAsIgstPerRate() {
    sale("T1", "19AAOCM4713F1ZB", true, item("3004", "9", "118.00"), item("3401", "2.5", "105.00"));

    Gstr1ReportContext ctx = aggregator.buildContext("s1", "2026-08");

    List<GstInvoiceLine> rows = ctx.getB2bLines();
    assertEquals(2, rows.size());
    assertMoney("18.00", rows.get(0).getIntegratedTaxAmount());
    assertMoney("0", rows.get(0).getCentralTaxAmount());
    assertMoney("0", rows.get(0).getStateTaxAmount());
    assertMoney("5.00", rows.get(1).getIntegratedTaxAmount());
  }

  @Test
  void aLocalSaleKeepsCgstAndSgst() {
    sale("T2", "10ATAPK0829J1Z5", false, item("3004", "9", "118.00"));

    GstInvoiceLine row = aggregator.buildContext("s1", "2026-08").getB2bLines().get(0);

    assertMoney("0", row.getIntegratedTaxAmount());
    assertMoney("9.00", row.getCentralTaxAmount());
    assertMoney("9.00", row.getStateTaxAmount());
  }

  @Test
  void theHsnSummaryCarriesIgstForAnInterstateSale() {
    sale("T3", "19AAOCM4713F1ZB", true, item("3004", "9", "118.00"));
    sale("T4", "19AAOCM4713F1ZB", true, item("3004", "9", "236.00"));

    List<GstHsnLine> hsn = aggregator.buildContext("s1", "2026-08").getHsnB2bLines();

    assertEquals(1, hsn.size());
    assertMoney("54.00", hsn.get(0).getIntegratedTaxAmount());
    assertMoney("0", hsn.get(0).getCentralTaxAmount());
    assertMoney("0", hsn.get(0).getStateUtTaxAmount());
  }

  @Test
  void aShopWithNoStateCannotFile() {
    shop.setGstinNo(null);
    shop.setLocation(new Location());

    assertThrows(GstConfigurationException.class, () -> aggregator.buildContext("s1", "2026-08"));
  }
}
