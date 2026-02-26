package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1ExpTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "exp";
  private static final List<String> HEADERS = Arrays.asList(
      "Export Type", "Invoice Number", "Invoice date", "Invoice Value", "Port Code",
      "Shipping Bill Number", "Shipping Bill Date", "Rate", "Taxable Value", "Cess Value");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstInvoiceLine> lines = context.getExpLines();
    int noOfInvoices = lines.size();
    java.math.BigDecimal totalInvValue = lines.stream().map(GstInvoiceLine::getInvoiceValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    long noOfShippingBills = lines.stream().map(GstInvoiceLine::getShippingBillNo).filter(s -> s != null && !s.isBlank()).distinct().count();
    java.math.BigDecimal totalTaxableValue = lines.stream().map(GstInvoiceLine::getTaxableValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For EXP(6)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Invoices");
    sh.createCell(2).setCellValue("Total Invoice Value");
    sh.createCell(4).setCellValue("No. of Shipping Bill");
    sh.createCell(6).setCellValue("Total Taxable Value");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfInvoices);
    PoiHelper.setCellValue(sd.createCell(2), totalInvValue);
    PoiHelper.setCellValue(sd.createCell(4), (int) noOfShippingBills);
    PoiHelper.setCellValue(sd.createCell(6), totalTaxableValue);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstInvoiceLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getExportType() != null ? line.getExportType() : "");
      PoiHelper.setCellValue(row.createCell(1), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(2), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(3), line.getInvoiceValue());
      PoiHelper.setCellValue(row.createCell(4), line.getPortCode() != null ? line.getPortCode() : "");
      PoiHelper.setCellValue(row.createCell(5), line.getShippingBillNo() != null ? line.getShippingBillNo() : "");
      PoiHelper.setCellValue(row.createCell(6), line.getShippingBillDate());
      PoiHelper.setCellValue(row.createCell(7), line.getRate());
      PoiHelper.setCellValue(row.createCell(8), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(9), line.getCessValue() != null ? line.getCessValue() : java.math.BigDecimal.ZERO);
    }
  }
}
