package com.inventory.product.service;

import static com.inventory.product.service.RateRulesTestFixtures.CASHIER;
import static com.inventory.product.service.RateRulesTestFixtures.OWNER;
import static com.inventory.product.service.RateRulesTestFixtures.SHOP;
import static com.inventory.product.service.RateRulesTestFixtures.dec;
import static com.inventory.product.service.RateRulesTestFixtures.rateA;
import static com.inventory.product.service.RateRulesTestFixtures.rateS;
import static com.inventory.product.service.RateRulesTestFixtures.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.service.RateGenerationService;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.port.ShopRateRulesPort;
import com.inventory.pricing.service.api.RateRuleEngineImpl;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.validation.PricingValidator;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.RateRulesMapper;
import com.inventory.product.mapper.RateRulesMapperImpl;
import com.inventory.product.rest.dto.request.PreviewRateRulesRequest;
import com.inventory.product.rest.dto.request.UpdateRateRulesRequest;
import com.inventory.product.rest.dto.response.RateRulesPreviewResponse;
import com.inventory.product.rest.dto.response.RateRulesResponse;
import com.inventory.product.validation.RateRulesValidator;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.api.ShopPermissions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateRulesServiceTest {

  @Mock private ShopRepository shopRepository;
  @Mock private ShopValidator shopValidator;
  @Mock private ShopPermissions shopPermissions;
  @Mock private MetricsWrapper metrics;

  private final InMemoryShopRateRulesRepository rules = new InMemoryShopRateRulesRepository();
  private static final RateRuleEngineImpl ENGINE = new RateRuleEngineImpl(new PricingValidator());
  private final RateRulesMapper mapper = new RateRulesMapperImpl();
  private RateRulesService service;

  @BeforeEach
  void setUp() {
    service = new RateRulesService(rules.asRepository(), shopRepository, shopPermissions,
        shopValidator, ENGINE, new RateRulesValidator(ENGINE), mapper, metrics);
    when(shopPermissions.hasAccess(anyString(), anyString())).thenReturn(true);
    when(shopPermissions.canEditRates(SHOP, OWNER)).thenReturn(true);
    when(shopPermissions.canEditRates(SHOP, CASHIER)).thenReturn(false);
    shopIs(ShopType.DISTRIBUTOR, "pharmacy");
  }

  private void shopIs(ShopType type, String vertical) {
    when(shopRepository.findById(SHOP)).thenReturn(Optional.of(RateRulesTestFixtures.shop(type, vertical)));
  }

  private static UpdateRateRulesRequest update(com.inventory.product.rest.dto.response.RateRuleDto... r) {
    return new UpdateRateRulesRequest(List.of(r));
  }

  @Test
  void getReturnsRetailerBaseOptions() {
    shopIs(ShopType.RETAILER, "pharmacy");

    RateRulesResponse response = service.getRules(SHOP, OWNER);

    assertTrue(response.isEnabled());
    assertEquals(List.of("sellingPrice"), response.getBaseOptions());
    assertTrue(response.isCanEdit());
    assertTrue(response.getRules().isEmpty());
  }

  @Test
  void getReturnsPtrAndMrpOptionsForOthersAndNoEditForCashier() {
    RateRulesResponse response = service.getRules(SHOP, CASHIER);

    assertEquals(List.of("priceToRetail", "maximumRetailPrice"), response.getBaseOptions());
    assertFalse(response.isCanEdit());
  }

  @Test
  void getIsDisabledForCafe() {
    shopIs(ShopType.RETAILER, "cafe");

    RateRulesResponse response = service.getRules(SHOP, OWNER);

    assertFalse(response.isEnabled());
  }

  @Test
  void updateStampsAuditFields() {
    RateRulesResponse response = service.updateRules(SHOP, OWNER, update(rateA()));

    ShopRateRulesDocument saved = rules.stored(SHOP).orElseThrow();
    assertEquals(OWNER, saved.getUpdatedByUserId());
    assertNotNull(saved.getCreatedAt());
    assertNotNull(saved.getUpdatedAt());
    assertEquals(1, saved.getRules().size());
    assertEquals("priceToRetail", response.getRules().get(0).getBase());
    assertEquals(new BigDecimal("8"), response.getRules().get(0).getDiscountPercent());
  }

  @Test
  void updateKeepsCreatedAtOnTheSecondSave() {
    service.updateRules(SHOP, OWNER, update(rateA()));
    var created = rules.stored(SHOP).orElseThrow().getCreatedAt();

    service.updateRules(SHOP, OWNER, update(rateA(), rule("Rate-M", "maximumRetailPrice", "10", null)));

    assertEquals(created, rules.stored(SHOP).orElseThrow().getCreatedAt());
    assertEquals(2, rules.stored(SHOP).orElseThrow().getRules().size());
  }

  /**
   * Two first saves racing: the loser's insert hits the unique shopId index. It reloads the
   * winner's document and saves its rules onto it, once.
   */
  @Test
  void updateRetriesOnceOnTheDocumentAConcurrentFirstSaveCreated() {
    ShopRateRulesRepository repo = mock(ShopRateRulesRepository.class);
    ShopRateRulesDocument winner = new ShopRateRulesDocument();
    winner.setId("doc-1");
    winner.setShopId(SHOP);
    winner.setVersion(0L);
    when(repo.findByShopId(SHOP)).thenReturn(Optional.empty(), Optional.of(winner));
    when(repo.save(any(ShopRateRulesDocument.class)))
        .thenThrow(new DuplicateKeyException("E11000 duplicate key shopId"))
        .thenAnswer(i -> i.getArgument(0));
    service = serviceWith(repo);

    RateRulesResponse response = service.updateRules(SHOP, OWNER, update(rateA()));

    assertEquals(1, response.getRules().size());
    ArgumentCaptor<ShopRateRulesDocument> saved = ArgumentCaptor.forClass(ShopRateRulesDocument.class);
    verify(repo, times(2)).save(saved.capture());
    assertEquals("doc-1", saved.getAllValues().get(1).getId());
    assertEquals("Rate-A", saved.getAllValues().get(1).getRules().get(0).getName());
  }

  @Test
  void updateIsAConflictWhenTheRetryAlsoLoses() {
    ShopRateRulesRepository repo = mock(ShopRateRulesRepository.class);
    when(repo.findByShopId(SHOP)).thenReturn(Optional.empty());
    when(repo.save(any(ShopRateRulesDocument.class)))
        .thenThrow(new DuplicateKeyException("E11000 duplicate key shopId"));
    service = serviceWith(repo);

    BaseException e = assertThrows(BaseException.class,
        () -> service.updateRules(SHOP, OWNER, update(rateA())));

    assertEquals(ErrorCode.RATE_RULES_CONFLICT, e.getErrorCode());
    assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getErrorCode().getHttpStatus());
  }

  /** Two saves of the same document: the one holding a stale version gets a 409, not a lost write. */
  @Test
  void updateOnAStaleVersionIsAConflict() {
    ShopRateRulesRepository repo = mock(ShopRateRulesRepository.class);
    ShopRateRulesDocument doc = new ShopRateRulesDocument();
    doc.setId("doc-1");
    doc.setShopId(SHOP);
    doc.setVersion(3L);
    when(repo.findByShopId(SHOP)).thenReturn(Optional.of(doc));
    when(repo.save(any(ShopRateRulesDocument.class)))
        .thenThrow(new OptimisticLockingFailureException("version 3 is stale"));
    service = serviceWith(repo);

    BaseException e = assertThrows(BaseException.class,
        () -> service.updateRules(SHOP, OWNER, update(rateA())));

    assertEquals(ErrorCode.RATE_RULES_CONFLICT, e.getErrorCode());
  }

  private RateRulesService serviceWith(ShopRateRulesRepository repo) {
    return new RateRulesService(repo, shopRepository, shopPermissions,
        shopValidator, ENGINE, new RateRulesValidator(ENGINE), mapper, metrics);
  }

  @Test
  void updateRulesWithoutGrantIsForbidden() {
    BaseException e = assertThrows(BaseException.class,
        () -> service.updateRules(SHOP, CASHIER, update(rateA())));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    assertTrue(rules.stored(SHOP).isEmpty());
  }

  @Test
  void updateRejectsAnInvalidRuleBeforeSaving() {
    BaseException e = assertThrows(BaseException.class,
        () -> service.updateRules(SHOP, OWNER, update(rule("Rate-A", "priceToRetail", null, null))));

    assertEquals(ErrorCode.RATE_RULE_INVALID, e.getErrorCode());
    assertTrue(rules.stored(SHOP).isEmpty());
  }

  @Test
  void updateIsRefusedForCafe() {
    shopIs(ShopType.RETAILER, "cafe");

    BaseException e = assertThrows(BaseException.class,
        () -> service.updateRules(SHOP, OWNER, update(rateS())));

    assertEquals(ErrorCode.RATE_RULE_INVALID, e.getErrorCode());
  }

  private static PreviewRateRulesRequest previewRequest() {
    PreviewRateRulesRequest request = new PreviewRateRulesRequest();
    request.setPriceToRetail(dec("100"));
    request.setMaximumRetailPrice(dec("120"));
    return request;
  }

  @Test
  void previewUsesUnsavedRulesWhenGiven() {
    service.updateRules(SHOP, OWNER, update(rateA()));
    PreviewRateRulesRequest request = previewRequest();
    request.setRules(List.of(rule("Unsaved", "priceToRetail", "10", null)));

    RateRulesPreviewResponse response = service.preview(SHOP, OWNER, request);

    assertEquals(1, response.getRates().size());
    assertEquals("Unsaved", response.getRates().get(0).getName());
    assertEquals(0, dec("90.00").compareTo(response.getRates().get(0).getPrice()));
  }

  @Test
  void previewFallsBackToSavedRules() {
    service.updateRules(SHOP, OWNER, update(rateA()));

    RateRulesPreviewResponse response = service.preview(SHOP, CASHIER, previewRequest());

    assertEquals(1, response.getRates().size());
    assertEquals("Rate-A", response.getRates().get(0).getName());
  }

  @Test
  void previewTermsTextForDiscAndScheme() {
    PreviewRateRulesRequest request = previewRequest();
    request.setRules(List.of(rateA()));

    RateRulesPreviewResponse.PreviewRate rate = service.preview(SHOP, OWNER, request).getRates().get(0);

    assertEquals("PTR 100.00 − 8% · 10+1", rate.getTermsText());
    assertEquals(0, dec("92.00").compareTo(rate.getAfterDiscount()));
    assertEquals(0, dec("83.64").compareTo(rate.getPrice()));
    assertEquals(0, dec("100").compareTo(rate.getBaseValue()));
    assertEquals(PricingConstants.GST_MODE_EXCLUSIVE, rate.getGstMode());
    assertEquals("priceToRetail", rate.getBase());
  }

  @Test
  void previewBelowCostMatchesSaveForSchemeAndTaxInclusiveCost() {
    List<com.inventory.product.rest.dto.response.RateRuleDto> ruleDtos = List.of(
        rule("Deep", "priceToRetail", "30", null), rule("Shallow", "priceToRetail", "20", null));
    PreviewRateRulesRequest request = previewRequest();
    request.setCostPrice(dec("90"));
    request.setCostPriceIncludesTax(true);
    request.setPurchaseAdditionalDiscount(null);
    request.setPurchaseScheme(new SchemeTerms(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null));
    request.setSgst("6");
    request.setCgst("6");
    request.setRules(ruleDtos);

    RateRulesPreviewResponse preview = service.preview(SHOP, OWNER, request);

    // The same inputs saved through RateGenerationService.
    ShopRateRulesPort port = mock(ShopRateRulesPort.class);
    when(port.rulesFor(SHOP)).thenReturn(List.of(
        new ShopRateRule("Deep", "priceToRetail", dec("30"), null),
        new ShopRateRule("Shallow", "priceToRetail", dec("20"), null)));
    RateGenerationService generation = new RateGenerationService();
    ReflectionTestUtils.setField(generation, "shopRateRulesPort", port);
    ReflectionTestUtils.setField(generation, "metrics", metrics);
    ReflectionTestUtils.setField(generation, "pricingValidator", new PricingValidator());
    Pricing pricing = new Pricing();
    pricing.setShopId(SHOP);
    pricing.setPriceToRetail(dec("100"));
    pricing.setMaximumRetailPrice(dec("120"));
    pricing.setSellingPrice(dec("100"));
    pricing.setCostPrice(dec("90"));
    pricing.setCostPriceIncludesTax(true);
    pricing.setPurchaseScheme(new Scheme(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 10, 1, null));
    pricing.setSgst("6");
    pricing.setCgst("6");
    pricing.setDefaultRate(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL);
    generation.apply(pricing, null, null, OWNER);

    assertEquals(List.of("Deep"), preview.getSkipped().stream().map(s -> s.getName()).toList());
    assertEquals("BELOW_COST", preview.getSkipped().get(0).getReason());
    assertEquals(List.of("Shallow"), preview.getRates().stream().map(r -> r.getName()).toList());
    assertEquals(List.of("Shallow"), pricing.getRates().stream().map(Rate::getName).toList());
  }

  @Test
  void previewForARetailerMirrorsSellingPriceIntoMrp() {
    shopIs(ShopType.RETAILER, "pharmacy");
    PreviewRateRulesRequest request = new PreviewRateRulesRequest();
    request.setSellingPrice(dec("100"));
    request.setRules(List.of(rateS()));

    RateRulesPreviewResponse.PreviewRate rate = service.preview(SHOP, OWNER, request).getRates().get(0);

    assertEquals(0, dec("95.00").compareTo(rate.getPrice()));
    assertEquals(PricingConstants.GST_MODE_INCLUSIVE, rate.getGstMode());
  }
}
