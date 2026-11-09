package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2ExempLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import com.inventory.taxation.utils.helper.GstTotals;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2ExempTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "exemp";
  private static final List<String> HEADERS = Arrays.asList(
      "Description", "Composition taxable person", "Nil Rated Supplies",
      "Exempted (other than nil rated/non GST supply )", "Non-GST supplies");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2ExempLine> lines = context.getExempLines();

    BigDecimal totalComp = GstTotals.sum(lines, Gstr2ExempLine::getCompositionTaxablePerson);
    BigDecimal totalNil = GstTotals.sum(lines, Gstr2ExempLine::getNilRatedSupplies);
    BigDecimal totalExempt = GstTotals.sum(lines, Gstr2ExempLine::getExemptedOtherThanNilOrNonGst);
    BigDecimal totalNonGst = GstTotals.sum(lines, Gstr2ExempLine::getNonGstSupplies);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For Composition, Nil rated, ");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("Total Composition Taxable Person");
    sh.createCell(2).setCellValue("Total Nil Rated Supplies");
    sh.createCell(3).setCellValue("Total Exempted Supplies");
    sh.createCell(4).setCellValue("Total Non-GST Supplies");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), totalComp);
    PoiHelper.setCellValue(sd.createCell(2), totalNil);
    PoiHelper.setCellValue(sd.createCell(3), totalExempt);
    PoiHelper.setCellValue(sd.createCell(4), totalNonGst);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2ExempLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getDescription());
      PoiHelper.setCellValue(row.createCell(c++), line.getCompositionTaxablePerson());
      PoiHelper.setCellValue(row.createCell(c++), line.getNilRatedSupplies());
      PoiHelper.setCellValue(row.createCell(c++), line.getExemptedOtherThanNilOrNonGst());
      PoiHelper.setCellValue(row.createCell(c++), line.getNonGstSupplies());
    }
  }
}
