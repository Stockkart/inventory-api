package com.inventory.documentservice.rest.dto.mis;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One extra workbook sheet on a MIS Excel export. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisDocumentSheet {
  private String title;

  @Builder.Default private List<String> columns = new ArrayList<>();

  @Builder.Default private List<List<String>> rows = new ArrayList<>();
}
