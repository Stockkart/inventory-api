package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Refund;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.RefundRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.RefundMapper;
import com.inventory.product.rest.dto.request.RefundRequest;
import com.inventory.product.service.vertical.ShopCapabilityService;
import com.inventory.product.validation.CheckoutValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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

/**
 * A customer return of an at-MRP line through {@link RefundService#processRefund}: the GST it
 * reverses depends on whether the sale stated it (#229), and a line returned in pieces reverses
 * and refunds, in total, what the sale charged for it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceAtMrpReturnTest {

  private static final String SHOP = "shop-1";
  private static final String LOT = "lot-1";
  private static final String SALE = "sale-1";

  @Mock private PurchaseRepository purchaseRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private CheckoutValidator checkoutValidator;
  @Mock private RefundMapper refundMapper;
  @Mock private InvoiceSequenceService invoiceSequenceService;
  @Mock private ShopCapabilityService shopCapabilityService;
  @Mock private ShopRepository shopRepository;
  @Mock private HttpServletRequest httpRequest;
  @InjectMocks private RefundService refundService;

  private final List<Refund> saved = new ArrayList<>();

  @BeforeEach
  void setUp() {
    saved.clear();
    when(httpRequest.getAttribute("shopId")).thenReturn(SHOP);
    when(httpRequest.getAttribute("userId")).thenReturn("user-1");
    when(invoiceSequenceService.getNextCreditNoteNo(anyString())).thenReturn("CN-1");
    when(shopRepository.findById(SHOP)).thenReturn(Optional.empty());
    when(refundRepository.findByPurchaseIdOrderByCreatedAtDesc(SALE)).thenAnswer(i -> saved);
    when(refundRepository.save(any(Refund.class)))
        .thenAnswer(
            i -> {
              Refund refund = i.getArgument(0);
              saved.add(refund);
              return refund;
            });
    Inventory inventory = new Inventory();
    inventory.setId(LOT);
    inventory.setShopId(SHOP);
    inventory.setCurrentBaseCount(0);
    inventory.setSoldBaseCount(1000);
    when(inventoryRepository.findById(LOT)).thenReturn(Optional.of(inventory));
  }

  /** A sale made since #229 stated 7.63 + 7.63 inside 142.50; the return reverses it. */
  @Test
  void returnOfASaleThatStatedAtMrpGstReversesIt() {
    sale(atMrpLine(1, 0, 0), new BigDecimal("15.26"));

    Refund refund = refund(1);

    assertMoney("7.63", refund.getCgstAmount());
    assertMoney("7.63", refund.getSgstAmount());
    assertMoney("127.24", refund.getTaxableTotal());
    assertMoney("143", refund.getRefundAmount());
  }

  /** A sale made before #229 has header tax 0 or none at all; its return reverses no GST. */
  @Test
  void returnOfASaleMadeBefore229ReversesNoGst() {
    for (BigDecimal headerTax : new BigDecimal[] {BigDecimal.ZERO, null}) {
      saved.clear();
      sale(atMrpLine(1, 0, 0), headerTax);

      Refund refund = refund(1);

      assertMoney("0", refund.getCgstAmount());
      assertMoney("0", refund.getSgstAmount());
      assertMoney("142.50", refund.getTaxableTotal());
    }
  }

  /**
   * An old mixed bill whose below-MRP line was taxed at the shop's default rates: its header
   * holds only that line's GST, so the at-MRP line's return reverses none.
   */
  @Test
  void belowMrpLineAtShopDefaultsDoesNotMakeAnOldSaleReadAsStated() {
    Shop shop = new Shop();
    shop.setCgst("6");
    shop.setSgst("6");
    when(shopRepository.findById(SHOP)).thenReturn(Optional.of(shop));
    PurchaseItem below = atMrpLine(1, 0, 0);
    below.setSellableRef("inventory:lot-2");
    below.setPriceToRetail(new BigDecimal("140"));
    below.setCgst(null);
    below.setSgst(null);
    sale(List.of(atMrpLine(1, 0, 0), below), new BigDecimal("15.96"));

    Refund refund = refund(1);

    assertMoney("0", refund.getCgstAmount());
    assertMoney("0", refund.getSgstAmount());
  }

  /**
   * 11 strips at MRP 150 − 5% on 10+1, returned a strip at a time, then the rest: the GST heads
   * and the line refunds add up to the sale's, read from the earlier returns' records.
   */
  @Test
  void schemeLineReturnedInPiecesAddsUpToTheSale() {
    PurchaseItem line = atMrpLine(11, 10, 1);
    // What CheckoutService.calculateTax stated for this line: 1272.32 × 6%, each head.
    sale(line, new BigDecimal("152.68"));

    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    for (int quantity : new int[] {1, 1, 1, 3, 5}) {
      Refund refund = refund(quantity);
      cgst = cgst.add(refund.getCgstAmount());
      sgst = sgst.add(refund.getSgstAmount());
      paid = paid.add(refund.getRefundedItems().get(0).getLineReturnTotal());
    }

    assertMoney("76.34", cgst);
    assertMoney("76.34", sgst);
    assertMoney("1425.00", paid);
  }

  private Refund refund(int quantity) {
    RefundRequest.RefundItem item = new RefundRequest.RefundItem();
    item.setInventoryId(LOT);
    item.setQuantity(quantity);
    RefundRequest request = new RefundRequest();
    request.setPurchaseId(SALE);
    request.setPaymentMethod("CASH");
    request.setItems(List.of(item));
    refundService.processRefund(request, httpRequest);
    return saved.get(saved.size() - 1);
  }

  private void sale(PurchaseItem line, BigDecimal headerTax) {
    sale(List.of(line), headerTax);
  }

  private void sale(List<PurchaseItem> lines, BigDecimal headerTax) {
    Purchase sale = new Purchase();
    sale.setId(SALE);
    sale.setShopId(SHOP);
    sale.setStatus(PurchaseStatus.COMPLETED);
    sale.setBillingMode(BillingMode.REGULAR);
    sale.setItems(lines);
    sale.setTaxTotal(headerTax);
    when(purchaseRepository.findById(SALE)).thenReturn(Optional.of(sale));
  }

  /** {@code strips} at MRP 150 − 5%, CGST 6 + SGST 6, a strip being 10 base units. */
  private static PurchaseItem atMrpLine(int strips, int payFor, int free) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef("inventory:" + LOT);
    item.setPriceToRetail(new BigDecimal("150"));
    item.setMaximumRetailPrice(new BigDecimal("150"));
    item.setSaleAdditionalDiscount(new BigDecimal("5"));
    item.setQuantity(BigDecimal.valueOf(strips));
    item.setBaseQuantity(strips * 10);
    item.setUnitFactor(10);
    item.setCgst("6");
    item.setSgst("6");
    if (payFor > 0) {
      item.setSchemeType(SchemeType.FIXED_UNITS);
      item.setSchemePayFor(payFor);
      item.setSchemeFree(free);
    }
    return item;
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }
}
