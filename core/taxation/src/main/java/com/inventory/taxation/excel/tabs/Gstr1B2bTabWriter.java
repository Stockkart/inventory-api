package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1B2bTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "b2b,sez,de";
  private static final List<String> HEADERS = Arrays.asList(
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
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle);
    int rowNum = 1;
    for (GstInvoiceLine line : context.getB2bLines()) {
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
