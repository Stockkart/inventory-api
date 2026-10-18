package com.inventory.product.service;

import static com.inventory.product.service.RateRulesTestFixtures.OWNER;
import static com.inventory.product.service.RateRulesTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.RateRule;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.model.enums.RateBase;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.service.RbacService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShopRateRulesPortImplTest {

  @Mock private ShopRepository shopRepository;
  @Mock private RbacService rbacService;

  private final InMemoryShopRateRulesRepository rules = new InMemoryShopRateRulesRepository();
  private ShopRateRulesPortImpl port;

  @BeforeEach
  void setUp() {
    port = new ShopRateRulesPortImpl(rules.asRepository(), shopRepository, rbacService);
  }

  private void saveOneRule() {
    ShopRateRulesDocument doc = new ShopRateRulesDocument();
    doc.setShopId(SHOP);
    RateRule rule = new RateRule();
    rule.setName("Rate-A");
    rule.setBase(RateBase.PRICE_TO_RETAIL);
    rule.setDiscountPercent(new BigDecimal("8"));
    doc.setRules(List.of(rule));
    rules.asRepository().save(doc);
  }

  @Test
  void rulesAreReturnedAsCalculatorSpecs() {
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.DISTRIBUTOR, "pharmacy")));
    saveOneRule();

    List<RateRuleSpec> specs = port.rulesFor(SHOP);

    assertEquals(1, specs.size());
    assertEquals("Rate-A", specs.get(0).name());
    assertEquals(PricingConstants.RATE_BASE_PRICE_TO_RETAIL, specs.get(0).base());
    assertEquals(new BigDecimal("8"), specs.get(0).discountPercent());
  }

  @Test
  void cafeShopGetsNoRules() {
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.RETAILER, "cafe")));
    saveOneRule();

    assertTrue(port.rulesFor(SHOP).isEmpty());
  }

  @Test
  void missingDocumentGivesNoRules() {
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.DISTRIBUTOR, "pharmacy")));

    assertTrue(port.rulesFor(SHOP).isEmpty());
  }

  @Test
  void isRetailerFollowsTheShopType() {
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.RETAILER, "pharmacy")));
    assertTrue(port.isRetailer(SHOP));
    when(shopRepository.findById(SHOP))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.WHOLESALER, "pharmacy")));
    assertFalse(port.isRetailer(SHOP));
  }

  @Test
  void canEditRatesFollowsRbac() {
    when(rbacService.canEditRates(OWNER, SHOP)).thenReturn(true);
    when(rbacService.canEditRates("cashier-1", SHOP)).thenReturn(false);

    assertTrue(port.canEditRates(OWNER, SHOP));
    assertFalse(port.canEditRates("cashier-1", SHOP));
  }
}
