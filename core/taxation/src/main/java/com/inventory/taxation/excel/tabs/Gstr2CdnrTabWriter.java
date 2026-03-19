package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr2.Gstr2CdnrLine;
import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
import com.inventory.taxation.excel.PoiHelper;
import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public class Gstr2CdnrTabWriter implements Gstr2TabWriter {

  private static final String SHEET_NAME = "cdnr";
  private static final List<String> HEADERS = Arrays.asList(
      "GSTIN of Supplier", "Note/Refund Voucher Number", "Note/Refund Voucher date",
      "Invoice/Advance Payment Voucher Num", "Invoice/Advance Payment Voucher dat", "Pre GST", "Document Type",
      "Reason For Issuing document", "Supply Type", "Note/Refund Voucher Value", "Rate", "Taxable Value",
      "Integrated Tax Paid", "Central Tax Paid", "State/UT Tax Paid", "Cess Paid", "Eligibility for ITC",
      "Availed ITC Integrated Tax", "Availed ITC Central Tax", "Availed ITC State/UT Tax", "Availed ITC Cess");

  @Override
  public String getSheetName() {
    return SHEET_NAME;
  }

  @Override
  public void write(Workbook workbook, Gstr2ReportContext context) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    List<Gstr2CdnrLine> lines = context.getCdnrLines();

    int noOfSuppliers = (int) lines.stream().map(Gstr2CdnrLine::getSupplierGstin)
        .filter(g -> g != null && !g.isBlank()).distinct().count();
    int noOfNotes = lines.size();
    BigDecimal totalNoteValue = lines.stream().map(Gstr2CdnrLine::getNoteValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalTaxable = lines.stream().map(Gstr2CdnrLine::getTaxableValue)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalIgst = lines.stream().map(Gstr2CdnrLine::getIntegratedTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCgst = lines.stream().map(Gstr2CdnrLine::getCentralTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalSgst = lines.stream().map(Gstr2CdnrLine::getStateUtTaxPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCess = lines.stream().map(Gstr2CdnrLine::getCessPaid)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal availedIgst = lines.stream().map(Gstr2CdnrLine::getAvailedItcIntegrated)
        .filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);

    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue("Summary For CDNR(6C)");
    Row sh = sheet.createRow(rowNum++);
    sh.createCell(0).setCellValue("No. of Supplier");
    sh.createCell(1).setCellValue("No. of Notes/Vouchers");
    sh.createCell(3).setCellValue("No. of Invoices");
    sh.createCell(8).setCellValue("Total Note/Voucher Value");
    sh.createCell(11).setCellValue("Total Taxable Value");
    sh.createCell(12).setCellValue("Total Integrated Tax Paid");
    sh.createCell(13).setCellValue("Total Central Tax Paid");
    sh.createCell(14).setCellValue("Total State/UT Tax Paid");
    sh.createCell(15).setCellValue("Total Cess");
    sh.createCell(17).setCellValue("Total Availed ITC Integrated Tax");
    Row sd = sheet.createRow(rowNum++);
    PoiHelper.setCellValue(sd.createCell(0), noOfSuppliers);
    PoiHelper.setCellValue(sd.createCell(1), noOfNotes);
    PoiHelper.setCellValue(sd.createCell(3), 0);
    PoiHelper.setCellValue(sd.createCell(8), totalNoteValue);
    PoiHelper.setCellValue(sd.createCell(11), totalTaxable);
    PoiHelper.setCellValue(sd.createCell(12), totalIgst);
    PoiHelper.setCellValue(sd.createCell(13), totalCgst);
    PoiHelper.setCellValue(sd.createCell(14), totalSgst);
    PoiHelper.setCellValue(sd.createCell(15), totalCess);
    PoiHelper.setCellValue(sd.createCell(17), availedIgst);
    rowNum++;
    PoiHelper.createHeaderRow(sheet, HEADERS, headerStyle, rowNum++);
    for (Gstr2CdnrLine line : lines) {
      Row row = sheet.createRow(rowNum++);
      int c = 0;
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplierGstin());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteNumber());
      PoiHelper.setCellValue(row.createCell(c++), line.getNoteDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceNo());
      PoiHelper.setCellValue(row.createCell(c++), line.getInvoiceDate());
      PoiHelper.setCellValue(row.createCell(c++), line.getPreGst());
      PoiHelper.setCellValue(row.createCell(c++), line.getDocumentType());
      PoiHelper.setCellValue(row.createCell(c++), line.getReasonForIssuing());
      PoiHelper.setCellValue(row.createCell(c++), line.getSupplyType());
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
