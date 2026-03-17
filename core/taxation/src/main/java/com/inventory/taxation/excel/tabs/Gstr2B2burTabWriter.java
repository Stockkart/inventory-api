package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2B2burLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2B2burTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "b2bur";
  private static final List<String> HEADERS = Arrays.asList(
      "Supplier Name", "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply", "Supply Type",
      "Rate", "Taxable Value", "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Amount",
      "Eligibility for ITC", "Availed ITC Integrated Tax", "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2B2burLine> lines = context.getB2burLines();

    int noOfInvoices = lines.size();
    BigDecimal totalInvValue = lines.stream().map(Gstr2B2burLine::getInvoiceValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxable = lines.stream().map(Gstr2B2burLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalIgst = lines.stream().map(Gstr2B2burLine::getIntegratedTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCgst = lines.stream().map(Gstr2B2burLine::getCentralTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalSgst = lines.stream().map(Gstr2B2burLine::getStateUtTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2B2burLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary Of Supplies From Unregister");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("No. of Invoices");
    sh.createCell(3).setCellValue("Total Inv Value");
    sh.createCell(7).setCellValue("Total Taxable Value");
    sh.createCell(8).setCellValue("Total Integrated Tax Paid");
    sh.createCell(9).setCellValue("Total Central Tax Paid");
    sh.createCell(10).setCellValue("Total State/UT Tax Paid");
    sh.createCell(11).setCellValue("Total Cess Paid");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), noOfInvoices);
    PoiHelper.setCellValue(sd.createCell(3), totalInvValue);
    PoiHelper.setCellValue(sd.createCell(7), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(8), totalIgst);
    PoiHelper.setCellValue(sd.createCell(9), totalCgst);
    PoiHelper.setCellValue(sd.createCell(10), totalSgst);
    PoiHelper.setCellValue(sd.createCell(11), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2B2burLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplierName());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplyType());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCentralTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getStateUtTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcEligibility());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcIntegrated());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcCentral());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcStateUt());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcCess());
    }
  }
}
