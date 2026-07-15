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
public class PartyMoneyMisResponse {
  private String side;
  private LocalDate from;
  private LocalDate to;
  @Builder.Default private List<PartyMoneyMisRowDto> rows = new ArrayList<>();
  private PartyMoneyMisSummaryDto summary;
}
