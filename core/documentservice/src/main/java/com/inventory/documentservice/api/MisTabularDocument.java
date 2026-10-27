package com.inventory.documentservice.api;

import java.util.List;
import lombok.Builder;

/**
 * A MIS report as plain text cells, rendered by {@link MisDocuments} as an Excel workbook or a
 * PDF. Holds no domain types, so any module can build one.
 *
 * <p>A list left out (null) renders exactly like an empty list.
 *
 * @param title report title, printed in the header
 * @param shopName shop name, printed in the header
 * @param periodLabel the period covered, e.g. {@code 01 Apr 2025 - 30 Apr 2025}
 * @param generatedAtLabel when the report was made, as printed
 * @param kpis label/value pairs printed above the table
 * @param columns column headers of the detail table
 * @param rows detail rows, each aligned with {@code columns}
 * @param detailSheetTitle detail sheet name; empty means {@code Detail}
 * @param secondarySheetTitle optional second sheet (e.g. by party); empty means none
 * @param secondaryColumns column headers of the second sheet
 * @param secondaryRows rows of the second sheet
 * @param extraSheets more sheets after the second one (e.g. daily sales)
 */
@Builder
public record MisTabularDocument(
    String title,
    String shopName,
    String periodLabel,
    String generatedAtLabel,
    List<MisKpi> kpis,
    List<String> columns,
    List<List<String>> rows,
    String detailSheetTitle,
    String secondarySheetTitle,
    List<String> secondaryColumns,
    List<List<String>> secondaryRows,
    List<MisSheet> extraSheets) {}
