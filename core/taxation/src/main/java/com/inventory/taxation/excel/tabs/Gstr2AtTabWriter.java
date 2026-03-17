package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2AtLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2AtTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "at";
  private static final List<String> HEADERS = Arrays.asList(
      "Place Of Supply", "Rate", "Gross Advance Paid", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2AtLine> lines = context.getAtLines();

    BigDecimal totalAdvance = lines.stream().map(Gstr2AtLine::getGrossAdvancePaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2AtLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For  Tax Liability on Advan");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("Total Advance Paid");
    sh.createCell(2).setCellValue("Total Cess Amount");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), totalAdvance);
    PoiHelper.setCellValue(sd.createCell(2), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2AtLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getGrossAdvancePaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
    }
  }
}
