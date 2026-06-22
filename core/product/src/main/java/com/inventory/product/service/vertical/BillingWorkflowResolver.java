package com.inventory.product.service.vertical;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.cart.CartBuildContext;
import com.inventory.pluginengine.cart.CartLineContributor;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BillingWorkflowResolver {

  public static final String WORKFLOW_MENU_LIST = "menu-list";
  public static final String WORKFLOW_SKU_LIST = "sku-list";

  private final ShopRepository shopRepository;
  private final SchemaLoader schemaLoader;

  public BillingWorkflowResolver(ShopRepository shopRepository, SchemaLoader schemaLoader) {
    this.shopRepository = shopRepository;
    this.schemaLoader = schemaLoader;
  }

  public boolean isMenuListBilling(String shopId) {
    return WORKFLOW_MENU_LIST.equalsIgnoreCase(resolveBillingMode(shopId));
  }

  public String resolveBillingMode(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      return WORKFLOW_SKU_LIST;
    }
    Shop shop = shopRepository.findById(shopId).orElse(null);
    if (shop == null || !StringUtils.hasText(shop.getVerticalId())) {
      return WORKFLOW_SKU_LIST;
    }
    VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
    if (schema.getWorkflows() == null) {
      return WORKFLOW_SKU_LIST;
    }
    Object mode = schema.getWorkflows().get("billingMode");
    return mode != null ? String.valueOf(mode).trim() : WORKFLOW_SKU_LIST;
  }
}
