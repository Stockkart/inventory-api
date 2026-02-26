package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstDocumentSummaryLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1DocsTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "docs";
  private static final List<String> HEADERS = Arrays.asList(
      "Nature of Document", "Sr.No. From", "Sr.No. To", "Total Number", "Cancelled");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstDocumentSummaryLine> lines = context.getDocLines();
    int totalNumber = lines.stream().mapToInt(l -> l.getTotalNumber() != null ? l.getTotalNumber() : 0).sum();
    int cancelled = lines.stream().mapToInt(l -> l.getCancelled() != null ? l.getCancelled() : 0).sum();
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary of documents issued during the tax period (13)");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), totalNumber);
    PoiHelper.setCellValue(sd.createCell(1), cancelled);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstDocumentSummaryLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getNatureOfDocument());
      PoiHelper.setCellValue(row.createCell(1), line.getSrNoFrom());
      PoiHelper.setCellValue(row.createCell(2), line.getSrNoTo());
      PoiHelper.setCellValue(row.createCell(3), line.getTotalNumber());
      PoiHelper.setCellValue(row.createCell(4), line.getCancelled() != null ? line.getCancelled() : 0);
    }
  }
}
