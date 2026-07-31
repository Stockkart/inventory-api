package com.inventory.analytics.rest.dto.response;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorMoneyMisResponse {
  private LocalDate from;
  private LocalDate to;
  @Builder.Default private List<VendorMoneyMisRowDto> rows = new ArrayList<>();
  private VendorMoneyMisSummaryDto summary;
}
