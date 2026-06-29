package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.ProductSearchEditMode;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopRbacAdminResponse {
  private ProductSearchEditMode productSearchEditMode;
  private List<ShopMemberAccessDto> members;
}
