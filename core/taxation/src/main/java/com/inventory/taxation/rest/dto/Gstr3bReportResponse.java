package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.gstr3b.Gstr3bReportContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API response for GSTR-3B report.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gstr3bReportResponse {

  private String shopId;
  private String shopGstin;
  private String legalName;
  private String period;
  private int year;
  private int month;

  private Gstr3bSection31Dto section31;
  private List<Gstr3bInterStateSupplyDto> interStateSupplies;
  private Gstr3bSection4Dto section4;
  private Gstr3bSection5Dto section5;
  private Gstr3bSection61Dto section61;

  public static Gstr3bReportResponse fromContext(Gstr3bReportContext ctx) {
    Gstr3bReportResponse r = new Gstr3bReportResponse();
    r.setShopId(ctx.getShopId());
    r.setShopGstin(ctx.getShopGstin());
    r.setLegalName(ctx.getLegalName());
    r.setPeriod(ctx.getPeriod());
    r.setYear(ctx.getYear());
    r.setMonth(ctx.getMonth());
    r.setSection31(toDto(ctx.getSection31()));
    r.setInterStateSupplies(ctx.getInterStateSupplies() != null
        ? ctx.getInterStateSupplies().stream()
            .map(s -> new Gstr3bInterStateSupplyDto(s.getPlaceOfSupply(), toNum(s.getTaxableValue()), toNum(s.getIntegratedTax())))
            .collect(Collectors.toList())
        : List.of());
    r.setSection4(toDto(ctx.getSection4()));
    r.setSection5(toDto(ctx.getSection5()));
    r.setSection61(toDto(ctx.getSection61()));
    return r;
  }

  private static Gstr3bSection31Dto toDto(Gstr3bReportContext.Gstr3bSection31 s) {
    if (s == null) return null;
    return new Gstr3bSection31Dto(
        toNum(s.getOutwardTaxableValue()), toNum(s.getOutwardTaxableIgst()), toNum(s.getOutwardTaxableCgst()),
        toNum(s.getOutwardTaxableSgst()), toNum(s.getOutwardTaxableCess()),
        toNum(s.getZeroRatedValue()), toNum(s.getZeroRatedIgst()),
        toNum(s.getNilExemptValue()),
        toNum(s.getInwardRcmValue()), toNum(s.getInwardRcmIgst()), toNum(s.getInwardRcmCgst()),
        toNum(s.getInwardRcmSgst()), toNum(s.getInwardRcmCess()),
        toNum(s.getNonGstValue()));
  }

  private static Gstr3bSection4Dto toDto(Gstr3bReportContext.Gstr3bSection4 s) {
    if (s == null) return null;
    Gstr3bSection4Dto d = new Gstr3bSection4Dto();
    d.setItcOtherIgst(toNum(s.getItcOtherIgst()));
    d.setItcOtherCgst(toNum(s.getItcOtherCgst()));
    d.setItcOtherSgst(toNum(s.getItcOtherSgst()));
    d.setItcReversedOthersIgst(toNum(s.getItcReversedOthersIgst()));
    d.setItcReversedOthersCgst(toNum(s.getItcReversedOthersCgst()));
    d.setItcReversedOthersSgst(toNum(s.getItcReversedOthersSgst()));
    return d;
  }

  private static Gstr3bSection5Dto toDto(Gstr3bReportContext.Gstr3bSection5 s) {
    if (s == null) return null;
    return new Gstr3bSection5Dto(
        toNum(s.getCompExemptInterState()), toNum(s.getCompExemptIntraState()),
        toNum(s.getNonGstInterState()), toNum(s.getNonGstIntraState()));
  }

  private static Gstr3bSection61Dto toDto(Gstr3bReportContext.Gstr3bSection61 s) {
    if (s == null) return null;
    Gstr3bSection61Dto d = new Gstr3bSection61Dto();
    d.setIgstPayable(toNum(s.getIgstPayable()));
    d.setIgstPaidByItc(toNum(s.getIgstPaidByItc()));
    d.setIgstPaidByCash(toNum(s.getIgstPaidByCash()));
    d.setCgstPayable(toNum(s.getCgstPayable()));
    d.setCgstPaidByItcIgst(toNum(s.getCgstPaidByItcIgst()));
    d.setCgstPaidByItcCgst(toNum(s.getCgstPaidByItcCgst()));
    d.setCgstPaidByItcSgst(toNum(s.getCgstPaidByItcSgst()));
    d.setCgstPaidByCash(toNum(s.getCgstPaidByCash()));
    d.setSgstPayable(toNum(s.getSgstPayable()));
    d.setSgstPaidByItcIgst(toNum(s.getSgstPaidByItcIgst()));
    d.setSgstPaidByItcCgst(toNum(s.getSgstPaidByItcCgst()));
    d.setSgstPaidByItcSgst(toNum(s.getSgstPaidByItcSgst()));
    d.setSgstPaidByCash(toNum(s.getSgstPaidByCash()));
    d.setCessPayable(toNum(s.getCessPayable()));
    return d;
  }

  private static Double toNum(BigDecimal b) {
    return b != null ? b.doubleValue() : null;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection31Dto {
    private Double outwardTaxableValue;
    private Double outwardTaxableIgst;
    private Double outwardTaxableCgst;
    private Double outwardTaxableSgst;
    private Double outwardTaxableCess;
    private Double zeroRatedValue;
    private Double zeroRatedIgst;
    private Double nilExemptValue;
    private Double inwardRcmValue;
    private Double inwardRcmIgst;
    private Double inwardRcmCgst;
    private Double inwardRcmSgst;
    private Double inwardRcmCess;
    private Double nonGstValue;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bInterStateSupplyDto {
    private String placeOfSupply;
    private Double taxableValue;
    private Double integratedTax;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection4Dto {
    private Double itcOtherIgst;
    private Double itcOtherCgst;
    private Double itcOtherSgst;
    private Double itcReversedOthersIgst;
    private Double itcReversedOthersCgst;
    private Double itcReversedOthersSgst;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection5Dto {
    private Double compExemptInterState;
    private Double compExemptIntraState;
    private Double nonGstInterState;
    private Double nonGstIntraState;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr3bSection61Dto {
    private Double igstPayable;
    private Double igstPaidByItc;
    private Double igstPaidByCash;
    private Double cgstPayable;
    private Double cgstPaidByItcIgst;
    private Double cgstPaidByItcCgst;
    private Double cgstPaidByItcSgst;
    private Double cgstPaidByCash;
    private Double sgstPayable;
    private Double sgstPaidByItcIgst;
    private Double sgstPaidByItcCgst;
    private Double sgstPaidByItcSgst;
    private Double sgstPaidByCash;
    private Double cessPayable;
  }
}
