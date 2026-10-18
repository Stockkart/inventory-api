package com.inventory.pricing.service;

import static com.inventory.pricing.service.RateGenerationTestFixtures.CASHIER;
import static com.inventory.pricing.service.RateGenerationTestFixtures.OWNER;
import static com.inventory.pricing.service.RateGenerationTestFixtures.SHOP;
import static com.inventory.pricing.service.RateGenerationTestFixtures.dec;
import static com.inventory.pricing.service.RateGenerationTestFixtures.find;
import static com.inventory.pricing.service.RateGenerationTestFixtures.namePrice;
import static com.inventory.pricing.service.RateGenerationTestFixtures.rateA;
import static com.inventory.pricing.service.RateGenerationTestFixtures.stored;
import static com.inventory.pricing.service.RateGenerationTestFixtures.storedRateA100;
import static com.inventory.pricing.service.RateGenerationTestFixtures.withDisc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.pricing.mapper.PricingMapper;
import com.inventory.pricing.mapper.PricingMapperImpl;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceItem;
import com.inventory.pricing.rest.dto.request.UpdateDefaultPriceRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.rest.dto.response.PricingResponse;
import com.inventory.pricing.utils.RateRuleCalculator;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.validation.PricingValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingServiceRateRulesTest {

  @Mock private PricingRepository pricingRepository;
  @Mock private VerticalPricingPolicyPort pricingPolicyPort;
  @Mock private ShopRateRulesPort port;
  @Mock private MetricsWrapper metrics;
  @Spy private PricingMapper pricingMapper = new PricingMapperImpl();
  @Spy private PricingValidator pricingValidator = new PricingValidator();
  @InjectMocks private PricingService pricingService;

  private RateGenerationService rateGenerationService;

  @BeforeEach
  void setUp() {
    rateGenerationService = new RateGenerationService();
    ReflectionTestUtils.setField(rateGenerationService, "shopRateRulesPort", port);
    ReflectionTestUtils.setField(rateGenerationService, "metrics", metrics);
    ReflectionTestUtils.setField(rateGenerationService, "pricingValidator", pricingValidator);
    ReflectionTestUtils.setField(pricingService, "rateGenerationService", rateGenerationService);
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateA()));
    when(port.canEditRates(OWNER, SHOP)).thenReturn(true);
    when(port.canEditRates(CASHIER, SHOP)).thenReturn(false);
    when(pricingRepository.save(any(Pricing.class))).thenAnswer(i -> i.getArgument(0));
  }

  private CreatePricingRequest createRequest() {
    CreatePricingRequest req = new CreatePricingRequest();
    req.setShopId(SHOP);
    req.setPriceToRetail(dec("100"));
    req.setMaximumRetailPrice(dec("150"));
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "999"))));
    return req;
  }

  private Pricing givenStored(Pricing p) {
    when(pricingRepository.findById(p.getId())).thenReturn(Optional.of(p));
    return p;
  }

  private Pricing saved() {
    ArgumentCaptor<Pricing> captor = ArgumentCaptor.forClass(Pricing.class);
    verify(pricingRepository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void createGeneratesRatesFromRules() {
    CreatePricingRequest req = createRequest();

    pricingService.createAndReturnEntity(req);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertFalse(a.getManual());
  }

  @Test
  void generationRunsBeforeTheVerticalPolicy() {
    CreatePricingRequest req = createRequest();
    when(pricingRepository.save(any(Pricing.class))).thenAnswer(i -> i.getArgument(0));
    org.mockito.Mockito.doAnswer(i -> {
      CreatePricingRequest seen = i.getArgument(0);
      assertEquals(dec("83.64"), find(seen.getRates(), "Rate-A").getPrice());
      return null;
    }).when(pricingPolicyPort).validateAndNormalizeCreate(any());

    pricingService.createAndReturnEntity(req);

    verify(pricingPolicyPort).validateAndNormalizeCreate(req);
  }

  @Test
  void createByUserWithoutGrantSucceeds() {
    CreatePricingRequest req = createRequest();
    req.setRates(new ArrayList<>(List.of(withDisc("Rate-A", "1", "50"))));

    pricingService.createAndReturnEntity(req);

    assertEquals(dec("83.64"), find(saved().getRates(), "Rate-A").getPrice());
    verify(port, never()).canEditRates(anyString(), anyString());
  }

  @Test
  void noRulesStoresSentRatesAsManual() {
    when(port.rulesFor(SHOP)).thenReturn(List.of());
    CreatePricingRequest req = createRequest();

    pricingService.createAndReturnEntity(req);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("999"), a.getPrice());
    assertTrue(a.getManual());
  }

  @Test
  void noPortStoresSentRatesAsManual() {
    ReflectionTestUtils.setField(rateGenerationService, "shopRateRulesPort", null);
    Pricing p = givenStored(stored("p1", "100", "150", null, namePrice("Rate-X", "70")));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-X", "75"))));

    pricingService.updateDefaultPrice("p1", req, SHOP, CASHIER);

    Rate x = find(saved().getRates(), "Rate-X");
    assertEquals(dec("75"), x.getPrice());
    assertTrue(x.getManual());
    assertEquals(p, saved());
  }

  @Test
  void ptrEditOnInventoryUpdateRecalculatesNonManualRates() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setPriceToRetail(dec("110"));

    pricingService.update("p1", req, null, CASHIER);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("92.00"), a.getPrice());
    assertEquals(dec("110"), saved().getPriceToRetail());
  }

  @Test
  void updateNotTouchingRatesOrBasesSkipsGeneration() {
    Pricing p = givenStored(stored("p1", "100", "150", "90", storedRateA100()));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setSgst("6");

    pricingService.update("p1", req, null, CASHIER);

    assertEquals(dec("83.64"), find(saved().getRates(), "Rate-A").getPrice());
    verify(port, never()).rulesFor(anyString());
    assertEquals(p, saved());
  }

  @Test
  void updateDefaultPriceOverrideByOwnerIsKept() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(withDisc("Rate-A", "83.64", "5"))));

    pricingService.updateDefaultPrice("p1", req, SHOP, OWNER);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("86.36"), a.getPrice());
    assertTrue(a.getManual());
  }

  @Test
  void everyWritePathEnforcesGrant() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));

    UpdatePricingRequest update = new UpdatePricingRequest();
    update.setRates(new ArrayList<>(List.of(withDisc("Rate-A", "83.64", "5"))));
    assertDenied(() -> pricingService.update("p1", update, null, CASHIER));

    UpdateDefaultPriceRequest patch = new UpdateDefaultPriceRequest();
    patch.setRates(new ArrayList<>(List.of(withDisc("Rate-A", "83.64", "5"))));
    assertDenied(() -> pricingService.updateDefaultPrice("p1", patch, SHOP, CASHIER));

    UpdateDefaultPriceItem item = new UpdateDefaultPriceItem();
    item.setPricingId("p1");
    item.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "83.64"))));
    item.setResetRates(List.of("Rate-A"));
    assertDenied(() -> pricingService.bulkUpdateDefaultPrice(List.of(item), SHOP, CASHIER));

    UpdatePricingRequest reset = new UpdatePricingRequest();
    reset.setResetRates(List.of("Rate-A"));
    assertDenied(() -> pricingService.update("p1", reset, null, CASHIER));

    verify(pricingRepository, never()).save(any(Pricing.class));
  }

  @Test
  void bulkResetByOwnerRegeneratesNamedRate() {
    Rate override = RateGenerationTestFixtures.storedOverrideFivePercent();
    givenStored(stored("p1", "100", "150", null, override));
    UpdateDefaultPriceItem item = new UpdateDefaultPriceItem();
    item.setPricingId("p1");
    item.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "86.36"))));
    item.setResetRates(List.of("Rate-A"));

    pricingService.bulkUpdateDefaultPrice(List.of(item), SHOP, OWNER);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertFalse(a.getManual());
  }

  @Test
  void resetOnlyPatchByOwnerRegeneratesNamedRate() {
    givenStored(stored("p1", "100", "150", null, RateGenerationTestFixtures.storedOverrideFivePercent()));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setResetRates(List.of("Rate-A"));

    pricingService.updateDefaultPrice("p1", req, SHOP, OWNER);

    Rate a = find(saved().getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertFalse(a.getManual());
  }

  @Test
  void sellingPriceFollowsRegeneratedDefaultRateOnUpdate() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100());
    p.setDefaultRate("Rate-A");
    p.setSellingPrice(dec("83.64"));
    givenStored(p);
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setPriceToRetail(dec("110"));

    pricingService.update("p1", req, null, CASHIER);

    assertEquals(dec("92.00"), saved().getSellingPrice());
  }

  @Test
  void defaultRateOnSkippedRuleFallsBackWithoutThrowing() {
    Pricing p = stored("p1", "100", "150", "90", storedRateA100());
    p.setDefaultRate("Rate-A");
    p.setSellingPrice(dec("83.64"));
    givenStored(p);
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setPriceToRetail(dec("100"));

    pricingService.update("p1", req, null, CASHIER);

    assertEquals("priceToRetail", saved().getDefaultRate());
    assertEquals(dec("100"), saved().getSellingPrice());
    assertNull(find(saved().getRates(), "Rate-A"));
  }

  /** Rate-S = selling price − 10%, the only base a retailer's rules may use. */
  private static RateRuleSpec rateS() {
    return new RateRuleSpec("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, dec("10"), null);
  }

  private static Rate storedRateS100() {
    return RateGenerationTestFixtures.generated("Rate-S", "90.00", PricingConstants.RATE_BASE_SELLING_PRICE,
        "100", "10", null, false, PricingConstants.GST_MODE_EXCLUSIVE);
  }

  @Test
  void retailerPatchDefaultRateOnSellingPriceRateFallsBack() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateS()));
    givenStored(stored("p1", "100", "150", null, storedRateS100()));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setDefaultRate("Rate-S");

    pricingService.updateDefaultPrice("p1", req, SHOP, CASHIER);

    assertEquals(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL, saved().getDefaultRate());
    assertEquals(dec("100"), saved().getSellingPrice());
  }

  @Test
  void retailerUpdateDefaultRateOnSellingPriceRateFallsBack() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateS()));
    givenStored(stored("p1", "100", "150", null, storedRateS100()));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setDefaultRate("Rate-S");

    pricingService.update("p1", req, null, CASHIER);

    assertEquals(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL, saved().getDefaultRate());
    assertEquals(dec("100"), saved().getSellingPrice());
  }

  /** A client-typed rate with rule fields: only its name and price may be trusted. */
  private static Rate spoofedOrphan() {
    return RateGenerationTestFixtures.generated("Orphan", "120", PricingConstants.RATE_BASE_PRICE_TO_RETAIL,
        "500", "3", RateGenerationTestFixtures.tenPlusOne(), false, PricingConstants.GST_MODE_INCLUSIVE);
  }

  private static void assertStripped(Rate r) {
    assertEquals(dec("120"), r.getPrice());
    assertTrue(r.getManual());
    assertNull(r.getBase());
    assertNull(r.getBaseValue());
    assertNull(r.getDiscountPercent());
    assertNull(r.getScheme());
    assertNull(r.getGstMode());
  }

  @Test
  void orphanRateIsStoredWithNameAndPriceOnly() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "83.64"), spoofedOrphan())));

    pricingService.updateDefaultPrice("p1", req, SHOP, CASHIER);

    assertStripped(find(saved().getRates(), "Orphan"));
  }

  @Test
  void orphanRateOnInventoryUpdateIsStoredWithNameAndPriceOnly() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "83.64"), spoofedOrphan())));

    pricingService.update("p1", req, null, CASHIER);

    assertStripped(find(saved().getRates(), "Orphan"));
  }

  @Test
  void noRulesPatchStoresRatesWithNameAndPriceOnly() {
    when(port.rulesFor(SHOP)).thenReturn(List.of());
    givenStored(stored("p1", "100", "150", null));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(spoofedOrphan())));

    pricingService.updateDefaultPrice("p1", req, SHOP, CASHIER);

    assertStripped(find(saved().getRates(), "Orphan"));
  }

  @Test
  void noRulesInventoryUpdateStoresRatesWithNameAndPriceOnly() {
    when(port.rulesFor(SHOP)).thenReturn(List.of());
    givenStored(stored("p1", "100", "150", null));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setRates(new ArrayList<>(List.of(spoofedOrphan())));

    pricingService.update("p1", req, null, CASHIER);

    assertStripped(find(saved().getRates(), "Orphan"));
  }

  @Test
  void noRulesCreateStoresRatesWithNameAndPriceOnly() {
    when(port.rulesFor(SHOP)).thenReturn(List.of());
    CreatePricingRequest req = createRequest();
    req.setRates(new ArrayList<>(List.of(spoofedOrphan())));

    pricingService.createAndReturnEntity(req);

    assertStripped(find(saved().getRates(), "Orphan"));
  }

  @Test
  void belowCostOverrideWarningIsInThePatchResponse() {
    Pricing p = stored("p1", "100", "150", "90", RateGenerationTestFixtures.storedOverrideFivePercent());
    givenStored(p);
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "86.36"))));

    PricingResponse response = pricingService.updateDefaultPrice("p1", req, SHOP, OWNER);

    assertEquals(List.of(new SkippedRateRule("Rate-A", RateRuleCalculator.REASON_BELOW_COST)),
        response.getWarnings());
    assertEquals(dec("86.36"), find(response.getRates(), "Rate-A").getPrice());
  }

  @Test
  void belowCostOverrideWarningIsInTheBulkResponse() {
    givenStored(stored("p1", "100", "150", "90", RateGenerationTestFixtures.storedOverrideFivePercent()));
    UpdateDefaultPriceItem item = new UpdateDefaultPriceItem();
    item.setPricingId("p1");
    item.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "86.36"))));

    List<PricingResponse> responses = pricingService.bulkUpdateDefaultPrice(List.of(item), SHOP, OWNER);

    assertEquals(List.of(new SkippedRateRule("Rate-A", RateRuleCalculator.REASON_BELOW_COST)),
        responses.get(0).getWarnings());
  }

  @Test
  void belowCostOverrideWarningIsInTheUpdateResponse() {
    givenStored(stored("p1", "100", "150", "90", RateGenerationTestFixtures.storedOverrideFivePercent()));
    UpdatePricingRequest req = new UpdatePricingRequest();
    req.setPriceToRetail(dec("100"));

    PricingResponse response = pricingService.update("p1", req, null, CASHIER);

    assertEquals(List.of(new SkippedRateRule("Rate-A", RateRuleCalculator.REASON_BELOW_COST)),
        response.getWarnings());
  }

  @Test
  void noWarningsLeavesTheFieldEmpty() {
    givenStored(stored("p1", "100", "150", null, storedRateA100()));
    UpdateDefaultPriceRequest req = new UpdateDefaultPriceRequest();
    req.setRates(new ArrayList<>(List.of(namePrice("Rate-A", "83.64"))));

    PricingResponse response = pricingService.updateDefaultPrice("p1", req, SHOP, CASHIER);

    assertTrue(response.getWarnings() == null || response.getWarnings().isEmpty());
  }

  private static void assertDenied(org.junit.jupiter.api.function.Executable call) {
    BaseException e = assertThrows(BaseException.class, call);
    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
  }
}
