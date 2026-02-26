package com.inventory.taxation.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Summary of documents issued during the tax period (13): Total Number, Cancelled */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstDocsSummaryDto {
  private int totalNumber;
  private int cancelled;
}
