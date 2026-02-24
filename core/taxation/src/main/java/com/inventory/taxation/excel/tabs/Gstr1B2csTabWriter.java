package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1B2csTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "b2cs";
  private static final List<String> HEADERS = Arrays.asList(
      "Type", "Place of Supply", "Applicable %Tax", "Rate", "Taxable Value", "Cess Amount", "E-Commerce GSTIN");

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
    for (GstInvoiceLine line : context.getB2csLines()) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getB2csType() != null ? line.getB2csType() : "OE");
      PoiHelper.setCellValue(row.createCell(1), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(2), line.getApplicableTaxPct());
      PoiHelper.setCellValue(row.createCell(3), line.getRate());
      PoiHelper.setCellValue(row.createCell(4), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(5), line.getCessAmount());
      PoiHelper.setCellValue(row.createCell(6), line.getEcommerceGstin() != null ? line.getEcommerceGstin() : "");
    }
  }
}
