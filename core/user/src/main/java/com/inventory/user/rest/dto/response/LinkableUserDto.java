package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal user info returned when searching for users to link to vendor/customer.
 * Used by shop owners to confirm identity before linking.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkableUserDto {

  private String userId;
  private String email;
  private String name;
}
