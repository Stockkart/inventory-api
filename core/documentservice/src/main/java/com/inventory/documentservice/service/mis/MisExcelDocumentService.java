package com.inventory.documentservice.service.mis;

import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/** Builds MIS report workbooks from a neutral tabular request. */
@Service
@Slf4j
public class MisExcelDocumentService {

  public byte[] generateExcel(MisTabularDocumentRequest request) {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writeSummarySheet(workbook, request);
      writeDetailSheet(workbook, "Detail", request.getColumns(), request.getRows());
      if (StringUtils.hasText(request.getSecondarySheetTitle())
          && !CollectionUtils.isEmpty(request.getSecondaryColumns())) {
        writeDetailSheet(
            workbook,
            request.getSecondarySheetTitle(),
            request.getSecondaryColumns(),
            request.getSecondaryRows());
      }
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      log.error("Failed to build MIS Excel: {}", e.getMessage(), e);
      throw new IllegalStateException("Failed to generate MIS Excel", e);
    }
  }

  private void writeSummarySheet(Workbook workbook, MisTabularDocumentRequest request) {
    Sheet sheet = workbook.createSheet("Summary");
    CellStyle bold = boldStyle(workbook);
    int r = 0;
    r = writeLabelValue(sheet, r, "Report", nullToEmpty(request.getTitle()), bold);
    r = writeLabelValue(sheet, r, "Shop", nullToEmpty(request.getShopName()), bold);
    r = writeLabelValue(sheet, r, "Period", nullToEmpty(request.getPeriodLabel()), bold);
    r = writeLabelValue(sheet, r, "Generated", nullToEmpty(request.getGeneratedAtLabel()), bold);
    r++;
    Row header = sheet.createRow(r++);
    cell(header, 0, "KPI", bold);
    cell(header, 1, "Value", bold);
    List<MisDocumentKpi> kpis = request.getKpis() != null ? request.getKpis() : List.of();
    for (MisDocumentKpi kpi : kpis) {
      Row row = sheet.createRow(r++);
      cell(row, 0, nullToEmpty(kpi.getLabel()), null);
      cell(row, 1, nullToEmpty(kpi.getValue()), null);
    }
    sheet.autoSizeColumn(0);
    sheet.autoSizeColumn(1);
  }

  private void writeDetailSheet(
      Workbook workbook, String sheetName, List<String> columns, List<List<String>> rows) {
    Sheet sheet = workbook.createSheet(sanitizeSheetName(sheetName));
    CellStyle bold = boldStyle(workbook);
    List<String> cols = columns != null ? columns : List.of();
    Row header = sheet.createRow(0);
    for (int c = 0; c < cols.size(); c++) {
      cell(header, c, cols.get(c), bold);
    }
    List<List<String>> data = rows != null ? rows : List.of();
    int r = 1;
    for (List<String> line : data) {
      Row row = sheet.createRow(r++);
      for (int c = 0; c < cols.size(); c++) {
        String value = line != null && c < line.size() ? nullToEmpty(line.get(c)) : "";
        cell(row, c, value, null);
      }
    }
    for (int c = 0; c < cols.size(); c++) {
      sheet.autoSizeColumn(c);
    }
  }

  private static int writeLabelValue(
      Sheet sheet, int rowIdx, String label, String value, CellStyle bold) {
    Row row = sheet.createRow(rowIdx);
    cell(row, 0, label, bold);
    cell(row, 1, value, null);
    return rowIdx + 1;
  }

  private static void cell(Row row, int col, String value, CellStyle style) {
    Cell cell = row.createCell(col);
    cell.setCellValue(value != null ? value : "");
    if (style != null) {
      cell.setCellStyle(style);
    }
  }

  private static CellStyle boldStyle(Workbook workbook) {
    Font font = workbook.createFont();
    font.setBold(true);
    CellStyle style = workbook.createCellStyle();
    style.setFont(font);
    return style;
  }

  private static String sanitizeSheetName(String name) {
    String n = name != null ? name : "Sheet";
    n = n.replaceAll("[\\\\/*?\\[\\]:]", " ");
    return n.length() > 31 ? n.substring(0, 31) : n;
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }
}
