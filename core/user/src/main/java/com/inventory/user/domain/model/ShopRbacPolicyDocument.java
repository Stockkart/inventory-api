package com.inventory.user.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_rbac_policies")
public class ShopRbacPolicyDocument {

  @Id
  private String id;

  @Indexed(unique = true)
  private String shopId;

  private ProductSearchEditMode productSearchEditMode = ProductSearchEditMode.FULL_EDIT;
  private Instant updatedAt;
  private String updatedByUserId;
}
