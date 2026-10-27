package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One active login of a shop, as other modules (HR login link) need it. No email or phone. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopMemberSummaryDto {
  private String userId;
  private String name;
  /** UserRole name in this shop. */
  private String role;
  /** OWNER or INVITED. */
  private String relationship;
}
