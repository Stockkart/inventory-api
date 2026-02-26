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

  /** B2B / SEZ / Deemed export – sheet name: "b2b,sez,de" (summary + lines) */
  @JsonProperty("b2b,sez,de")
  private GstB2bSezDeTabDto b2bSezDe;

  @JsonProperty("b2cl")
  private GstB2clTabDto b2cl;

  @JsonProperty("b2cs")
  private GstB2csTabDto b2cs;

  @JsonProperty("cdnr")
  private GstCdnrTabDto cdnr;

  @JsonProperty("cdnur")
  private GstCdnurTabDto cdnur;

  @JsonProperty("exp")
  private GstExpTabDto exp;

  @JsonProperty("at")
  private GstAtTabDto at;

  @JsonProperty("atadj")
  private GstAtadjTabDto atadj;

  @JsonProperty("exemp")
  private GstExempTabDto exemp;

  @JsonProperty("hsn(b2b)")
  private GstHsnTabDto hsnB2b;

  @JsonProperty("hsn(b2c)")
  private GstHsnTabDto hsnB2c;

  @JsonProperty("docs")
  private GstDocsTabDto docs;

  public static Gstr1ReportResponse fromContext(Gstr1ReportContext ctx) {
    Gstr1ReportResponse r = new Gstr1ReportResponse();
    r.setShopId(ctx.getShopId());
    r.setShopGstin(ctx.getShopGstin());
    r.setPeriod(ctx.getPeriod());
    r.setYear(ctx.getYear());
    r.setMonth(ctx.getMonth());
    r.setB2bSezDe(GstB2bSezDeTabDto.fromLines(ctx.getB2bLines()));
    r.setB2cl(GstB2clTabDto.fromLines(ctx.getB2clLines()));
    r.setB2cs(GstB2csTabDto.fromLines(ctx.getB2csLines()));
    r.setCdnr(GstCdnrTabDto.fromLines(ctx.getCdnrLines()));
    r.setCdnur(GstCdnurTabDto.fromLines(ctx.getCdnurLines()));
    r.setExp(GstExpTabDto.fromLines(ctx.getExpLines()));
    r.setAt(GstAtTabDto.fromLines(ctx.getAtLines()));
    r.setAtadj(GstAtadjTabDto.fromLines(ctx.getAtadjLines()));
    r.setExemp(GstExempTabDto.fromLines(ctx.getExempLines()));
    r.setHsnB2b(GstHsnTabDto.fromLines(ctx.getHsnB2bLines()));
    r.setHsnB2c(GstHsnTabDto.fromLines(ctx.getHsnB2cLines()));
    r.setDocs(GstDocsTabDto.fromLines(ctx.getDocLines()));
    return r;
  }
}
