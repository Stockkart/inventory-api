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

  /** A legacy rate picked with a quantity change on an at-MRP line takes the rate's price. */
  @Test
  void legacyPickWithAQuantityOnALineAtMrpTakesTheRatePrice() {
    RateDto old = new RateDto("Old", new BigDecimal("120"));
    stock("100", "150", "150", null, old);
    add(1, null);
    assertEquals(true, CheckoutUtils.isSellingAtMrp(line()));

    add(1, "Old");

    assertMoney("120", line().getPriceToRetail());
    assertEquals(2, line().getQuantity().intValue());
  }

  /**
   * A rate matching no rule is stored as name, price and manual only (pricing strips anything a
   * client sent), so checkout bills its price as a legacy rate, never a client-typed base.
   */
  @Test
  void strippedOrphanRateBillsItsPriceAsALegacyRate() {
    RateDto orphan = new RateDto("Orphan", new BigDecimal("120"));
    orphan.setManual(true);
    stock("100", "150", "130", null, orphan);

    add(1, "Orphan");

    assertMoney("120", line().getPriceToRetail());
    assertNull(line().getSchemeType());
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

  /** ScanSell's + resends the line's disc and sends scheme only when the line has one. */
  @Test
  void defaultRateThenPickRateBThenPlusOneKeepsRateBTerms() {
    stock("100", "150", "83.64", "Rate-A", rateA(), rateB());
    add(11, null);
    add(0, "Rate-B");

    AddToCartRequest.CartItem plus = item(1, null);
    plus.setSaleAdditionalDiscount(new BigDecimal("7"));
    send(plus);

    PurchaseItem line = line();
    assertEquals(12, line.getQuantity().intValue());
    assertMoney("100", line.getPriceToRetail());
    assertMoney("7", line.getSaleAdditionalDiscount());
    assertNull(line.getSchemeType());
    assertNull(line.getSchemePayFor());
    assertNull(line.getSchemeFree());
    assertMoney("1116.00", beforeTax());
  }

  @Test
  void defaultRateThenDiscEditThenPlusOneKeepsEdit() {
    RateDto schemeOnly = rate("Rate-C", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, "100", "0",
        PricingConstants.GST_MODE_EXCLUSIVE);
    schemeOnly.setDiscountPercent(null);
    schemeOnly.setScheme(new SchemeDto(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null));
    stock("100", "150", "90.91", "Rate-C", schemeOnly);
    add(11, null);
    assertTerms("100", "0", 10, 1);

    AddToCartRequest.CartItem edit = item(0, null);
    edit.setSaleAdditionalDiscount(new BigDecimal("5"));
    send(edit);

    add(1, null);

    assertEquals(12, line().getQuantity().intValue());
    assertTerms("100", "5", 10, 1);
  }

  @Test
  void defaultMrpRateThenPickPtrRateThenPlusOneKeepsPtrPrice() {
    RateDto rateM = rate("Rate-M", PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE, "150", "25",
        PricingConstants.GST_MODE_INCLUSIVE);
    stock("100", "150", "112.50", "Rate-M", rateM, rateA());
    add(1, null);
    assertMoney("150", line().getPriceToRetail());
    assertMoney("25", line().getSaleAdditionalDiscount());
    add(0, "Rate-A");

    AddToCartRequest.CartItem plus = item(10, null);
    plus.setSaleAdditionalDiscount(new BigDecimal("8"));
    plus.setSchemeType(SchemeType.FIXED_UNITS);
    plus.setSchemePayFor(10);
    plus.setSchemeFree(1);
    send(plus);

    assertEquals(11, line().getQuantity().intValue());
    assertTerms("100", "8", 10, 1);
    assertMoney("920.00", beforeTax());
  }

  @Test
  void unitSwitchDoesNotReapplyDefaultRate() {
    stock("100", "150", "83.64", "Rate-A", rateA());
    add(11, null);
    AddToCartRequest.CartItem edit = item(0, null);
    edit.setSaleAdditionalDiscount(new BigDecimal("5"));
    send(edit);

    AddToCartRequest.CartItem toTablet = item(110, null);
    toTablet.setUnit("TABLET");
    toTablet.setBaseQuantity(110);
    send(toTablet);

    PurchaseItem line = line();
    assertEquals("TABLET", line.getSaleUnit());
    assertMoney("5", line.getSaleAdditionalDiscount());
    assertEquals(SchemeType.FIXED_UNITS, line.getSchemeType());
    assertEquals(10, line.getSchemePayFor());
    assertEquals(1, line.getSchemeFree());
    assertMoney("10.00", line.getPriceToRetail());
  }

  @Test
  void rateNameWithUnitSwitchRejected() {
    stock("100", "150", "83.64", null, rateA());
    add(11, "Rate-A");

    AddToCartRequest.CartItem pickAndSwitch = item(0, "Rate-A");
    pickAndSwitch.setUnit("TABLET");
    ValidationException e = assertThrows(ValidationException.class, () -> send(pickAndSwitch));
    org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("rateName"), e.getMessage());

    AddToCartRequest.CartItem addAndSwitch = item(10, "Rate-A");
    addAndSwitch.setUnit("TABLET");
    addAndSwitch.setBaseQuantity(10);
    assertThrows(ValidationException.class, () -> send(addAndSwitch));
    assertEquals("STRIP", line().getSaleUnit());
  }

  @Test
  void unknownRateNameRejected() {
    stock("100", "150", "83.64", null, rateA());

    assertThrows(ValidationException.class, () -> add(1, "Rate-Z"));
  }

  /**
   * B1: a rate's base is per pack. Picked on a line sold by the tablet it goes on per tablet
   * (base ÷ pack, rounded down) with the line MRP for a tablet; disc and scheme apply unchanged,
   * the scheme on the tablets paid for. 110 tablets of Rate-A: 100 paid × 10.00 × 0.92 = 920.00.
   */
  @Test
  void switchToTabletThenPickRateBillsThePerTabletBase() {
    stock("100", "150", "90", null, rateA());
    add(11, null);
    switchUnit("TABLET");
    assertEquals("TABLET", line().getSaleUnit());

    add(0, "Rate-A", "TABLET");

    PurchaseItem line = line();
    assertMoney("10.00", line.getPriceToRetail());
    assertMoney("15.00", line.getMaximumRetailPrice());
    assertMoney("8", line.getSaleAdditionalDiscount());
    assertEquals(SchemeType.FIXED_UNITS, line.getSchemeType());
    assertEquals(10, line.getSchemePayFor());
    assertEquals(1, line.getSchemeFree());
    assertEquals(110, line.getBaseQuantity());
    assertMoney("920.00", beforeTax());
    assertMoney("110.40", cart.getTaxTotal());

    add(1, null, "TABLET");
    assertMoney("10.00", line().getPriceToRetail());
    assertEquals(111, line().getBaseQuantity());
  }

  /**
   * T6: an at-MRP rate picked on a tablet line of an uneven pack (MRP 100, strip of 15) goes on
   * at 6.66 a tablet, rounded down: line MRP equals the price, GST stays inside, and the bill is
   * never above MRP × quantity.
   */
  @Test
  void atMrpRatePickedOnAnUnevenTabletLineStaysAtMrp() {
    RateDto rateM = rate("Rate-M", PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE, "100", "10",
        PricingConstants.GST_MODE_INCLUSIVE);
    stock("80", "100", "90", null, 15, rateM);
    add(1, null);
    switchUnit("TABLET");

    add(0, "Rate-M", "TABLET");

    PurchaseItem line = line();
    assertMoney("6.66", line.getPriceToRetail());
    assertMoney("6.66", line.getMaximumRetailPrice());
    assertEquals(PricingConstants.GST_MODE_INCLUSIVE, rateM.getGstMode());
    assertEquals(true, CheckoutUtils.isSellingAtMrp(line));
    assertEquals(15, line.getBaseQuantity());
    BigDecimal mrpTimesQuantity = line.getMaximumRetailPrice().multiply(line.getQuantity());
    org.junit.jupiter.api.Assertions.assertTrue(
        cart.getGrandTotal().compareTo(mrpTimesQuantity) <= 0, "grandTotal " + cart.getGrandTotal());
    org.junit.jupiter.api.Assertions.assertTrue(cart.getTaxTotal().signum() > 0, "GST stated inside");
    assertMoney("89.91", line.getTotalAmount());
  }

  /**
   * B2: with no rateName, the price on an incoming line is the batch's and disc/scheme the
   * request did not send are the batch's; a line already in the cart keeps its own. The shop's
   * default rate is a system one, the batch has its own 5+1 and sells at 90 (not PTR 100).
   */
  @Test
  void pickRateAThenPlusOneSendingOnlyDiscKeepsTheRateTerms() {
    stockWithBatchScheme();
    add(11, "Rate-A");

    AddToCartRequest.CartItem plus = item(1, null);
    plus.setSaleAdditionalDiscount(new BigDecimal("8"));
    send(plus);

    assertEquals(12, line().getQuantity().intValue());
    assertTerms("100", "8", 10, 1);
    assertEquals("Rate-A", line().getAppliedRateName());
  }

  @Test
  void pickRateBThenPlusOneAddsNoBatchScheme() {
    stockWithBatchScheme();
    add(11, "Rate-B");

    add(1, null);

    PurchaseItem line = line();
    assertEquals(12, line.getQuantity().intValue());
    assertMoney("100", line.getPriceToRetail());
    assertMoney("7", line.getSaleAdditionalDiscount());
    assertNull(line.getSchemeType());
    assertNull(line.getSchemePayFor());
    assertMoney("1116.00", beforeTax());
  }

  @Test
  void manualPriceEditThenPlusOneKeepsThePrice() {
    stockWithBatchScheme();
    add(1, null);
    assertMoney("90", line().getPriceToRetail());

    AddToCartRequest.CartItem edit = item(0, null);
    edit.setPriceToRetail(new BigDecimal("85"));
    send(edit);
    add(1, null);

    assertEquals(2, line().getQuantity().intValue());
    assertMoney("85", line().getPriceToRetail());
    assertEquals(5, line().getSchemePayFor());
  }

  /**
   * B4a: a system name as rateName puts that batch field on the line with the batch's own sale
   * disc and scheme, as a first add without rules gets, replacing a rule rate's terms.
   */
  @Test
  void systemPickAfterARuleRateResetsToTheBatchDefaults() {
    stockWithBatchScheme();
    batch.setSaleAdditionalDiscount(new BigDecimal("2"));
    add(11, "Rate-A");

    add(0, "priceToRetail");
    assertTerms("100", "2", 5, 1);
    assertEquals("priceToRetail", line().getAppliedRateName());

    add(0, "maximumRetailPrice");
    assertTerms("150", "2", 5, 1);
    assertEquals(true, CheckoutUtils.isSellingAtMrp(line()));
    assertEquals("maximumRetailPrice", line().getAppliedRateName());

    add(0, "costPrice");
    assertTerms("50", "2", 5, 1);
    assertEquals("costPrice", line().getAppliedRateName());
  }

  @Test
  void systemPickWithAUnitChangeIsRejected() {
    stockWithBatchScheme();
    add(11, null);

    AddToCartRequest.CartItem pickAndSwitch = item(0, "priceToRetail");
    pickAndSwitch.setUnit("TABLET");
    assertThrows(ValidationException.class, () -> send(pickAndSwitch));
  }

  /** T5: a stored hand-typed rate named like a system option never shadows it. */
  @Test
  void systemNameWinsOverAStoredRateOfTheSameName() {
    RateDto ptr = new RateDto("priceToRetail", new BigDecimal("120"));
    RateDto mrp = new RateDto("MRP", new BigDecimal("140"));
    stock("100", "150", "90", null, ptr, mrp);

    add(1, "priceToRetail");
    assertMoney("100", line().getPriceToRetail());

    add(0, "MRP");
    assertMoney("150", line().getPriceToRetail());
    assertEquals("maximumRetailPrice", line().getAppliedRateName());
  }

  /** A system pick on a tablet line takes the batch field per tablet, as a rule rate does. */
  @Test
  void systemPickOnATabletLineTakesThePerTabletValue() {
    stockWithBatchScheme();
    add(1, null);
    switchUnit("TABLET");

    add(0, "priceToRetail", "TABLET");

    assertMoney("10.00", line().getPriceToRetail());
    assertMoney("15.00", line().getMaximumRetailPrice());
  }

  /**
   * B4b: the line records the rate it is billed at -- a pick, a system pick, or the batch's
   * default rule rate on a first add -- and returns it on the cart.
   */
  @Test
  void appliedRateNameIsSetByPickSystemPickAndDefaultRuleRate() {
    stock("100", "150", "83.64", "Rate-A", rateA(), rateB());
    add(11, null);
    assertEquals("Rate-A", line().getAppliedRateName());

    add(0, "Rate-B");
    assertEquals("Rate-B", line().getAppliedRateName());

    add(1, null);
    assertEquals("Rate-B", line().getAppliedRateName());

    add(0, "priceToRetail");
    assertEquals("priceToRetail", line().getAppliedRateName());

    cart = null;
    stock("100", "150", "90", null, rateA());
    add(1, null);
    assertNull(line().getAppliedRateName());
  }

  /** T4: any manual price, disc or scheme edit clears the applied rate name. */
  @Test
  void manualPriceDiscOrSchemeEditClearsAppliedRateName() {
    stock("100", "150", "83.64", null, rateA());

    add(11, "Rate-A");
    AddToCartRequest.CartItem disc = item(0, null);
    disc.setSaleAdditionalDiscount(new BigDecimal("5"));
    send(disc);
    assertNull(line().getAppliedRateName());

    add(0, "Rate-A");
    AddToCartRequest.CartItem price = item(0, null);
    price.setPriceToRetail(new BigDecimal("95"));
    send(price);
    assertNull(line().getAppliedRateName());

    add(0, "Rate-A");
    AddToCartRequest.CartItem scheme = item(0, null);
    scheme.setSchemeType(SchemeType.FIXED_UNITS);
    scheme.setSchemePayFor(5);
    scheme.setSchemeFree(1);
    send(scheme);
    assertNull(line().getAppliedRateName());

    cart = null;
    AddToCartRequest.CartItem typed = item(11, null);
    typed.setSaleAdditionalDiscount(new BigDecimal("3"));
    stock("100", "150", "83.64", "Rate-A", rateA());
    send(typed);
    assertNull(line().getAppliedRateName());
  }

  /** PTR 100, MRP 150, sells at 90, batch sale scheme 5+1, default rate the system PTR. */
  private void stockWithBatchScheme() {
    stock("100", "150", "90", "priceToRetail", rateA(), rateB());
    batch.setSchemeType(SchemeType.FIXED_UNITS);
    batch.setSchemePayFor(5);
    batch.setSchemeFree(1);
  }

  private void switchUnit(String unit) {
    AddToCartRequest.CartItem item = item(0, null);
    item.setUnit(unit);
    item.setPriceToRetail(new BigDecimal("1"));
    send(item);
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
    stock(ptr, mrp, sell, defaultRate, 10, rates);
  }

  private void stock(String ptr, String mrp, String sell, String defaultRate, int pack,
      RateDto... rates) {
    batch = new Inventory();
    batch.setId(LOT);
    batch.setShopId(SHOP);
    batch.setName("Paracetamol 650");
    batch.setBaseUnit("TABLET");
    batch.setUnitConversions(new UnitConversion("STRIP", pack));
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
    item.setBaseQuantity(strips * batch.getUnitConversions().getFactor());
    item.setRateName(rateName);
    return item;
  }

  private void add(int strips, String rateName) {
    send(item(strips, rateName));
  }

  /** {@code quantity} of {@code unit} (TABLET: base units). */
  private void add(int quantity, String rateName, String unit) {
    AddToCartRequest.CartItem item = item(0, rateName);
    item.setUnit(unit);
    item.setQuantity(quantity);
    item.setBaseQuantity("TABLET".equals(unit) ? quantity : quantity * batch.getUnitConversions().getFactor());
    send(item);
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
