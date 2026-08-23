package com.inventory.user.rest.dto.response;

import com.inventory.user.domain.model.enums.CustomerPartyType;
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
  /** PAN derived from GSTIN: 10 chars from 3rd character (1-based). */
  private String panNo;
  /** Optional. Set when customer is linked to a registered user. */
  private String userId;
  private CustomerPartyType partyType;
  /** True for the shop walk-in / name-address-only placeholder. */
  private Boolean isGeneral;
  private Instant createdAt;
  private Instant updatedAt;
}
