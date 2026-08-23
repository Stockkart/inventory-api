package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import com.inventory.taxation.utils.helper.GstTotals;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2HsnsumTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "hsnsum";
  private static final List<String> HEADERS = Arrays.asList(
      "HSN", "Description", "UQC", "Total Quantity", "Total Value", "Taxable Value",
      "Integrated Tax Amount", "Central Tax Amount", "State/UT Tax", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<GstHsnLine> lines = context.getHsnLines();

    int noOfHsn = GstTotals.countDistinct(lines, GstHsnLine::getHsn);
    BigDecimal totalValue = GstTotals.sum(lines, GstHsnLine::getTotalValue);
    BigDecimal totalTaxable = GstTotals.sum(lines, GstHsnLine::getTaxableValue);
    BigDecimal totalIgst = GstTotals.sum(lines, GstHsnLine::getIntegratedTaxAmount);
    BigDecimal totalCgst = GstTotals.sum(lines, GstHsnLine::getCentralTaxAmount);
    BigDecimal totalSgst = GstTotals.sum(lines, GstHsnLine::getStateUtTaxAmount);
    BigDecimal totalCess = GstTotals.sum(lines, GstHsnLine::getCessAmount);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For HSN(13)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of HSN");
    sh.createCell(4).setCellValue("Total Value");
    sh.createCell(5).setCellValue("Total Taxable Value");
    sh.createCell(6).setCellValue("Total Integrated Tax");
    sh.createCell(7).setCellValue("Total Central Tax");
    sh.createCell(8).setCellValue("Total State/UT Tax");
    sh.createCell(9).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfHsn);
    PoiHelper.setCellValue(sd.createCell(4), totalValue);
    PoiHelper.setCellValue(sd.createCell(5), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(6), totalIgst);
    PoiHelper.setCellValue(sd.createCell(7), totalCgst);
    PoiHelper.setCellValue(sd.createCell(8), totalSgst);
    PoiHelper.setCellValue(sd.createCell(9), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstHsnLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getHsn());
      PoiHelper.setCellValue(row.createCell(c++), line.getDescription());
      PoiHelper.setCellValue(row.createCell(c++), line.getUqc());
      PoiHelper.setCellValue(row.createCell(c++), line.getTotalQuantity());
      PoiHelper.setCellValue(row.createCell(c++), line.getTotalValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getCentralTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getStateUtTaxAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
    }
  }
}
