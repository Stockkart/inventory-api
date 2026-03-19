package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2B2bLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2B2bTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "b2b";
  private static final List<String> HEADERS = Arrays.asList(
      "GSTIN/UIN of Recipient", "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply",
      "Reverse Charge", "Invoice Type", "Rate", "Taxable Value", "Integrated Tax Paid", "Central Tax Paid",
      "State/UT Tax Paid", "Cess Amount", "Eligibility for ITC", "Availed ITC Integrated Tax",
      "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2B2bLine> lines = context.getB2bLines();

    int noOfSuppliers = (int) lines.stream().map(Gstr2B2bLine::getSupplierGstin)
        .filter(g -> g != null && !g.isBlank()).distinct().count();
    int noOfInvoices = lines.size();
    BigDecimal totalInvValue = lines.stream().map(Gstr2B2bLine::getInvoiceValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxable = lines.stream().map(Gstr2B2bLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalIgst = lines.stream().map(Gstr2B2bLine::getIntegratedTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCgst = lines.stream().map(Gstr2B2bLine::getCentralTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalSgst = lines.stream().map(Gstr2B2bLine::getStateUtTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2B2bLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedIgst = lines.stream().map(Gstr2B2bLine::getAvailedItcIntegrated)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedCgst = lines.stream().map(Gstr2B2bLine::getAvailedItcCentral)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedSgst = lines.stream().map(Gstr2B2bLine::getAvailedItcStateUt)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedCess = lines.stream().map(Gstr2B2bLine::getAvailedItcCess)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary Of Supplies From Registered");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Suppliers");
    sh.createCell(2).setCellValue("No. of Invoices");
    sh.createCell(4).setCellValue("Total Invoice Value");
    sh.createCell(8).setCellValue("Total Taxable Value");
    sh.createCell(9).setCellValue("Total Integrated tax Paid");
    sh.createCell(10).setCellValue("Total Central tax Paid");
    sh.createCell(11).setCellValue("Total State/UT Tax Paid");
    sh.createCell(12).setCellValue("Total Cess Paid");
    sh.createCell(14).setCellValue("Total Availed ITC Integrated Tax");
    sh.createCell(15).setCellValue("Total Availed ITC Central Tax");
    sh.createCell(16).setCellValue("Total Availed ITC State/UT Tax");
    sh.createCell(17).setCellValue("Total Availed ITC Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfSuppliers);
    PoiHelper.setCellValue(sd.createCell(2), noOfInvoices);
    PoiHelper.setCellValue(sd.createCell(4), totalInvValue);
    PoiHelper.setCellValue(sd.createCell(8), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(9), totalIgst);
    PoiHelper.setCellValue(sd.createCell(10), totalCgst);
    PoiHelper.setCellValue(sd.createCell(11), totalSgst);
    PoiHelper.setCellValue(sd.createCell(12), totalCess);
    PoiHelper.setCellValue(sd.createCell(14), availedIgst);
    PoiHelper.setCellValue(sd.createCell(15), availedCgst);
    PoiHelper.setCellValue(sd.createCell(16), availedSgst);
    PoiHelper.setCellValue(sd.createCell(17), availedCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2B2bLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplierGstin());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getReverseCharge());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceType());
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
