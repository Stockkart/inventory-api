package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr1B2bTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "b2b,sez,de";
  private static final List<String> DATA_HEADERS = Arrays.asList(
      "GSTIN/UIN of Recipient", "Receiver Name", "Invoice Number", "Invoice date", "Invoice Value",
      "Place Of Supply", "Reverse Charge", "Applicable %Tax", "Invoice Type", "E-Commerce GSTIN",
      "Rate", "Taxable Value", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);

    List<GstInvoiceLine> lines = context.getB2bLines();
    int noOfRecipients = (int) lines.stream().map(GstInvoiceLine::getRecipientGstin)
        .filter(g -> g != null && !g.isBlank()).distinct().count();
    int noOfInvoices = lines.size();
    BigDecimal totalInvoiceValue = lines.stream().map(GstInvoiceLine::getInvoiceValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal taxableValue = lines.stream().map(GstInvoiceLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cessAmount = lines.stream().map(GstInvoiceLine::getCessAmount)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    Row titleRow = sheet.createRow(rowNum++);
    titleRow.createCell(0).setCellValue("Summary For B2B(" + noOfInvoices + ")");
    Row summaryHeaderRow = sheet.createRow(rowNum++);
    summaryHeaderRow.createCell(0).setCellValue("No. of Recipients");
    summaryHeaderRow.createCell(2).setCellValue("No. of Invoices");
    summaryHeaderRow.createCell(4).setCellValue("Total Invoice Value");
    summaryHeaderRow.createCell(10).setCellValue("Taxable Value");
    summaryHeaderRow.createCell(12).setCellValue("Cess Amount");
    Row summaryDataRow = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(summaryDataRow.createCell(0), noOfRecipients);
    PoiHelper.setCellValue(summaryDataRow.createCell(2), noOfInvoices);
    PoiHelper.setCellValue(summaryDataRow.createCell(4), totalInvoiceValue);
    PoiHelper.setCellValue(summaryDataRow.createCell(10), taxableValue);
    PoiHelper.setCellValue(summaryDataRow.createCell(12), cessAmount);
    rowNum++; // blank row before data

    PoiHelper.createHeaderRow(sheet, DATA_HEADERS, headerStyle, rowNum++);
    for (GstInvoiceLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      createDataRow(row, line);
    }
  }

  private static void createDataRow(Row row, GstInvoiceLine line) {
    int c = 0;
    PoiHelper.setCellValue(row.createCell(c++), line.getRecipientGstin());
    PoiHelper.setCellValue(row.createCell(c++), line.getReceiverName());
    PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
    PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
    PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
    PoiHelper.setCellValue(row.createCell(c++), line.getReverseCharge());
    PoiHelper.setCellValue(row.createCell(c++), line.getApplicableTaxPct());
    PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceType());
    PoiHelper.setCellValue(row.createCell(c++), line.getEcommerceGstin());
    PoiHelper.setCellValue(row.createCell(c++), line.getRate());
    PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
  }
}
