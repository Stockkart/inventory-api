package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Many-to-many relationship between users and shops.
 * Replaces the single shopId on UserAccount for access control while keeping
 * UserAccount.shopId as the "active" shop for backward compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_shop_memberships")
@CompoundIndex(name = "userId_shopId_idx", def = "{'userId': 1, 'shopId': 1}", unique = true)
public class UserShopMembership {

  @Id
  private String membershipId;
  private String userId;
  private String shopId;
  private UserRole role;
  private String relationship; // OWNER or INVITED
  private boolean active;
  private Instant joinedAt;
}
