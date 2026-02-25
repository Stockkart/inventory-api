package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstRefundLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1CdnurTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "cdnur";
  private static final List<String> HEADERS = Arrays.asList(
      "UR Type", "Note Number", "Note date", "Note Type", "Place Of Supply",
      "Note Value", "Applicable % of Tax Rate", "Rate", "Taxable Value", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstRefundLine> lines = context.getCdnurLines();
    int noOfNotes = lines.size();
    java.math.BigDecimal totalNoteValue = lines.stream().map(GstRefundLine::getNoteValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalTaxableValue = lines.stream().map(GstRefundLine::getTaxableValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCess = lines.stream().map(GstRefundLine::getCessAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For CDNUR(9B)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Notes/Vouchers");
    sh.createCell(2).setCellValue("Total Note Value");
    sh.createCell(4).setCellValue("Total Taxable Value");
    sh.createCell(6).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfNotes);
    PoiHelper.setCellValue(sd.createCell(2), totalNoteValue);
    PoiHelper.setCellValue(sd.createCell(4), totalTaxableValue);
    PoiHelper.setCellValue(sd.createCell(6), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstRefundLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getUrType() != null ? line.getUrType() : "UR");
      PoiHelper.setCellValue(row.createCell(1), line.getNoteNumber());
      PoiHelper.setCellValue(row.createCell(2), line.getNoteDate());
      PoiHelper.setCellValue(row.createCell(3), line.getNoteType());
      PoiHelper.setCellValue(row.createCell(4), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(5), line.getNoteValue());
      PoiHelper.setCellValue(row.createCell(6), line.getApplicableTaxPct());
      PoiHelper.setCellValue(row.createCell(7), line.getRate());
      PoiHelper.setCellValue(row.createCell(8), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(9), line.getCessAmount());
    }
  }
}
