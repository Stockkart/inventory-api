package com.inventory.plan.rest.dto.request;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Cart to price. Same shape checkout will take, so a quote and its checkout hash identically. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRequest {

  private String planCode;
  /** Defaults to 12; plans are sold yearly. */
  private Integer durationMonths;
  private List<AddOnLine> addOns;
  private List<String> voucherCodes;
  private Boolean applyWalletCredit;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AddOnLine {
    private String code;
    private Integer quantity;
  }
}
