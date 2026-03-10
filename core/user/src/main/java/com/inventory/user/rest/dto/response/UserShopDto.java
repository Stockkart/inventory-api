package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserShopDto {
  private String shopId;
  private String shopName;
  private String role;
  private String relationship; // OWNER or INVITED
  private Instant joinedAt;
}
