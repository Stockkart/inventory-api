package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.inventory.common.gst.SupplyPlacement;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.BillingMode;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceUnitSwitchMrpTest {

  private static final String SHOP = "shop-1";
  private static final String LOT = "lot-1";

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
    when(checkoutValidator.resolveAndValidateCartBillingMode(any(), any())).thenReturn(BillingMode.REGULAR);
    when(shopRepository.findById(any())).thenReturn(Optional.empty());
    when(purchaseRepository.save(any(Purchase.class))).thenAnswer(i -> {
      Purchase saved = i.getArgument(0);
      cart = saved;
      return saved;
    });
    doReturn(new AddToCartResponse()).when(purchaseMapper).toAddToCartResponse(any());
  }

  /**
   * What a switch stores today for a line below MRP, recorded on main before the fix. The
   * request is the one ScanSell sends on a unit change: quantity 0 and the price it already
   * rescaled to the new unit. Lines hold quantity in the sale unit (base / unitFactor, where
   * unitFactor is the sale unit's factor) and keep the batch's per-strip MRP whatever the unit.
   */
  @Test
  void unitSwitchTodayCharacterization() {
    stock("100", "90", 15);
    add("TABLET", 15, 15, null);
    assertLine("90", "100", "15.0000", 15, 1, "1512.00");
    add("STRIP", 0, 0, "1350.00");
    assertLine("20250.00", "100", "1.0000", 15, 15, "22680.00");

    cart = null;
    stock("150", "140", 10);
    add("STRIP", 1, 10, null);
    assertLine("140", "150", "1.0000", 10, 10, "156.80");
    add("TABLET", 0, 0, "14.00");
    assertLine("1.40", "150", "10.0000", 10, 1, "15.68");
  }

  @Test
  void atMrpLineStaysAtMrpAfterSwitchBothWays() {
    stock("150", "150", 10);
    add("STRIP", 1, 10, null);
    assertTrue(CheckoutUtils.isSellingAtMrp(line()));

    add("TABLET", 0, 0, "15.00");
    assertLine("15.00", "15.00", "10.0000", 10, 1, "150.00");
    assertTrue(CheckoutUtils.isSellingAtMrp(line()));
    assertGstInsideMrp("150");

    add("STRIP", 0, 0, "150.00");
    assertLine("150", "150", "1.0000", 10, 10, "150.00");
    assertTrue(CheckoutUtils.isSellingAtMrp(line()));
    assertGstInsideMrp("150");

    cart = null;
    stock("100", "100", 15);
    add("TABLET", 15, 15, null);
    assertTrue(CheckoutUtils.isSellingAtMrp(line()));
    add("STRIP", 0, 0, "1500.00");
    assertLine("100", "100", "1.0000", 15, 15, "100.00");
    assertTrue(CheckoutUtils.isSellingAtMrp(line()));
    assertGstInsideMrp("100");
  }

  @Test
  void billedTotalNeverAboveMrpForUnevenFactor() {
    stock("100", "100", 15);
    add("STRIP", 1, 15, null);
    add("TABLET", 0, 0, "6.67");

    PurchaseItem line = line();
    assertEquals(0, new BigDecimal("6.66").compareTo(line.getPriceToRetail()));
    assertEquals(0, new BigDecimal("6.66").compareTo(line.getMaximumRetailPrice()));
    BigDecimal pricingQuantity = CheckoutUtils.getQuantityAsPricingUnits(line);
    assertTrue(line.getTotalAmount().compareTo(line.getMaximumRetailPrice().multiply(pricingQuantity)) <= 0);
    assertTrue(line.getTotalAmount().compareTo(new BigDecimal("100")) <= 0);
    assertEquals(0, new BigDecimal("99.90").compareTo(line.getTotalAmount()));
  }

  @Test
  void belowMrpLineKeepsExistingBehaviour() {
    stock("100", "90", 15);
    add("TABLET", 15, 15, null);
    add("STRIP", 0, 0, "1350.00");
    assertCart("20250.00", "2430.00", "22680");
    assertFalse(CheckoutUtils.isSellingAtMrp(line()));

    cart = null;
    stock("150", "140", 10);
    add("STRIP", 1, 10, null);
    add("TABLET", 0, 0, "14.00");
    assertCart("14.00", "1.68", "16");
    assertFalse(CheckoutUtils.isSellingAtMrp(line()));
  }

  private void stock(String mrp, String sell, int factor) {
    Inventory inventory = new Inventory();
    inventory.setId(LOT);
    inventory.setShopId(SHOP);
    inventory.setName("Paracetamol 650");
    inventory.setBaseUnit("TABLET");
    inventory.setUnitConversions(new UnitConversion("STRIP", factor));
    inventory.setMaximumRetailPrice(new BigDecimal(mrp));
    inventory.setSellingPrice(new BigDecimal(sell));
    inventory.setCostPrice(new BigDecimal("50"));
    inventory.setCgst("6");
    inventory.setSgst("6");
    inventory.setCurrentBaseCount(1000);
    when(inventoryRepository.findById(LOT)).thenReturn(Optional.of(inventory));
  }

  private void add(String unit, int quantity, int baseQuantity, String priceToRetail) {
    AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
    item.setId(LOT);
    item.setUnit(unit);
    item.setQuantity(quantity);
    item.setBaseQuantity(baseQuantity);
    item.setPriceToRetail(priceToRetail != null ? new BigDecimal(priceToRetail) : null);
    AddToCartRequest request = new AddToCartRequest();
    request.setItems(List.of(item));
    checkoutService.addToCart(request, httpRequest);
  }

  private PurchaseItem line() {
    return cart.getItems().get(0);
  }

  private void assertLine(String price, String mrp, String quantity, int baseQuantity, int unitFactor,
      String total) {
    PurchaseItem line = line();
    assertEquals(0, new BigDecimal(price).compareTo(line.getPriceToRetail()), "price " + line.getPriceToRetail());
    assertEquals(0, new BigDecimal(mrp).compareTo(line.getMaximumRetailPrice()), "mrp " + line.getMaximumRetailPrice());
    assertEquals(0, new BigDecimal(quantity).compareTo(line.getQuantity()), "quantity " + line.getQuantity());
    assertEquals(baseQuantity, line.getBaseQuantity());
    assertEquals(unitFactor, line.getUnitFactor());
    assertEquals(0, new BigDecimal(total).compareTo(line.getTotalAmount()), "total " + line.getTotalAmount());
  }

  private void assertCart(String subTotal, String taxTotal, String grandTotal) {
    assertEquals(0, new BigDecimal(subTotal).compareTo(cart.getSubTotal()), "subTotal " + cart.getSubTotal());
    assertEquals(0, new BigDecimal(taxTotal).compareTo(cart.getTaxTotal()), "taxTotal " + cart.getTaxTotal());
    assertEquals(0, new BigDecimal(grandTotal).compareTo(cart.getGrandTotal()), "grandTotal " + cart.getGrandTotal());
  }

  /** At MRP the GST is stated but sits inside the price: the bill comes to the MRP, not above it. */
  private void assertGstInsideMrp(String mrpTotal) {
    assertTrue(cart.getTaxTotal().signum() > 0, "GST inside MRP is stated");
    assertEquals(0, new BigDecimal(mrpTotal).compareTo(cart.getGrandTotal()), "grandTotal " + cart.getGrandTotal());
  }
}
