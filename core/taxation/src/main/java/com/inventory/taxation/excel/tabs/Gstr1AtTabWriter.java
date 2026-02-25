package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstAdvanceLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1AtTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "at";
  private static final List<String> HEADERS = Arrays.asList(
      "Place of Supply", "Applicable %Tax", "Rate", "Gross Advance Received", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstAdvanceLine> lines = context.getAtLines();
    java.math.BigDecimal totalAdvance = lines.stream().map(GstAdvanceLine::getGrossAdvanceReceivedOrAdjusted).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCess = lines.stream().map(GstAdvanceLine::getCessAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For Advance Received");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("Total Advance Received");
    sh.createCell(2).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), totalAdvance);
    PoiHelper.setCellValue(sd.createCell(2), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstAdvanceLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(1), line.getApplicableTaxPct());
      PoiHelper.setCellValue(row.createCell(2), line.getRate());
      PoiHelper.setCellValue(row.createCell(3), line.getGrossAdvanceReceivedOrAdjusted());
      PoiHelper.setCellValue(row.createCell(4), line.getCessAmount());
    }
  }
}
