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
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle);
    int rowNum = 1;
    for (GstInvoiceLine line : context.getExpLines()) {
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
