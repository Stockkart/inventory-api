package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import com.inventory.taxation.utils.helper.GstTotals;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1HsnB2cTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "hsn(b2c)";
  private static final List<String> HEADERS = Arrays.asList(
      "HSN", "Description", "UQC", "Total Quantity", "Total Value", "Rate", "Taxable Value",
      "Integrated Tax Amount", "Central Tax Amount", "State/UT Tax Amount", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstHsnLine> lines = context.getHsnB2cLines();
    int noOfHsn = GstTotals.countDistinct(lines, GstHsnLine::getHsn);
    java.math.BigDecimal totalValue = GstTotals.sum(lines, GstHsnLine::getTotalValue);
    java.math.BigDecimal totalTaxableValue = GstTotals.sum(lines, GstHsnLine::getTaxableValue);
    java.math.BigDecimal totalIntegratedTax = GstTotals.sum(lines, GstHsnLine::getIntegratedTaxAmount);
    java.math.BigDecimal totalCentralTax = GstTotals.sum(lines, GstHsnLine::getCentralTaxAmount);
    java.math.BigDecimal totalStateUtTax = GstTotals.sum(lines, GstHsnLine::getStateUtTaxAmount);
    java.math.BigDecimal totalCess = GstTotals.sum(lines, GstHsnLine::getCessAmount);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For HSN(12)");
    Row sh1 = sheet.createRow(rowNum++);
    sh1.createCell(0).setCellValue("No. of HSN");
    PoiHelper.setCellValue(sh1.createCell(1), noOfHsn);
    Row sh2 = sheet.createRow(rowNum++);
    sh2.createCell(0).setCellValue("Total Value");
    sh2.createCell(2).setCellValue("Total Taxable Value");
    sh2.createCell(4).setCellValue("Total Integrated Tax");
    sh2.createCell(6).setCellValue("Total Central Tax");
    sh2.createCell(8).setCellValue("Total State/UT Tax");
    sh2.createCell(10).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), totalValue);
    PoiHelper.setCellValue(sd.createCell(2), totalTaxableValue);
    PoiHelper.setCellValue(sd.createCell(4), totalIntegratedTax);
    PoiHelper.setCellValue(sd.createCell(6), totalCentralTax);
    PoiHelper.setCellValue(sd.createCell(8), totalStateUtTax);
    PoiHelper.setCellValue(sd.createCell(10), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstHsnLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int col = 0;
      PoiHelper.setCellValue(row.createCell(col++), line.getHsn());
      PoiHelper.setCellValue(row.createCell(col++), line.getDescription());
      PoiHelper.setCellValue(row.createCell(col++), line.getUqc());
      PoiHelper.setCellValue(row.createCell(col++), line.getTotalQuantity());
      PoiHelper.setCellValue(row.createCell(col++), line.getTotalValue());
      PoiHelper.setCellValue(row.createCell(col++), line.getRate());
      PoiHelper.setCellValue(row.createCell(col++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(col++), line.getIntegratedTaxAmount());
      PoiHelper.setCellValue(row.createCell(col++), line.getCentralTaxAmount());
      PoiHelper.setCellValue(row.createCell(col++), line.getStateUtTaxAmount());
      PoiHelper.setCellValue(row.createCell(col++), line.getCessAmount());
    }
  }
}
