package com.inventory.documentservice.rest.dto.mis;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Neutral tabular payload for MIS Excel/PDF generation (no domain deps). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisTabularDocumentRequest {

  private String title;
  private String shopName;
  private String periodLabel;
  private String generatedAtLabel;

  @Builder.Default private List<MisDocumentKpi> kpis = new ArrayList<>();

  /** Column headers for the detail sheet / table. */
  @Builder.Default private List<String> columns = new ArrayList<>();

  /** Each row is aligned with {@link #columns}. */
  @Builder.Default private List<List<String>> rows = new ArrayList<>();

  /** Optional second sheet (e.g. by-party). Empty = omitted. */
  private String secondarySheetTitle;

  @Builder.Default private List<String> secondaryColumns = new ArrayList<>();

  @Builder.Default private List<List<String>> secondaryRows = new ArrayList<>();
}
