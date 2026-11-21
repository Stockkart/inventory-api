package com.inventory.documentservice.api;

import java.util.List;
import lombok.Builder;

/**
 * One extra workbook sheet on a MIS Excel export. A sheet with no title or no columns is skipped.
 *
 * @param title sheet name
 * @param columns column headers
 * @param rows rows, each aligned with {@code columns}
 */
@Builder
public record MisSheet(String title, List<String> columns, List<List<String>> rows) {}
