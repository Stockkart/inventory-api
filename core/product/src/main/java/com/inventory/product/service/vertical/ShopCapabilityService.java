package com.inventory.product.service.vertical;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.capabilities.DefaultSkuScanUiContributor;
import com.inventory.pluginengine.capabilities.FeatureFlags;
import com.inventory.pluginengine.capabilities.ShopUiCapabilities;
import com.inventory.pluginengine.capabilities.VerticalUiContributor;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ShopCapabilityService {

  private final ShopRepository shopRepository;
  private final SchemaLoader schemaLoader;
  private final PluginRegistry pluginRegistry;
  private final DefaultSkuScanUiContributor defaultSkuScanUiContributor;
  private final ShopValidator shopValidator;
  private final UserShopMembershipService membershipService;

  public ShopCapabilityService(
      ShopRepository shopRepository,
      SchemaLoader schemaLoader,
      PluginRegistry pluginRegistry,
      DefaultSkuScanUiContributor defaultSkuScanUiContributor,
      ShopValidator shopValidator,
      UserShopMembershipService membershipService) {
    this.shopRepository = shopRepository;
    this.schemaLoader = schemaLoader;
    this.pluginRegistry = pluginRegistry;
    this.defaultSkuScanUiContributor = defaultSkuScanUiContributor;
    this.shopValidator = shopValidator;
    this.membershipService = membershipService;
  }

  public ShopUiCapabilities getShopCapabilities(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    if (!StringUtils.hasText(shop.getVerticalId())) {
      throw new ValidationException("Shop has no vertical configured");
    }
    VerticalSchema schema =
        schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
    VerticalUiContributor contributor = resolveUiContributor(shop.getVerticalId());
    return contributor.contribute(schema);
  }

  public boolean isCustomerReturnEnabled(String shopId, String userId) {
    FeatureFlags features = getShopCapabilities(shopId, userId).getFeatures();
    return features == null || features.isCustomerReturn();
  }

  public boolean isVendorReturnEnabled(String shopId, String userId) {
    FeatureFlags features = getShopCapabilities(shopId, userId).getFeatures();
    return features == null || features.isVendorReturn();
  }

  public void requireCustomerReturn(String shopId, String userId) {
    if (!isCustomerReturnEnabled(shopId, userId)) {
      throw new ValidationException(
          "Customer returns are not supported for this business type");
    }
  }

  public void requireVendorReturn(String shopId, String userId) {
    if (!isVendorReturnEnabled(shopId, userId)) {
      throw new ValidationException(
          "Vendor returns are not supported for this business type");
    }
  }

  private VerticalUiContributor resolveUiContributor(String verticalId) {
    return pluginRegistry
        .find(verticalId)
        .flatMap(VerticalPlugin::getUiContributor)
        .orElse(defaultSkuScanUiContributor);
  }
}
