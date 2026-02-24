package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstExemptLine;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.util.Arrays;
import java.util.List;

public class Gstr1ExempTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "exemp";
  private static final List<String> HEADERS = Arrays.asList(
      "Description", "Nil Rated Supplies", "Exempted (other than nil rated/non GST supply)", "Non-GST supplies");

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
    for (GstExemptLine line : context.getExempLines()) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getDescription());
      PoiHelper.setCellValue(row.createCell(1), line.getNilRatedSupplies());
      PoiHelper.setCellValue(row.createCell(2), line.getExemptedOtherThanNilOrNonGst());
      PoiHelper.setCellValue(row.createCell(3), line.getNonGstSupplies());
    }
  }
}
