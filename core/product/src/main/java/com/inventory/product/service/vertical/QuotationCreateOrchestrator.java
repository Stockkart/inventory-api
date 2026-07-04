package com.inventory.product.service.vertical;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.cart.QuotationCreateContext;
import com.inventory.pluginengine.cart.QuotationCreateHandler;
import com.inventory.pluginengine.cart.QuotationCreateResult;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class QuotationCreateOrchestrator {

  private final ShopRepository shopRepository;
  private final PluginRegistry pluginRegistry;

  public QuotationCreateOrchestrator(
      ShopRepository shopRepository, PluginRegistry pluginRegistry) {
    this.shopRepository = shopRepository;
    this.pluginRegistry = pluginRegistry;
  }

  public Optional<String> allocateTokenForNewQuotation(String shopId, String businessType) {
    return resolveHandler(shopId, businessType)
        .map(
            handler ->
                handler
                    .onQuotationCreated(
                        QuotationCreateContext.builder()
                            .shopId(shopId)
                            .businessType(businessType)
                            .verticalId(handler.getVerticalId())
                            .build())
                    .getTokenNo())
        .filter(StringUtils::hasText);
  }

  public Optional<String> ensureQuotationToken(String shopId, String businessType) {
    return allocateTokenForNewQuotation(shopId, businessType);
  }

  private Optional<QuotationCreateHandler> resolveHandler(String shopId, String businessType) {
    if (!StringUtils.hasText(shopId)) {
      return Optional.empty();
    }
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return Optional.empty();
    }
    if (StringUtils.hasText(businessType)
        && !businessType.equalsIgnoreCase(shop.getVerticalId())) {
      return Optional.empty();
    }
    return pluginRegistry
        .find(shop.getVerticalId())
        .flatMap(VerticalPlugin::getQuotationCreateHandler);
  }
}
