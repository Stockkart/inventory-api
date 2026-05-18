package com.inventory.product.service;

import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.profile.BusinessProfileMapper;
import com.inventory.product.profile.ProfileResolver;
import com.inventory.product.rest.dto.response.BusinessProfileResponse;
import com.inventory.product.validation.InventoryValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BusinessProfileService {

  private final ProfileResolver profileResolver;
  private final BusinessProfileMapper businessProfileMapper;
  private final InventoryValidator inventoryValidator;

  public BusinessProfileService(
      ProfileResolver profileResolver,
      BusinessProfileMapper businessProfileMapper,
      InventoryValidator inventoryValidator) {
    this.profileResolver = profileResolver;
    this.businessProfileMapper = businessProfileMapper;
    this.inventoryValidator = inventoryValidator;
  }

  public BusinessProfileResponse getForShop(String shopId) {
    inventoryValidator.validateShopId(shopId);
    BusinessProfile profile = profileResolver.resolveForShop(shopId);
    return BusinessProfileResponse.builder()
        .id(profile.getId())
        .code(profile.getCode())
        .name(profile.getName())
        .version(profile.getVersion())
        .modules(profile.getModules())
        .entities(businessProfileMapper.entitiesToMap(profile.getEntities()))
        .pricing(profile.getPricing())
        .strategies(profile.getStrategies())
        .compliance(profile.getCompliance())
        .ui(profile.getUi())
        .build();
  }
}
