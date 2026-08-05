package com.inventory.documentservice.rest.dto.mis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisDocumentKpi {
  private String label;
  private String value;
}
