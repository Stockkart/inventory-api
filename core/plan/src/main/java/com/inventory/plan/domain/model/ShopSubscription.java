package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A shop's current subscription. One document per shop, keyed by shopId, updated in place on
 * renewal and upgrade; per-term history lives on plan transactions.
 *
 * <p>Derived from {@code Shop.planId} / {@code Shop.planExpiryDate} until read paths move onto it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_subscriptions")
public class ShopSubscription {

  /** Same value as shopId, so a shop can never have two current subscriptions. */
  @Id
  private String id;
  private String shopId;
  /** Null while on trial. */
  private String planId;
  private SubscriptionStatus status;
  private Instant expiresAt;
  /** Payment order that last activated or extended this subscription. */
  private String sourceOrderId;
  private Instant createdAt;
  private Instant updatedAt;
}
