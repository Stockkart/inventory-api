package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.capabilities.DefaultSkuScanUiContributor;
import com.inventory.pluginengine.capabilities.FeatureFlags;
import com.inventory.pluginengine.capabilities.ShopUiCapabilities;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.RateRule;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.model.enums.RateBase;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.RateRulesTestFixtures;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShopCapabilityServiceRateRulesTest {

  private static final String SHOP = RateRulesTestFixtures.SHOP;

  private final ShopRepository shopRepository = mock(ShopRepository.class);
  private final SchemaLoader schemaLoader = mock(SchemaLoader.class);
  private final DefaultSkuScanUiContributor contributor = mock(DefaultSkuScanUiContributor.class);
  private final UserShopMembershipService membershipService = mock(UserShopMembershipService.class);
  private final ShopRateRulesRepository rulesRepository = mock(ShopRateRulesRepository.class);
  private ShopCapabilityService service;

  @BeforeEach
  void setUp() {
    service = new ShopCapabilityService(shopRepository, schemaLoader, mock(PluginRegistry.class),
        contributor, mock(ShopValidator.class), membershipService, rulesRepository);
    when(membershipService.hasAccess("u1", SHOP)).thenReturn(true);
    when(schemaLoader.load(any(), any())).thenReturn(mock(VerticalSchema.class));
    when(contributor.contribute(any())).thenAnswer(
        inv -> ShopUiCapabilities.builder().features(FeatureFlags.builder().build()).build());
  }

  private void shop(String vertical) {
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.DISTRIBUTOR, vertical)));
  }

  private void rules(int count) {
    ShopRateRulesDocument doc = new ShopRateRulesDocument();
    doc.setShopId(SHOP);
    RateRule rule = new RateRule();
    rule.setName("Rate-A");
    rule.setBase(RateBase.PRICE_TO_RETAIL);
    rule.setDiscountPercent(new BigDecimal("8"));
    doc.setRules(count == 0 ? List.of() : List.of(rule));
    when(rulesRepository.findByShopId(SHOP)).thenReturn(Optional.of(doc));
  }

  @Test
  void rateRulesFlagTrueOnlyWithRules() {
    shop("pharmacy");
    when(rulesRepository.findByShopId(SHOP)).thenReturn(Optional.empty());
    assertFalse(service.getShopCapabilities(SHOP, "u1").getFeatures().isRateRules());

    rules(0);
    assertFalse(service.getShopCapabilities(SHOP, "u1").getFeatures().isRateRules());

    rules(1);
    assertTrue(service.getShopCapabilities(SHOP, "u1").getFeatures().isRateRules());
  }

  @Test
  void rateRulesFlagIsFalseForCafeEvenWithRules() {
    shop("cafe");
    rules(1);

    assertFalse(service.getShopCapabilities(SHOP, "u1").getFeatures().isRateRules());
  }
}
