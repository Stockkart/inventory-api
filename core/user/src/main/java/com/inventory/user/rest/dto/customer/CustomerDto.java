package com.inventory.user.rest.dto.customer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {
  private String customerId;
  private String name;
  private String phone;
  private String address;
  private String email;
  private String gstin;
  private String dlNo;
  private String pan;
  /** Optional. Set when customer is linked to a registered user. */
  private String userId;
  private Instant createdAt;
  private Instant updatedAt;
}

