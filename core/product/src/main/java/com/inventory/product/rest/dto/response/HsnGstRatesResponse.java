package com.inventory.product.rest.dto.response;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The GST rates the rate notifications allow for an HSN, offered as choices on the stock-in row.
 * Empty {@code rates} when the HSN is not in the table; the row's CGST and SGST stay typed by hand.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HsnGstRatesResponse {
  /** The HSN as asked. */
  private String hsn;
  /** The code in the table that answered (the HSN itself, or its six- or four-digit parent). */
  private String matchedHsn;
  private List<Option> rates;
  /** Which notification entries the rates come from. */
  private String ref;

  /** One rate, split into the CGST and SGST the row stores. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Option {
    private BigDecimal gstRate;
    private BigDecimal cgst;
    private BigDecimal sgst;
  }
}
