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
    java.util.List<GstInvoiceLine> lines = context.getB2csLines();
    java.math.BigDecimal totalTaxableValue = lines.stream().map(GstInvoiceLine::getTaxableValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCess = lines.stream().map(GstInvoiceLine::getCessAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For B2CS(" + lines.size() + ")");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("Total Taxable Value");
    sh.createCell(2).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), totalTaxableValue);
    PoiHelper.setCellValue(sd.createCell(2), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstInvoiceLine line : lines) {
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
