package com.inventory.taxation.service;

import com.inventory.taxation.rest.dto.HsnGstRatesResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The GST rates to offer for an HSN on the stock-in row, each already split into the CGST and SGST
 * the row stores. The operator picks one, or types the halves by hand where the HSN is not on file
 * or the goods are an exception the table does not name.
 */
@Service
@RequiredArgsConstructor
public class HsnGstRateService {

  private final HsnGstRateMaster hsnGstRateMaster;

  public HsnGstRatesResponse ratesFor(String hsn) {
    return hsnGstRateMaster.rateFor(hsn)
        .filter(HsnGstRateMaster.Entry::isAuthoritative)
        .map(entry -> new HsnGstRatesResponse(
            hsn, entry.code(), entry.rates().stream().map(HsnGstRateService::option).toList(),
            entry.ref()))
        .orElseGet(() -> new HsnGstRatesResponse(hsn, null, List.of(), null));
  }

  /** An intra-state rate is charged as two equal halves, so each half is the rate over two. */
  private static HsnGstRatesResponse.Option option(BigDecimal gstRate) {
    BigDecimal half = gstRate.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
        .stripTrailingZeros();
    return new HsnGstRatesResponse.Option(gstRate.stripTrailingZeros(), half, half);
  }
}
