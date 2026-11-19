package com.inventory.user.rest.dto.request;

import com.inventory.common.gst.PostalAddress;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import java.time.LocalDate;
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
  /** Last day the D.L. is valid (yyyy-MM-dd). Null leaves it unchanged; cleared with the D.L. No. */
  private LocalDate dlExpiry;
  private String pan;
  private CustomerPartyType partyType;
}
