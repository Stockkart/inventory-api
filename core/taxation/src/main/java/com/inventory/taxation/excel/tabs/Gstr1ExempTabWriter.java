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
    java.util.List<GstExemptLine> lines = context.getExempLines();
    java.math.BigDecimal totalNil = lines.stream().map(GstExemptLine::getNilRatedSupplies).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalExempted = lines.stream().map(GstExemptLine::getExemptedOtherThanNilOrNonGst).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    java.math.BigDecimal totalNonGst = lines.stream().map(GstExemptLine::getNonGstSupplies).filter(v -> v != null).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For Nil rated, exempted and non GST outward supplies (8)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("Total Nil Rated Supplies");
    sh.createCell(2).setCellValue("Total Exempted Supplies");
    sh.createCell(4).setCellValue("Total Non-GST Supplies");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), totalNil);
    PoiHelper.setCellValue(sd.createCell(2), totalExempted);
    PoiHelper.setCellValue(sd.createCell(4), totalNonGst);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (GstExemptLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      PoiHelper.setCellValue(row.createCell(0), line.getDescription());
      PoiHelper.setCellValue(row.createCell(1), line.getNilRatedSupplies());
      PoiHelper.setCellValue(row.createCell(2), line.getExemptedOtherThanNilOrNonGst());
      PoiHelper.setCellValue(row.createCell(3), line.getNonGstSupplies());
    }
  }
}
