package com.inventory.product.profile;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.domain.repository.ShopRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProfileResolver {

  private final ShopRepository shopRepository;
  private final BusinessTypeRepository businessTypeRepository;
  private final BusinessProfileMapper businessProfileMapper;

  public ProfileResolver(
      ShopRepository shopRepository,
      BusinessTypeRepository businessTypeRepository,
      BusinessProfileMapper businessProfileMapper) {
    this.shopRepository = shopRepository;
    this.businessTypeRepository = businessTypeRepository;
    this.businessProfileMapper = businessProfileMapper;
  }

  public BusinessProfile resolveForShop(String shopId) {
    Shop shop = shopRepository.findById(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    String profileId = StringUtils.hasText(shop.getBusinessProfileId())
        ? shop.getBusinessProfileId()
        : BusinessProfile.DEFAULT_PROFILE_ID;
    return resolveById(profileId);
  }

  public BusinessProfile resolveById(String profileId) {
    String id = StringUtils.hasText(profileId) ? profileId : BusinessProfile.DEFAULT_PROFILE_ID;
    BusinessType entity = businessTypeRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Business profile", "id", id));
    if (!entity.isEnabled()) {
      throw new ResourceNotFoundException("Business profile", "id", id);
    }
    return businessProfileMapper.toRuntimeProfile(entity);
  }
}
