package com.inventory.product.service.api;

import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.api.port.ShopRateRulesPort;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.api.ShopPermissions;
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
  private final ShopPermissions shopPermissions;

  public ShopRateRulesPortImpl(
      ShopRateRulesRepository rulesRepository, ShopRepository shopRepository,
      ShopPermissions shopPermissions) {
    this.rulesRepository = rulesRepository;
    this.shopRepository = shopRepository;
    this.shopPermissions = shopPermissions;
  }

  @Override
  public List<ShopRateRule> rulesFor(String shopId) {
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
            .map(r -> new ShopRateRule(r.getName().trim(), r.getBase().value(),
                r.getDiscountPercent(), r.getScheme()))
            .toList())
        .orElseGet(List::of);
  }

  @Override
  public boolean canEditRates(String shopId, String userId) {
    return shopPermissions.canEditRates(shopId, userId);
  }
}
