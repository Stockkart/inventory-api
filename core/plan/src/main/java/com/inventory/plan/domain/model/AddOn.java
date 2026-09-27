package com.inventory.plan.domain.model;

import com.inventory.common.entitlement.PlanFeature;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Catalogue entry for something sold alongside a plan. Never deleted; hidden with {@code active}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "add_ons")
public class AddOn {

  @Id
  private String id;
  /** Stable key, e.g. MARKETING_MODULE. Fixed at create. */
  private String code;
  private String name;
  private String description;
  /** Price per unit, tax inclusive. */
  private BigDecimal price;
  private AddOnBillingType billingType;
  private AddOnGrantType grantType;
  /** Set when {@code grantType} is FEATURE. */
  private PlanFeature grantsFeature;
  /** Seats, SMS or OCR credits per unit; 1 for features. */
  private Integer grantsQuantity;
  /** Whether more than one unit may be bought in an order. */
  private boolean stackable;
  /** Upper bound per order when stackable; null means no bound. */
  private Integer maxQuantity;
  private Boolean active;
  private Integer displayOrder;
  private Instant createdAt;
  private Instant updatedAt;

  public boolean isActive() {
    return !Boolean.FALSE.equals(active);
  }
}
