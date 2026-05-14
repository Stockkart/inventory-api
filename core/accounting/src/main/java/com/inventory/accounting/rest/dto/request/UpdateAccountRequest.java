package com.inventory.accounting.rest.dto.request;

import lombok.Data;

@Data
public class UpdateAccountRequest {
  private String name;
  private Boolean active;
}
