package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.SupplyPlacement;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
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
 * Checkout with a picked rate: the rate's base goes on the line with its disc and scheme, so
 * checkout's own arithmetic bills it once. 12% GST, PTR 100, MRP 150 unless stated; lines are
 * sold by the strip of 10.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceRateNameTest {

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
  @Spy private CartRateResolver cartRateResolver;
  @InjectMocks private CheckoutService checkoutService;

  private Purchase cart;
  private Inventory batch;

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

  @Test
  void rateAElevenUnitsBillsNineHundredTwenty() {
    stock("100", "150", "83.64", null, rateA());

    add(11, "Rate-A");

    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
    assertMoney("110.40", cart.getTaxTotal());
    assertMoney("1030.40", line().getTotalAmount());
  }

  @Test
  void rateReplacesBatchSaleDiscAndScheme() {
    stock("100", "150", "83.64", null, rateA());
    batch.setSaleAdditionalDiscount(new BigDecimal("5"));
    batch.setSchemeType(SchemeType.FIXED_UNITS);
    batch.setSchemePayFor(5);
    batch.setSchemeFree(1);

    add(11, "Rate-A");

    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
  }

  @Test
  void rateWinsOverDiscSentInSameRequest() {
    stock("100", "150", "83.64", null, rateA());

    AddToCartRequest.CartItem item = item(11, "Rate-A");
    item.setSaleAdditionalDiscount(new BigDecimal("3"));
    item.setSchemeType(SchemeType.FIXED_UNITS);
    item.setSchemePayFor(2);
    item.setSchemeFree(1);
    send(item);
    assertTerms("100", "8", 10, 1);

    AddToCartRequest.CartItem edit = item(0, "Rate-A");
    edit.setSaleAdditionalDiscount(new BigDecimal("3"));
    edit.setPriceToRetail(new BigDecimal("90"));
    send(edit);
    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
  }

  @Test
  void manualDiscEditAfterPickSticksAndRepickRestores() {
    stock("100", "150", "83.64", null, rateA());
    add(11, "Rate-A");

    AddToCartRequest.CartItem edit = item(0, null);
    edit.setSaleAdditionalDiscount(new BigDecimal("5"));
    send(edit);
    assertTerms("100", "5", 10, 1);
    assertMoney("950.00", beforeTax());

    add(0, "Rate-A");
    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
  }

  @Test
  void pickOnExistingLineReplacesTerms() {
    stock("150", "150", "150", null, rateA(), rateB());
    batch.setSaleAdditionalDiscount(new BigDecimal("5"));
    batch.setSchemeType(SchemeType.FIXED_UNITS);
    batch.setSchemePayFor(5);
    batch.setSchemeFree(1);
    add(11, null);
    assertTerms("150", "5", 5, 1);

    add(0, "Rate-A");
    assertTerms("100", "8", 10, 1);
    assertEquals(11, line().getQuantity().intValue());

    add(0, "Rate-B");
    PurchaseItem line = line();
    assertMoney("100", line.getPriceToRetail());
    assertMoney("7", line.getSaleAdditionalDiscount());
    assertNull(line.getSchemeType());
    assertNull(line.getSchemePayFor());
    assertNull(line.getSchemeFree());
    assertMoney("1023.00", beforeTax());
  }

  @Test
  void pickWithAQuantityOnALineAtMrpTakesTheRateBase() {
    stock("100", "150", "150", null, rateA());
    add(1, null);
    assertEquals(true, CheckoutUtils.isSellingAtMrp(line()));

    add(10, "Rate-A");

    assertTerms("100", "8", 10, 1);
    assertEquals(11, line().getQuantity().intValue());
    assertMoney("920.00", beforeTax());
  }

  @Test
  void defaultRateNewLineDoesNotDoubleDiscount() {
    stock("100", "150", "83.64", "Rate-A", rateA());
    batch.setSaleAdditionalDiscount(new BigDecimal("5"));

    add(11, null);

    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
  }

  @Test
  void legacyRateKeepsOldBehaviour() {
    RateDto old = new RateDto("Old", new BigDecimal("95"));
    stock("100", "150", "95", "Old", old);
    batch.setSaleAdditionalDiscount(new BigDecimal("5"));

    add(1, null);
    assertMoney("95", line().getPriceToRetail());
    assertMoney("5", line().getSaleAdditionalDiscount());
    assertNull(line().getSchemeType());
    BigDecimal todayTotal = line().getTotalAmount();

    cart = null;
    add(1, "Old");
    assertMoney("95", line().getPriceToRetail());
    assertMoney("5", line().getSaleAdditionalDiscount());
    assertNull(line().getSchemeType());
    assertMoney(todayTotal.toPlainString(), line().getTotalAmount());
  }

  @Test
  void rateWithSchemeOnQuantityBelowPayForBillsByExistingRule() {
    stock("100", "150", "100", null, rateA());
    AddToCartRequest.CartItem today = item(5, null);
    today.setSaleAdditionalDiscount(new BigDecimal("8"));
    today.setSchemeType(SchemeType.FIXED_UNITS);
    today.setSchemePayFor(10);
    today.setSchemeFree(1);
    send(today);
    BigDecimal todayLine = line().getTotalAmount();
    BigDecimal todaySub = cart.getSubTotal();
    BigDecimal todayTax = cart.getTaxTotal();
    BigDecimal todayGrand = cart.getGrandTotal();

    cart = null;
    add(5, "Rate-A");

    assertTerms("100", "8", 10, 1);
    assertMoney(todayLine.toPlainString(), line().getTotalAmount());
    assertMoney(todaySub.toPlainString(), cart.getSubTotal());
    assertMoney(todayTax.toPlainString(), cart.getTaxTotal());
    assertMoney(todayGrand.toPlainString(), cart.getGrandTotal());
  }

  /** The four rows of the spec's GST table, one unit each. */
  @Test
  void billedGstMatchesGstMode() {
    gstRow("150", "150", rate("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, "150", "5",
        PricingConstants.GST_MODE_INCLUSIVE), "142.50", "15.26");
    gstRow("100", "150", rate("Rate-M", PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE, "150", "25",
        PricingConstants.GST_MODE_INCLUSIVE), "112.50", "12.06");
    gstRow("100", "150", rate("Rate-P", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "8",
        PricingConstants.GST_MODE_EXCLUSIVE), "103.04", "11.04");
    gstRow("100", "100", rate("Rate-P", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "8",
        PricingConstants.GST_MODE_INCLUSIVE), "92.00", "9.86");
  }

  @Test
  void unknownRateNameRejected() {
    stock("100", "150", "83.64", null, rateA());

    assertThrows(ValidationException.class, () -> add(1, "Rate-Z"));
  }

  private void gstRow(String ptr, String mrp, RateDto rate, String customerPays, String gst) {
    cart = null;
    stock(ptr, mrp, rate.getPrice().toPlainString(), null, rate);

    add(1, rate.getName());

    PurchaseItem line = line();
    boolean inclusive = PricingConstants.GST_MODE_INCLUSIVE.equals(rate.getGstMode());
    assertEquals(inclusive, CheckoutUtils.isSellingAtMrp(line), rate.getName() + " gstMode");
    assertMoney(customerPays, line.getTotalAmount());
    assertMoney(gst, cart.getTaxTotal());
  }

  private static RateDto rateA() {
    RateDto rate = rate("Rate-A", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "8",
        PricingConstants.GST_MODE_EXCLUSIVE);
    rate.setScheme(new SchemeDto(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null));
    rate.setPrice(new BigDecimal("83.64"));
    return rate;
  }

  private static RateDto rateB() {
    return rate("Rate-B", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "7",
        PricingConstants.GST_MODE_EXCLUSIVE);
  }

  private static RateDto rate(String name, String base, String baseValue, String disc, String gstMode) {
    BigDecimal value = new BigDecimal(baseValue);
    BigDecimal price = value.multiply(BigDecimal.ONE.subtract(new BigDecimal(disc).movePointLeft(2)))
        .setScale(2, java.math.RoundingMode.HALF_UP);
    RateDto rate = new RateDto(name, price);
    rate.setBase(base);
    rate.setBaseValue(value);
    rate.setDiscountPercent(new BigDecimal(disc));
    rate.setGstMode(gstMode);
    return rate;
  }

  private void stock(String ptr, String mrp, String sell, String defaultRate, RateDto... rates) {
    batch = new Inventory();
    batch.setId(LOT);
    batch.setShopId(SHOP);
    batch.setName("Paracetamol 650");
    batch.setBaseUnit("TABLET");
    batch.setUnitConversions(new UnitConversion("STRIP", 10));
    batch.setMaximumRetailPrice(new BigDecimal(mrp));
    batch.setPriceToRetail(new BigDecimal(ptr));
    batch.setSellingPrice(new BigDecimal(sell));
    batch.setCostPrice(new BigDecimal("50"));
    batch.setCgst("6");
    batch.setSgst("6");
    batch.setCurrentBaseCount(10000);
    batch.setRates(List.of(rates));
    batch.setDefaultRate(defaultRate);
    when(inventoryRepository.findById(LOT)).thenReturn(Optional.of(batch));
  }

  private AddToCartRequest.CartItem item(int strips, String rateName) {
    AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
    item.setId(LOT);
    item.setUnit("STRIP");
    item.setQuantity(strips);
    item.setBaseQuantity(strips * 10);
    item.setRateName(rateName);
    return item;
  }

  private void add(int strips, String rateName) {
    send(item(strips, rateName));
  }

  private void send(AddToCartRequest.CartItem item) {
    AddToCartRequest request = new AddToCartRequest();
    request.setItems(List.of(item));
    checkoutService.addToCart(request, httpRequest);
  }

  private PurchaseItem line() {
    return cart.getItems().get(0);
  }

  /** Line value before GST: subtotal less the additional discount. */
  private BigDecimal beforeTax() {
    return cart.getSubTotal().subtract(cart.getSaleAdditionalDiscountTotal());
  }

  private void assertTerms(String price, String disc, int payFor, int free) {
    PurchaseItem line = line();
    assertMoney(price, line.getPriceToRetail());
    assertMoney(disc, line.getSaleAdditionalDiscount());
    assertEquals(SchemeType.FIXED_UNITS, line.getSchemeType());
    assertEquals(payFor, line.getSchemePayFor());
    assertEquals(free, line.getSchemeFree());
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }
}
