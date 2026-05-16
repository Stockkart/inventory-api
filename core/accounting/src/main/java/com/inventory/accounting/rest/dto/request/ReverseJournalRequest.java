package com.inventory.accounting.rest.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReverseJournalRequest {
  @Size(max = 280)
  private String reason;
}
