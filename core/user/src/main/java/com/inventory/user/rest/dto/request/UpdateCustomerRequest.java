package com.inventory.user.rest.dto.request;

import com.inventory.common.gst.PostalAddress;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import lombok.Data;

@Data
public class UpdateCustomerRequest {
  private String name;
  private String phone;
  private String email;
  private String address;
  /** Replaces the stored fielded address when present. */
  private PostalAddress postalAddress;
  private String gstin;
  private String dlNo;
  private String pan;
  private CustomerPartyType partyType;
}
