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
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle);
    int rowNum = 1;
    for (GstAdvanceLine line : context.getAtLines()) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(1), line.getApplicableTaxPct());
      PoiHelper.setCellValue(row.createCell(2), line.getRate());
      PoiHelper.setCellValue(row.createCell(3), line.getGrossAdvanceReceivedOrAdjusted());
      PoiHelper.setCellValue(row.createCell(4), line.getCessAmount());
    }
  }
}
