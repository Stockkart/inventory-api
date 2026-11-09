package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2ItcrLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import com.inventory.taxation.utils.helper.GstTotals;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2ItcrTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "itcr";
  private static final List<String> HEADERS = Arrays.asList(
      "Description for reversal of ITC", "To be added or reduced from output liability",
      "ITC Integrated Tax Amount", "ITC Central Tax Amount", "ITC State/UT Tax Amount", "ITC Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2ItcrLine> lines = context.getItcrLines();

    BigDecimal totalIgst = GstTotals.sum(lines, Gstr2ItcrLine::getItcIntegratedTaxAmount);
    BigDecimal totalCgst = GstTotals.sum(lines, Gstr2ItcrLine::getItcCentralTaxAmount);
    BigDecimal totalSgst = GstTotals.sum(lines, Gstr2ItcrLine::getItcStateUtTaxAmount);
    BigDecimal totalCess = GstTotals.sum(lines, Gstr2ItcrLine::getItcCessAmount);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary Input Tax credit Reversal/R");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(2).setCellValue("Total ITC Integrated Tax Amount");
    sh.createCell(3).setCellValue("Total ITC Central Tax Amount");
    sh.createCell(4).setCellValue("Total ITC State/UT Tax Amount");
    sh.createCell(5).setCellValue("Total ITC Cess Amount");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(2), totalIgst);
    PoiHelper.setCellValue(sd.createCell(3), totalCgst);
    PoiHelper.setCellValue(sd.createCell(4), totalSgst);
    PoiHelper.setCellValue(sd.createCell(5), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2ItcrLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getDescription());
      PoiHelper.setCellValue(row.createCell(c++), line.getToBeAddedOrReduced());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcIntegratedTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcCentralTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcStateUtTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcCessAmount());
    }
  }
}
