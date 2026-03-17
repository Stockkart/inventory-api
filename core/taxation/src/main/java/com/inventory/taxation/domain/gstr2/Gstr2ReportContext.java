package com.inventory.taxation.domain.gstr2;

import com.inventory.taxation.domain.model.GstHsnLine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated data for a GSTR-2 return for a given period and shop.
 * GSTR-2 covers inward supplies (purchases from vendors).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gstr2ReportContext {

  private String shopId;
  private String shopGstin;
  private String period;
  private int year;
  private int month;

  @Builder.Default
  private List<Gstr2B2bLine> b2bLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2B2burLine> b2burLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2ImpsLine> impsLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2ImpgLine> impgLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2CdnrLine> cdnrLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2CdnurLine> cdnurLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2AtLine> atLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2AtadjLine> atadjLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2ExempLine> exempLines = new ArrayList<>();
  @Builder.Default
  private List<Gstr2ItcrLine> itcrLines = new ArrayList<>();
  @Builder.Default
  private List<GstHsnLine> hsnLines = new ArrayList<>();
}
