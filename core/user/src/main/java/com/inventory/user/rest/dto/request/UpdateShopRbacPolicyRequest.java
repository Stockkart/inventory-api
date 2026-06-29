package com.inventory.user.rest.dto.request;

import com.inventory.user.domain.model.ProductSearchEditMode;
import lombok.Data;

@Data
public class UpdateShopRbacPolicyRequest {
  private ProductSearchEditMode productSearchEditMode;
}
