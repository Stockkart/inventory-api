package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1HsnB2bTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "hsn(b2b)";
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
    java.util.List<GstHsnLine> lines = context.getHsnB2bLines();
    int noOfHsn = lines.size();
    java.math.BigDecimal totalValue = lines.stream().map(GstHsnLine::getTotalValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalTaxableValue = lines.stream().map(GstHsnLine::getTaxableValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalIntegratedTax = lines.stream().map(GstHsnLine::getIntegratedTaxAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCentralTax = lines.stream().map(GstHsnLine::getCentralTaxAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalStateUtTax = lines.stream().map(GstHsnLine::getStateUtTaxAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCess = lines.stream().map(GstHsnLine::getCessAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
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
      createDataRow(row, line);
    }
  }

  private static void createDataRow(Row row, GstHsnLine line) {
    int c = 0;
    PoiHelper.setCellValue(row.createCell(c++), line.getHsn());
    PoiHelper.setCellValue(row.createCell(c++), line.getDescription());
    PoiHelper.setCellValue(row.createCell(c++), line.getUqc());
    PoiHelper.setCellValue(row.createCell(c++), line.getTotalQuantity());
    PoiHelper.setCellValue(row.createCell(c++), line.getTotalValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getRate());
    PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getCentralTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getStateUtTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
  }
}
