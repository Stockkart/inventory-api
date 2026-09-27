package com.inventory.plan.rest.dto.response;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.ShopAddOnSource;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAddOnResponse {

  private String id;
  private String shopId;
  private String addOnCode;
  private String name;
  private AddOnGrantType grantType;
  private PlanFeature grantsFeature;
  private int quantity;
  private int grantedQuantity;
  private Integer remainingCredits;
  private Instant purchasedAt;
  private Instant expiresAt;
  private ShopAddOnSource source;
  private String sourceOrderId;
  private boolean live;
}
