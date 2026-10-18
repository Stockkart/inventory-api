package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.gst.SupplyPlacement;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.PurchaseMapperImpl;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.product.rest.dto.response.AddToCartResponse;
import com.inventory.product.service.vertical.CartContributorResolver;
import com.inventory.product.utils.CheckoutUtils;
import com.inventory.product.validation.CheckoutValidator;
import com.inventory.user.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A returned at-MRP line reverses the GST the sale stated inside its price, head by head, and
 * refunds what the customer paid. The sale side is run through {@link CheckoutService#addToCart}
 * so the comparison is against the sale's real tax calculation, not a copy of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesReturnValuationAtMrpTest {

  private static final String SHOP = "shop-1";
  private static final String LOT = "lot-1";
  private static final String CUSTOMER = "customer-1";

  @Mock private PurchaseRepository purchaseRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private CheckoutValidator checkoutValidator;
  @Mock private PackagingUnitService packagingUnitService;
  @Mock private ShopRepository shopRepository;
  @Mock private QuotationService quotationService;
  @Mock private ObjectProvider<SupplyPlacement> supplyPlacement;
  @Mock private HttpServletRequest httpRequest;
  @Mock private CustomerService customerService;
  @Mock private CartContributorResolver cartContributorResolver;
  @Spy private PurchaseMapperImpl purchaseMapper;
  @InjectMocks private CheckoutService checkoutService;

  private Purchase cart;

  @BeforeEach
  void setUp() {
    cart = null;
    when(cartContributorResolver.resolveContributor(any())).thenReturn(Optional.empty());
    when(httpRequest.getAttribute("shopId")).thenReturn(SHOP);
    when(httpRequest.getAttribute("userId")).thenReturn("user-1");
    when(quotationService.resolveTargetCart(any(), anyString(), anyString())).thenAnswer(i -> cart);
    when(quotationService.quotedBaseQuantitiesByLot(any(), any())).thenReturn(Map.of());
    when(checkoutValidator.resolveAndValidateCartBillingMode(any(), any()))
        .thenReturn(BillingMode.REGULAR);
    when(shopRepository.findById(any())).thenReturn(Optional.empty());
    when(customerService.resolvePurchaseCustomerId(any(), any(), any())).thenReturn(CUSTOMER);
    when(purchaseRepository.save(any(Purchase.class)))
        .thenAnswer(
            i -> {
              Purchase saved = i.getArgument(0);
              cart = saved;
              return saved;
            });
    doReturn(new AddToCartResponse()).when(purchaseMapper).toAddToCartResponse(any());
  }

  @Test
  void atMrpReturnStatesGstInside() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(atMrpLine(), 1, 1, BillingMode.REGULAR, false);

    assertMoney("7.63", line.cgst());
    assertMoney("7.63", line.sgst());
    assertMoney("0", line.igst());
    assertMoney("127.24", line.taxable());
    assertMoney("142.50", line.lineTotal());
  }

  @Test
  void atMrpInterstateReturnUsesIgst() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(atMrpLine(), 1, 1, BillingMode.REGULAR, true);

    assertMoney("0", line.cgst());
    assertMoney("0", line.sgst());
    assertMoney("15.26", line.igst());
    assertMoney("127.24", line.taxable());
    assertMoney("142.50", line.lineTotal());
  }

  @Test
  void atMrpReturnTaxMatchesSaleTax() {
    for (boolean interstate : new boolean[] {false, true}) {
      for (int[] sale : SALES) {
        sell(sale[0], sale[1], sale[2], interstate);
        PurchaseItem soldLine = cart.getItems().get(0);
        assertTrue(CheckoutUtils.isSellingAtMrp(soldLine), "line sold at MRP");
        assertEquals(interstate, Boolean.TRUE.equals(cart.getInterstate()));

        SalesReturnValuation.LineAmounts returned =
            SalesReturnValuation.lineAmounts(
                soldLine, soldLine.getBaseQuantity(), sale[0], BillingMode.REGULAR, interstate);

        String label = "qty " + sale[0] + (interstate ? " interstate" : " local");
        assertTrue(cart.getTaxTotal().signum() > 0, label + ": sale states GST");
        assertMoneyEquals(cart.getCgstAmount(), returned.cgst(), label + " CGST");
        assertMoneyEquals(cart.getSgstAmount(), returned.sgst(), label + " SGST");
        assertMoneyEquals(cart.getIgstAmount(), returned.igst(), label + " IGST");
      }
    }
  }

  @Test
  void atMrpRefundAmountMatchesSaleLine() {
    for (int[] sale : SALES) {
      sell(sale[0], sale[1], sale[2], false);
      PurchaseItem soldLine = cart.getItems().get(0);

      SalesReturnValuation.LineAmounts returned =
          SalesReturnValuation.lineAmounts(
              soldLine, soldLine.getBaseQuantity(), sale[0], BillingMode.REGULAR, false);

      assertMoney(PAID[sale[0] == 1 ? 0 : sale[0] == 3 ? 1 : 2], soldLine.getTotalAmount());
      assertMoneyEquals(soldLine.getTotalAmount(), returned.lineTotal(), "qty " + sale[0]);
      assertMoneyEquals(
          returned.lineTotal(),
          returned.taxable().add(returned.cgst()).add(returned.sgst()).add(returned.igst()),
          "qty " + sale[0] + " taxable + GST");
    }
  }

  /** Pinned from main before this change: a line below MRP adds tax on top, as it always has. */
  @Test
  void belowMrpReturnUnchanged() {
    PurchaseItem item = atMrpLine();
    item.setPriceToRetail(new BigDecimal("140"));

    SalesReturnValuation.LineAmounts local =
        SalesReturnValuation.lineAmounts(item, 1, 1, BillingMode.REGULAR, false);
    assertMoney("133.00", local.taxable());
    assertMoney("7.98", local.cgst());
    assertMoney("7.98", local.sgst());
    assertMoney("0", local.igst());
    assertMoney("148.96", local.lineTotal());

    SalesReturnValuation.LineAmounts interstate =
        SalesReturnValuation.lineAmounts(item, 1, 1, BillingMode.REGULAR, true);
    assertMoney("133.00", interstate.taxable());
    assertMoney("15.96", interstate.igst());
    assertMoney("148.96", interstate.lineTotal());
  }

  @Test
  void compositionBillingAtMrpStillNoTax() {
    SalesReturnValuation.LineAmounts line =
        SalesReturnValuation.lineAmounts(atMrpLine(), 1, 1, BillingMode.BASIC, false);

    assertMoney("0", line.cgst());
    assertMoney("0", line.sgst());
    assertMoney("0", line.igst());
    assertMoney("142.50", line.taxable());
    assertMoney("142.50", line.lineTotal());
  }

  /** {quantity, scheme pay-for, scheme free}; 0/0 is no scheme. */
  private static final int[][] SALES = {{1, 0, 0}, {3, 0, 0}, {11, 10, 1}};

  /** What the customer paid for each of {@link #SALES}: 150 − 5% a strip, 10 paid of 10+1. */
  private static final String[] PAID = {"142.50", "427.50", "1425.00"};

  /** One strip at MRP 150, 5% off, CGST 6 + SGST 6. */
  private static PurchaseItem atMrpLine() {
    PurchaseItem item = new PurchaseItem();
    item.setPriceToRetail(new BigDecimal("150"));
    item.setMaximumRetailPrice(new BigDecimal("150"));
    item.setSaleAdditionalDiscount(new BigDecimal("5"));
    item.setQuantity(BigDecimal.ONE);
    item.setBaseQuantity(1);
    item.setCgst("6");
    item.setSgst("6");
    return item;
  }

  private void sell(int quantity, int payFor, int free, boolean interstate) {
    cart = null;
    SupplyPlacement placement = mock(SupplyPlacement.class);
    when(placement.isInterstateSale(any(), any())).thenReturn(interstate);
    when(supplyPlacement.getIfAvailable()).thenReturn(placement);

    Inventory inventory = new Inventory();
    inventory.setId(LOT);
    inventory.setShopId(SHOP);
    inventory.setName("Paracetamol 650");
    inventory.setBaseUnit("TABLET");
    inventory.setUnitConversions(new UnitConversion("STRIP", 10));
    inventory.setMaximumRetailPrice(new BigDecimal("150"));
    inventory.setSellingPrice(new BigDecimal("150"));
    inventory.setCostPrice(new BigDecimal("50"));
    inventory.setCgst("6");
    inventory.setSgst("6");
    inventory.setCurrentBaseCount(1000);
    when(inventoryRepository.findById(LOT)).thenReturn(Optional.of(inventory));

    AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
    item.setId(LOT);
    item.setUnit("STRIP");
    item.setQuantity(quantity);
    item.setBaseQuantity(quantity * 10);
    item.setSaleAdditionalDiscount(new BigDecimal("5"));
    if (payFor > 0) {
      item.setSchemeType(SchemeType.FIXED_UNITS);
      item.setSchemePayFor(payFor);
      item.setSchemeFree(free);
    }
    AddToCartRequest request = new AddToCartRequest();
    request.setCustomerId(CUSTOMER);
    request.setItems(List.of(item));
    checkoutService.addToCart(request, httpRequest);
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

  private static void assertMoneyEquals(BigDecimal expected, BigDecimal actual, String label) {
    BigDecimal e = expected != null ? expected : BigDecimal.ZERO;
    BigDecimal a = actual != null ? actual : BigDecimal.ZERO;
    assertEquals(0, e.compareTo(a), label + ": expected " + e + " but was " + a);
  }
}
