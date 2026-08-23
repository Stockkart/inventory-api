package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2ImpsLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import com.inventory.taxation.utils.helper.GstTotals;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2ImpsTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "imps";
  private static final List<String> HEADERS = Arrays.asList(
      "Invoice Number of Reg Recipient", "Invoice Date", "Invoice Value", "Place Of Supply", "Rate",
      "Taxable Value", "Integrated Tax Paid", "Cess Paid", "Eligibility for ITC", "Availed ITC Integrated Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2ImpsLine> lines = context.getImpsLines();

    int noOfInvoices = GstTotals.countDistinct(lines, Gstr2ImpsLine::getInvoiceNo);
    BigDecimal totalInvValue = GstTotals.sum(lines, Gstr2ImpsLine::getInvoiceValue);
    BigDecimal totalTaxable = GstTotals.sum(lines, Gstr2ImpsLine::getTaxableValue);
    BigDecimal totalIgst = GstTotals.sum(lines, Gstr2ImpsLine::getIntegratedTaxPaid);
    BigDecimal totalCess = GstTotals.sum(lines, Gstr2ImpsLine::getCessPaid);
    BigDecimal availedIgst = GstTotals.sum(lines, Gstr2ImpsLine::getAvailedItcIntegrated);
    BigDecimal availedCess = GstTotals.sum(lines, Gstr2ImpsLine::getAvailedItcCess);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary of IMPS (4C)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Invoices (of Reg Recipient)");
    sh.createCell(2).setCellValue("Total Invoice Value");
    sh.createCell(5).setCellValue("Total Taxable Value");
    sh.createCell(6).setCellValue("Total Integrated Tax Paid");
    sh.createCell(7).setCellValue("Total Cess Paid");
    sh.createCell(9).setCellValue("Total Availed ITC Integrated Tax");
    sh.createCell(10).setCellValue("Total Availed ITC Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfInvoices);
    PoiHelper.setCellValue(sd.createCell(2), totalInvValue);
    PoiHelper.setCellValue(sd.createCell(5), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(6), totalIgst);
    PoiHelper.setCellValue(sd.createCell(7), totalCess);
    PoiHelper.setCellValue(sd.createCell(9), availedIgst);
    PoiHelper.setCellValue(sd.createCell(10), availedCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2ImpsLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getPlaceOfSupply());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcEligibility());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcIntegrated());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcCess());
    }
  }
}
