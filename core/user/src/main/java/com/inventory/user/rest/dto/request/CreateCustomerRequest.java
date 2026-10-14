package com.inventory.user.rest.dto.request;

import com.inventory.common.gst.PostalAddress;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import lombok.Data;

@Data
public class CreateCustomerRequest {
  private String name;
  private String phone;
  private String email;
  private String address;
  /** Optional fielded address; only the state code matters for tax, and even that is optional. */
  private PostalAddress postalAddress;
  private String gstin;
  private String dlNo;
  private String pan;
  /** Defaults to CONSUMER when omitted. */
  private CustomerPartyType partyType;
}
