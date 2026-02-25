package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstRefundLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1CdnrTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "cdnr";
  private static final List<String> HEADERS = Arrays.asList(
      "GSTIN/UIN of Recipient", "Receiver Name", "Note Number", "Note date", "Note Type",
      "Place Of Supply", "Reverse Charge", "Note Supply Type", "Note Value", "Applicable % of Tax Rate",
      "Rate", "Taxable Value", "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    java.util.List<GstRefundLine> lines = context.getCdnrLines();
    int noOfRecipients = (int) lines.stream().map(GstRefundLine::getRecipientGstin).filter(g -> g != null && !g.isBlank()).distinct().count();
    int noOfNotes = lines.size();
    java.math.BigDecimal totalNoteValue = lines.stream().map(GstRefundLine::getNoteValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalTaxableValue = lines.stream().map(GstRefundLine::getTaxableValue).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalCess = lines.stream().map(GstRefundLine::getCessAmount).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For CDNR(9B)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Recipients");
    sh.createCell(2).setCellValue("No. of Notes");
    sh.createCell(4).setCellValue("Total Note Value");
    sh.createCell(8).setCellValue("Total Taxable Val");
    sh.createCell(10).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfRecipients);
    PoiHelper.setCellValue(sd.createCell(2), noOfNotes);
    PoiHelper.setCellValue(sd.createCell(4), totalNoteValue);
    PoiHelper.setCellValue(sd.createCell(8), totalTaxableValue);
    PoiHelper.setCellValue(sd.createCell(10), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstRefundLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getRecipientGstin());
      PoiHelper.setCellValue(row.createCell(c++), line.getReceiverName());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteNumber());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteType());
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getReverseCharge());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteSupplyType());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getApplicableTaxPct());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
    }
  }
}
