package com.inventory.taxation.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventory.taxation.domain.gstr2.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * API response for GSTR-2 report (dashboard view).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gstr2ReportResponse {

  private String shopId;
  private String shopGstin;
  private String period;
  private int year;
  private int month;

  @JsonProperty("b2b")
  private Gstr2TabDto<Gstr2B2bLineDto> b2b;

  @JsonProperty("b2bur")
  private Gstr2TabDto<Gstr2B2burLineDto> b2bur;

  @JsonProperty("imps")
  private Gstr2TabDto<Gstr2ImpsLineDto> imps;

  @JsonProperty("impg")
  private Gstr2TabDto<Gstr2ImpgLineDto> impg;

  @JsonProperty("cdnr")
  private Gstr2TabDto<Gstr2CdnrLineDto> cdnr;

  @JsonProperty("cdnur")
  private Gstr2TabDto<Gstr2CdnurLineDto> cdnur;

  @JsonProperty("at")
  private Gstr2TabDto<Gstr2AtLineDto> at;

  @JsonProperty("atadj")
  private Gstr2TabDto<Gstr2AtadjLineDto> atadj;

  @JsonProperty("exemp")
  private Gstr2TabDto<Gstr2ExempLineDto> exemp;

  @JsonProperty("itcr")
  private Gstr2TabDto<Gstr2ItcrLineDto> itcr;

  @JsonProperty("hsnsum")
  private Gstr2TabDto<Gstr2HsnLineDto> hsnsum;

  public static Gstr2ReportResponse fromContext(Gstr2ReportContext ctx) {
    Gstr2ReportResponse r = new Gstr2ReportResponse();
    r.setShopId(ctx.getShopId());
    r.setShopGstin(ctx.getShopGstin());
    r.setPeriod(ctx.getPeriod());
    r.setYear(ctx.getYear());
    r.setMonth(ctx.getMonth());
    r.setB2b(Gstr2TabDto.from(ctx.getB2bLines(), Gstr2B2bLineDto::from));
    r.setB2bur(Gstr2TabDto.from(ctx.getB2burLines(), Gstr2B2burLineDto::from));
    r.setImps(Gstr2TabDto.from(ctx.getImpsLines(), Gstr2ImpsLineDto::from));
    r.setImpg(Gstr2TabDto.from(ctx.getImpgLines(), Gstr2ImpgLineDto::from));
    r.setCdnr(Gstr2TabDto.from(ctx.getCdnrLines(), Gstr2CdnrLineDto::from));
    r.setCdnur(Gstr2TabDto.from(ctx.getCdnurLines(), Gstr2CdnurLineDto::from));
    r.setAt(Gstr2TabDto.from(ctx.getAtLines(), Gstr2AtLineDto::from));
    r.setAtadj(Gstr2TabDto.from(ctx.getAtadjLines(), Gstr2AtadjLineDto::from));
    r.setExemp(Gstr2TabDto.from(ctx.getExempLines(), Gstr2ExempLineDto::from));
    r.setItcr(Gstr2TabDto.from(ctx.getItcrLines(), Gstr2ItcrLineDto::from));
    r.setHsnsum(Gstr2TabDto.fromHsn(ctx.getHsnLines()));
    return r;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Gstr2TabDto<T> {
    private List<T> lines;

    public static <S, T> Gstr2TabDto<T> from(List<S> lines, java.util.function.Function<S, T> mapper) {
      if (lines == null) return new Gstr2TabDto<>(List.of());
      return new Gstr2TabDto<>(lines.stream().map(mapper).collect(Collectors.toList()));
    }

    public static Gstr2TabDto<Gstr2HsnLineDto> fromHsn(List<com.inventory.taxation.domain.model.GstHsnLine> lines) {
      if (lines == null) return new Gstr2TabDto<>(List.of());
      return new Gstr2TabDto<>(lines.stream().map(Gstr2HsnLineDto::from).collect(Collectors.toList()));
    }
  }
}
