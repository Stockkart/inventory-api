package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;

import java.util.Arrays;
import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * {@code ecoa} -- supplies made through an e-commerce operator under section 9(5), which the platform does not model.
 *
 * <p>Written with its portal headers and no rows. The offline utility reads the
 * workbook as a template, every tab of it, so the sheet has to be present; an
 * empty one states there were no such supplies this period, which is true.
 */
public class Gstr1EcoaTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "ecoa";
  private static final List<String> HEADERS = Arrays.asList(
      "Nature of Supply",
      "Financial Year",
      "Original Month/Quarter",
      "Original GSTIN of E-Commerce Operator",
      "Revised GSTIN of E-Commerce Operator",
      "E-Commerce Operator Name",
      "Revised Net value of supplies",
      "Integrated tax",
      "Central tax",
      "State/UT tax",
      "Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For Amended Supplies through ECO-14A");
    // The populated tabs carry a summary block above the header; keep the same
    // shape so a reader finds the header on the row they expect.
    rowNum += 2;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum);
  }
}
