package com.inventory.taxation.domain.gstr1;

import com.inventory.taxation.domain.model.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated data for a GSTR-1 return for a given period and shop.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr1ReportContext {

  private String shopId;
  private String shopGstin;
  private String period;           // e.g. "2025-12"
  private int year;
  private int month;

  @Builder.Default
  private List<GstInvoiceLine> b2bLines = new ArrayList<>();
  @Builder.Default
  private List<GstInvoiceLine> b2clLines = new ArrayList<>();
  @Builder.Default
  private List<GstInvoiceLine> b2csLines = new ArrayList<>();
  @Builder.Default
  private List<GstInvoiceLine> expLines = new ArrayList<>();

  @Builder.Default
  private List<GstRefundLine> cdnrLines = new ArrayList<>();
  @Builder.Default
  private List<GstRefundLine> cdnurLines = new ArrayList<>();

  @Builder.Default
  private List<GstAdvanceLine> atLines = new ArrayList<>();
  @Builder.Default
  private List<GstAdvanceLine> atadjLines = new ArrayList<>();

  @Builder.Default
  private List<GstExemptLine> exempLines = new ArrayList<>();

  @Builder.Default
  private List<GstHsnLine> hsnB2bLines = new ArrayList<>();
  @Builder.Default
  private List<GstHsnLine> hsnB2cLines = new ArrayList<>();

  @Builder.Default
  private List<GstDocumentSummaryLine> docLines = new ArrayList<>();
}
