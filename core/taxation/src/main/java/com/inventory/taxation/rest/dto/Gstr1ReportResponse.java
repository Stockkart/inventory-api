package com.inventory.taxation.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventory.taxation.domain.model.*;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * API response for GSTR-1 report (dashboard view).
 * Each tab is exposed under its exact Excel sheet name so the UI can map 1:1 to the download.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Gstr1ReportResponse {

  private String shopId;
  private String shopGstin;
  private String period;
  private int year;
  private int month;

  /** B2B / SEZ / Deemed export – sheet name: "b2b,sez,de" (slim DTO, no internal fields) */
  @JsonProperty("b2b,sez,de")
  private List<GstB2bSezDeLineDto> b2bSezDe;

  @JsonProperty("b2cl")
  private List<GstInvoiceLine> b2cl;

  /** B2C small – sheet name: "b2cs" (slim DTO) */
  @JsonProperty("b2cs")
  private List<GstB2csLineDto> b2cs;

  @JsonProperty("cdnr")
  private List<GstRefundLine> cdnr;

  @JsonProperty("cdnur")
  private List<GstRefundLine> cdnur;

  @JsonProperty("exp")
  private List<GstInvoiceLine> exp;

  @JsonProperty("at")
  private List<GstAdvanceLine> at;

  @JsonProperty("atadj")
  private List<GstAdvanceLine> atadj;

  @JsonProperty("exemp")
  private List<GstExemptLine> exemp;

  @JsonProperty("hsn(b2b)")
  private List<GstHsnLine> hsnB2b;

  @JsonProperty("hsn(b2c)")
  private List<GstHsnLine> hsnB2c;

  @JsonProperty("docs")
  private List<GstDocumentSummaryLine> docs;

  public static Gstr1ReportResponse fromContext(Gstr1ReportContext ctx) {
    Gstr1ReportResponse r = new Gstr1ReportResponse();
    r.setShopId(ctx.getShopId());
    r.setShopGstin(ctx.getShopGstin());
    r.setPeriod(ctx.getPeriod());
    r.setYear(ctx.getYear());
    r.setMonth(ctx.getMonth());
    r.setB2bSezDe(GstB2bSezDeLineDto.fromList(ctx.getB2bLines()));
    r.setB2cl(ctx.getB2clLines());
    r.setB2cs(GstB2csLineDto.fromList(ctx.getB2csLines()));
    r.setCdnr(ctx.getCdnrLines());
    r.setCdnur(ctx.getCdnurLines());
    r.setExp(ctx.getExpLines());
    r.setAt(ctx.getAtLines());
    r.setAtadj(ctx.getAtadjLines());
    r.setExemp(ctx.getExempLines());
    r.setHsnB2b(ctx.getHsnB2bLines());
    r.setHsnB2c(ctx.getHsnB2cLines());
    r.setDocs(ctx.getDocLines());
    return r;
  }
}
