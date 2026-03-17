package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2CdnurLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2CdnurTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "cdnur";
  private static final List<String> HEADERS = Arrays.asList(
      "Note/Voucher Number", "Note/Voucher Date", "Invoice/Advance Payment Voucher num",
      "Invoice/Advance Payment Voucher dat", "Pre GST", "Document Type", "Reason For Issuing document",
      "Supply Type", "Invoice Type", "Note/Voucher Value", "Rate", "Taxable Value", "Integrated Tax Paid",
      "Central Tax Paid", "State/UT Tax Paid", "Cess Paid", "Eligibility for ITC", "Availed ITC Integrated Tax",
      "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2CdnurLine> lines = context.getCdnurLines();

    int noOfNotes = lines.size();
    BigDecimal totalNoteValue = lines.stream().map(Gstr2CdnurLine::getNoteValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxable = lines.stream().map(Gstr2CdnurLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2CdnurLine::getCessPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For CDNUR(6C)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(1).setCellValue("No. of Notes/Vouchers");
    sh.createCell(4).setCellValue("Total Note Value");
    sh.createCell(11).setCellValue("Total Taxable Value");
    sh.createCell(15).setCellValue("Total Cess");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(1), noOfNotes);
    PoiHelper.setCellValue(sd.createCell(4), totalNoteValue);
    PoiHelper.setCellValue(sd.createCell(11), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(15), totalCess);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2CdnurLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteNumber());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getPreGst());
      PoiHelper.setCellValue(row.createCell(c++), line.getDocumentType());
      PoiHelper.setCellValue(row.createCell(c++), line.getReasonForIssuing());
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplyType());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceType());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getRate());
      PoiHelper.setCellValue(row.createCell(c++), line.getTaxableValue());
      PoiHelper.setCellValue(row.createCell(c++), line.getIntegratedTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCentralTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getStateUtTaxPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getCessPaid());
      PoiHelper.setCellValue(row.createCell(c++), line.getItcEligibility());
      PoiHelper.setCellValue(row.createCell(c++), line.getAvailedItcIntegrated());
    }
  }
}
