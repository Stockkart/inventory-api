package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class UpdateCustomerRequest {
  private String name;
  private String phone;
  private String email;
  private String address;
  private String gstin;
  private String dlNo;
  private String pan;
}
