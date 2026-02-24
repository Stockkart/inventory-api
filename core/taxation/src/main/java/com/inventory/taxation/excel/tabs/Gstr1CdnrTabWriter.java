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
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle);
    int rowNum = 1;
    for (GstRefundLine line : context.getCdnrLines()) {
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
