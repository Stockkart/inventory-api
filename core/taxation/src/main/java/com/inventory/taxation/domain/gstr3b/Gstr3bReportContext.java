package com.inventory.taxation.domain.gstr3b;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated data for GSTR-3B monthly summary return.
 * Computed from GSTR-1 (outward) and GSTR-2 (inward/ITC).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr3bReportContext {

  private String shopId;
  private String shopGstin;
  private String legalName;
  private String period;
  private int year;
  private int month;

  /** Section 3.1: Outward supplies summary */
  @Builder.Default
  private Gstr3bSection31 section31 = new Gstr3bSection31();

  /** Section 3.2: Inter-state supplies (place of supply breakdown) */
  @Builder.Default
  private List<Gstr3bInterStateSupply> interStateSupplies = new ArrayList<>();

  /** Section 4: Eligible ITC */
  @Builder.Default
  private Gstr3bSection4 section4 = new Gstr3bSection4();

  /** Section 5: Exempt/nil/non-GST inward */
  @Builder.Default
  private Gstr3bSection5 section5 = new Gstr3bSection5();

  /** Section 6.1: Payment of tax */
  @Builder.Default
  private Gstr3bSection61 section61 = new Gstr3bSection61();

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection31 {
    private BigDecimal outwardTaxableValue;
    private BigDecimal outwardTaxableIgst;
    private BigDecimal outwardTaxableCgst;
    private BigDecimal outwardTaxableSgst;
    private BigDecimal outwardTaxableCess;
    private BigDecimal zeroRatedValue;
    private BigDecimal zeroRatedIgst;
    private BigDecimal nilExemptValue;
    private BigDecimal inwardRcmValue;
    private BigDecimal inwardRcmIgst;
    private BigDecimal inwardRcmCgst;
    private BigDecimal inwardRcmSgst;
    private BigDecimal inwardRcmCess;
    private BigDecimal nonGstValue;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bInterStateSupply {
    private String placeOfSupply;
    private BigDecimal taxableValue;
    private BigDecimal integratedTax;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Gstr3bSection4 {
    private BigDecimal itcImportGoodsIgst;
    private BigDecimal itcImportGoodsCgst;
    private BigDecimal itcImportGoodsSgst;
    private BigDecimal itcImportGoodsCess;
    private BigDecimal itcImportServicesIgst;
    private BigDecimal itcImportServicesCgst;
    private BigDecimal itcImportServicesSgst;
    private BigDecimal itcImportServicesCess;
    private BigDecimal itcRcmIgst;
    private BigDecimal itcRcmCgst;
    private BigDecimal itcRcmSgst;
    private BigDecimal itcRcmCess;
    private BigDecimal itcIsdIgst;
    private BigDecimal itcIsdCgst;
    private BigDecimal itcIsdSgst;
    private BigDecimal itcIsdCess;
    private BigDecimal itcOtherIgst;
    private BigDecimal itcOtherCgst;
    private BigDecimal itcOtherSgst;
    private BigDecimal itcOtherCess;
    private BigDecimal itcReversedRulesIgst;
    private BigDecimal itcReversedRulesCgst;
    private BigDecimal itcReversedRulesSgst;
    private BigDecimal itcReversedRulesCess;
    private BigDecimal itcReversedOthersIgst;
    private BigDecimal itcReversedOthersCgst;
    private BigDecimal itcReversedOthersSgst;
    private BigDecimal itcReversedOthersCess;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection5 {
    private BigDecimal compExemptInterState;
    private BigDecimal compExemptIntraState;
    private BigDecimal nonGstInterState;
    private BigDecimal nonGstIntraState;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Gstr3bSection61 {
    private BigDecimal igstPayable;
    private BigDecimal igstPaidByItc;
    private BigDecimal igstPaidByCash;
    private BigDecimal cgstPayable;
    private BigDecimal cgstPaidByItcIgst;
    private BigDecimal cgstPaidByItcCgst;
    private BigDecimal cgstPaidByItcSgst;
    private BigDecimal cgstPaidByCash;
    private BigDecimal sgstPayable;
    private BigDecimal sgstPaidByItcIgst;
    private BigDecimal sgstPaidByItcCgst;
    private BigDecimal sgstPaidByItcSgst;
    private BigDecimal sgstPaidByCash;
    private BigDecimal cessPayable;
    private BigDecimal cessPaidByItc;
    private BigDecimal cessPaidByCash;
  }
}
