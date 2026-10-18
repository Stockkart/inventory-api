package com.inventory.product.service;

import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.service.ShopRateRulesPort;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.service.RbacService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Gives the pricing module the shop's rate rules and the rate-edit grant (L2). */
@Component
public class ShopRateRulesPortImpl implements ShopRateRulesPort {

  /** The one vertical that has no rate rules. */
  public static final String CAFE_VERTICAL = "cafe";

  private final ShopRateRulesRepository rulesRepository;
  private final ShopRepository shopRepository;
  private final RbacService rbacService;

  public ShopRateRulesPortImpl(
      ShopRateRulesRepository rulesRepository, ShopRepository shopRepository, RbacService rbacService) {
    this.rulesRepository = rulesRepository;
    this.shopRepository = shopRepository;
    this.rbacService = rbacService;
  }

  @Override
  public List<RateRuleSpec> rulesFor(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return List.of();
    }
    Optional<Shop> shop = shopRepository.findById(shopId);
    if (shop.isPresent() && CAFE_VERTICAL.equalsIgnoreCase(shop.get().getVerticalId())) {
      return List.of();
    }
    return rulesRepository.findByShopId(shopId)
        .map(ShopRateRulesDocument::getRules)
        .map(rules -> rules.stream()
            .filter(r -> r.getBase() != null && StringUtils.hasText(r.getName()))
            .map(r -> new RateRuleSpec(r.getName().trim(), r.getBase().value(),
                r.getDiscountPercent(), r.getScheme()))
            .toList())
        .orElseGet(List::of);
  }

  @Override
  public boolean isRetailer(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return false;
    }
    return shopRepository.findById(shopId)
        .map(s -> s.getShopType() == ShopType.RETAILER)
        .orElse(false);
  }

  @Override
  public boolean canEditRates(String userId, String shopId) {
    return rbacService.canEditRates(userId, shopId);
  }
}
