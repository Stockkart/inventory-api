package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.pluginengine.profile.FieldDefinition;
import com.inventory.product.rest.dto.request.RegisterShopRequest;
import com.inventory.product.service.profile.ProfileResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ShopProfileValidator {

  @Autowired
  private ProfileResolver profileResolver;

  public void validateRegisterRequest(RegisterShopRequest request, String profileId) {
    BusinessProfile profile = profileResolver.requireEnabled(profileId);
    var shopEntity = profile.getEntities().get("shop");
    if (shopEntity == null) {
      return;
    }
    for (FieldDefinition field : shopEntity.getFields()) {
      boolean visible = field.getVisible() == null || Boolean.TRUE.equals(field.getVisible());
      if (!visible || !Boolean.TRUE.equals(field.getRequired())) {
        continue;
      }
      if (!StringUtils.hasText(getShopFieldValue(request, field.getKey()))) {
        String label = StringUtils.hasText(field.getLabel()) ? field.getLabel() : field.getKey();
        throw new ValidationException(label + " is required");
      }
    }
  }

  private String getShopFieldValue(RegisterShopRequest request, String key) {
    if (key == null) {
      return null;
    }
    return switch (key) {
      case "dlNo" -> request.getDlNo();
      case "gstinNo" -> request.getGstinNo();
      case "fssai" -> request.getFssai();
      case "panNo" -> request.getPanNo();
      case "sgst" -> request.getSgst();
      case "cgst" -> request.getCgst();
      default -> null;
    };
  }
}
