package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1HsnB2bTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "hsn(b2b)";
  private static final List<String> HEADERS = Arrays.asList(
      "HSN", "Description", "UQC", "Total Quantity", "Total Value", "Rate", "Taxable Value",
      "Integrated Tax Amount", "Central Tax Amount", "State/UT Tax Amount", "Cess Amount");

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
    for (GstHsnLine line : context.getHsnB2bLines()) {
      Row row = sheet.createRow(rowNum++);
      createDataRow(row, line);
    }
  }

  private static void createDataRow(Row row, GstHsnLine line) {
    int c = 0;
    PoiHelper.setCellValue(row.createCell(c++), line.getHsn());
    PoiHelper.setCellValue(row.createCell(c++), line.getDescription());
    PoiHelper.setCellValue(row.createCell(c++), line.getUqc());
    PoiHelper.setCellValue(row.createCell(c++), line.getTotalQuantity());
    PoiHelper.setCellValue(row.createCell(c++), line.getTotalValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getRate());
    PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
    PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getCentralTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getStateUtTaxAmount());
    PoiHelper.setCellValue(row.createCell(c++), line.getCessAmount());
  }
}
