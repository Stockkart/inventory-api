package com.inventory.plan.domain.model;

import com.inventory.common.entitlement.PlanFeature;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An add-on a shop holds. The grant is copied from the catalogue when issued, so later catalogue
 * edits do not change what was bought. Not monthly: OCR credits live here so a month rollover
 * cannot touch them (§23).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_add_ons")
public class ShopAddOn {

  @Id
  private String id;
  private String shopId;
  private String addOnCode;
  private String name;
  private AddOnGrantType grantType;
  private PlanFeature grantsFeature;
  /** Units bought. */
  private int quantity;
  /** quantity × grantsQuantity: seats, SMS or OCR credits granted. */
  private int grantedQuantity;
  /** OCR credits not yet used; null for other grant types. */
  private Integer remainingCredits;
  private Instant purchasedAt;
  /** Null for credits that never expire. */
  private Instant expiresAt;
  /** Order that paid for it; null for admin grants. Unique with addOnCode. */
  private String sourceOrderId;
  private ShopAddOnSource source;
  /** Admin who granted it, for ADMIN grants. */
  private String grantedByUserId;
  private String note;
  private Instant createdAt;
  /** Taken back because its order was refunded; expiresAt is set to the same instant. */
  private Instant revokedAt;

  public boolean isLive(Instant now) {
    return expiresAt == null || expiresAt.isAfter(now);
  }
}
