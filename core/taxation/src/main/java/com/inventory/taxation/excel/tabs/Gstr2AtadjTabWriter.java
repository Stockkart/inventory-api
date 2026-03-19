package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2AtadjLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2AtadjTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "atadj";
  private static final List<String> HEADERS = Arrays.asList(
      "Place of Supply", "Rate", "Gross Advance Paid to be Adjusted", "Cess Adjusted");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2AtadjLine> lines = context.getAtadjLines();

    BigDecimal totalAdjusted = lines.stream().map(Gstr2AtadjLine::getGrossAdvanceToBeAdjusted)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2AtadjLine::getCessAdjusted)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For Adjustment of advance t");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("Total Advance Adjusted");
    sh.createCell(2).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), totalAdjusted);
    PoiHelper.setCellValue(sd.createCell(2), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2AtadjLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getGrossAdvanceToBeAdjusted());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessAdjusted());
    }
  }
}
