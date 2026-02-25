package com.inventory.taxation.rest.dto;

import com.inventory.taxation.domain.model.GstDocumentSummaryLine;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** DOCS tab: Summary of documents (13) - Total Number, Cancelled */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GstDocsTabDto {
  private GstDocsSummaryDto summary;
  private List<GstDocumentSummaryLine> lines;

  public static GstDocsTabDto fromLines(List<GstDocumentSummaryLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return new GstDocsTabDto(new GstDocsSummaryDto(0, 0), List.of());
    }
    int totalNumber = lines.stream().mapToInt(l -> l.getTotalNumber() != null ? l.getTotalNumber() : 0).sum();
    int cancelled = lines.stream().mapToInt(l -> l.getCancelled() != null ? l.getCancelled() : 0).sum();
    return new GstDocsTabDto(new GstDocsSummaryDto(totalNumber, cancelled), lines);
  }
}
