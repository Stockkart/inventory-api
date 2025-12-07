package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_shops")
@CompoundIndexes({
  @CompoundIndex(name = "userId_shopId_idx", def = "{'userId': 1, 'shopId': 1}", unique = true)
})
public class UserShop {

  @Id
  private String userShopId;
  private String userId;
  private String shopId;
  private String role; // ADMIN, MANAGER, CASHIER
  private String relationship; // OWNER, INVITED
  private boolean active;
  private boolean primary; // Primary shop for the user (used for default context)
  private Instant joinedAt;
  private Instant updatedAt;
}

