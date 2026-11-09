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
 * {@code cdnra} -- an amendment tab. It restates an invoice from an earlier period that has since been corrected, and nothing in the platform tracks a revision against its original.
 *
 * <p>Written with its portal headers and no rows. The offline utility reads the
 * workbook as a template, every tab of it, so the sheet has to be present; an
 * empty one states there were no such supplies this period, which is true.
 */
public class Gstr1CdnraTabWriter implements Gstr1TabWriter {

  private static final String SHEET_NAME = "cdnra";
  private static final List<String> HEADERS = Arrays.asList(
      "GSTIN/UIN of Recipient",
      "Receiver Name",
      "Original Note Number",
      "Original Note Date",
      "Revised Note Number",
      "Revised Note Date",
      "Note Type",
      "Place Of Supply",
      "Reverse Charge",
      "Note Supply Type",
      "Note Value",
      "Applicable % of Tax Rate",
      "Rate",
      "Taxable Value",
      "Cess Amount");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For CDNRA");
    // The populated tabs carry a summary block above the header; keep the same
    // shape so a reader finds the header on the row they expect.
    rowNum += 2;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum);
  }
}
