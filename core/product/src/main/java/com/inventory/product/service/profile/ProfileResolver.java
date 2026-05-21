package com.inventory.product.service.profile;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.BusinessProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@Slf4j
public class ProfileResolver {

  @Autowired
  private BusinessTypeRepository businessTypeRepository;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private BusinessProfileMapper businessProfileMapper;

  public BusinessProfile requireEnabled(String profileId) {
    return findEnabled(profileId)
        .orElseThrow(() -> new ValidationException("Unknown or disabled business profile: " + profileId));
  }

  public Optional<BusinessProfile> findEnabled(String profileId) {
    if (!StringUtils.hasText(profileId)) {
      return Optional.empty();
    }
    return businessTypeRepository.findById(profileId.trim())
        .filter(BusinessType::isEnabled)
        .map(businessProfileMapper::toProfile);
  }

  public BusinessProfile resolveForShop(String shopId) {
    Shop shop = shopRepository.findById(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    String profileId = StringUtils.hasText(shop.getBusinessProfileId())
        ? shop.getBusinessProfileId()
        : BusinessProfile.DEFAULT_PROFILE_ID;
    return requireEnabled(profileId);
  }

  public String resolveProfileIdForShop(String shopId) {
    return shopRepository.findById(shopId)
        .map(Shop::getBusinessProfileId)
        .filter(StringUtils::hasText)
        .orElse(BusinessProfile.DEFAULT_PROFILE_ID);
  }

  public Optional<String> getProfileDisplayName(String profileId) {
    return businessTypeRepository.findById(profileId).map(BusinessType::getName);
  }
}
