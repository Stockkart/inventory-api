package com.inventory.analytics.service;

import com.inventory.analytics.rest.dto.response.PartyMoneyMisResponse;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisRowDto;
import com.inventory.analytics.rest.dto.response.PartyMoneyMisSummaryDto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class PartyMoneyMisExcelWriter {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

  public byte[] write(PartyMoneyMisResponse report) throws IOException {
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("Vendor Money MIS");
      CellStyle headerStyle = workbook.createCellStyle();
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);

      int r = 0;
      Row title = sheet.createRow(r++);
      title.createCell(0).setCellValue("Vendor Money MIS");
      Row meta = sheet.createRow(r++);
      meta.createCell(0)
          .setCellValue(
              "Period: "
                  + (report.getFrom() != null ? report.getFrom().format(DATE_FMT) : "")
                  + " – "
                  + (report.getTo() != null ? report.getTo().format(DATE_FMT) : ""));
      r++;

      Row header = sheet.createRow(r++);
      String[] cols = {
        "Date",
        "Party",
        "Txn ID",
        "Type",
        "Ref No",
        "Against",
        "Total",
        "Cash",
        "Online",
        "Credit",
        "Balance"
      };
      for (int i = 0; i < cols.length; i++) {
        Cell c = header.createCell(i);
        c.setCellValue(cols[i]);
        c.setCellStyle(headerStyle);
      }

      for (PartyMoneyMisRowDto row : report.getRows()) {
        Row excelRow = sheet.createRow(r++);
        excelRow
            .createCell(0)
            .setCellValue(row.getTxnDate() != null ? row.getTxnDate().format(DATE_FMT) : "");
        excelRow.createCell(1).setCellValue(nullToEmpty(row.getPartyName()));
        excelRow.createCell(2).setCellValue(nullToEmpty(row.getTxnId()));
        excelRow.createCell(3).setCellValue(nullToEmpty(row.getTxnTypeLabel()));
        excelRow.createCell(4).setCellValue(nullToEmpty(row.getRefNo()));
        excelRow.createCell(5).setCellValue(nullToEmpty(row.getAgainstRefNo()));
        setMoney(excelRow.createCell(6), row.getTotalAmount());
        setMoney(excelRow.createCell(7), row.getCashAmount());
        setMoney(excelRow.createCell(8), row.getOnlineAmount());
        setMoney(excelRow.createCell(9), row.getCreditAmount());
        setMoney(excelRow.createCell(10), row.getBalanceAfter());
      }

      PartyMoneyMisSummaryDto summary = report.getSummary();
      if (summary != null) {
        r++;
        Row totals = sheet.createRow(r++);
        totals.createCell(0).setCellValue("Totals");
        setMoney(totals.createCell(6), summary.getPeriodPurchaseTotal());
        setMoney(totals.createCell(7), summary.getPeriodCashTotal());
        setMoney(totals.createCell(8), summary.getPeriodOnlineTotal());
        setMoney(totals.createCell(9), summary.getPeriodCreditTotal());
        setMoney(totals.createCell(10), summary.getCurrentPayableTotal());
      }

      for (int i = 0; i < cols.length; i++) {
        sheet.autoSizeColumn(i);
      }
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private static void setMoney(Cell cell, BigDecimal value) {
    if (value == null || value.signum() == 0) {
      cell.setBlank();
      return;
    }
    cell.setCellValue(value.doubleValue());
  }

  private static String nullToEmpty(String s) {
    return s != null ? s : "";
  }
}
