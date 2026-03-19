package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2ImpgLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2ImpgTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "impg";
  private static final List<String> HEADERS = Arrays.asList(
      "Port Code", "Bill of Entry Number", "Bill Of Entry Date", "Bill Of Entry Value", "Document Type",
      "GSTIN of SEZ Supplier", "Rate", "Taxable Value", "Integrated Tax Paid", "Cess Paid",
      "Eligibility for ITC", "Availed ITC Integrated Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2ImpgLine> lines = context.getImpgLines();

    int noOfBills = lines.size();
    BigDecimal totalBoEValue = lines.stream().map(Gstr2ImpgLine::getBillOfEntryValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxable = lines.stream().map(Gstr2ImpgLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalIgst = lines.stream().map(Gstr2ImpgLine::getIntegratedTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2ImpgLine::getCessPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedIgst = lines.stream().map(Gstr2ImpgLine::getAvailedItcIntegrated)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedCess = lines.stream().map(Gstr2ImpgLine::getAvailedItcCess)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary of IMPG (4)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("No. of Bill Entry");
    sh.createCell(3).setCellValue("Total Bill of Entry Value");
    sh.createCell(7).setCellValue("Total Taxable Value");
    sh.createCell(8).setCellValue("Total Integrated Tax Paid");
    sh.createCell(9).setCellValue("Total Cess Paid");
    sh.createCell(11).setCellValue("Total Availed ITC Integrated Tax");
    sh.createCell(12).setCellValue("Total Availed ITC Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), noOfBills);
    PoiHelper.setCellValue(sd.createCell(3), totalBoEValue);
    PoiHelper.setCellValue(sd.createCell(7), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(8), totalIgst);
    PoiHelper.setCellValue(sd.createCell(9), totalCess);
    PoiHelper.setCellValue(sd.createCell(11), availedIgst);
    PoiHelper.setCellValue(sd.createCell(12), availedCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2ImpgLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getPortCode());
      PoiHelper.setCellValue(row.createCell(c++), line.getBillOfEntryNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getBillOfEntryDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getBillOfEntryValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getDocumentType());
      PoiHelper.setCellValue(row.createCell(c++), line.getSezSupplierGstin());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcEligibility());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcIntegrated());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcCess());
    }
  }
}
