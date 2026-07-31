package com.inventory.documentservice.service;

import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for document generation and handling.
 * Handles PDF generation, Excel export, document templates, and document management.
 */
@Service
@Slf4j
public class DocumentService {

  @Autowired private InvoicePdfService invoicePdfService;

  /**
   * Generate invoice PDF.
   *
   * @param request the invoice generation request
   * @return PDF as byte array
   */
  public byte[] generateInvoice(GenerateInvoiceRequest request) {
    log.info("Generating invoice PDF for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.generateInvoicePdf(request);
  }

  /**
   * Render invoice HTML (same templates as PDF) for in-app preview without browser PDF chrome.
   */
  public String generateInvoiceHtml(GenerateInvoiceRequest request) {
    log.info("Generating invoice HTML preview for invoice: {}", request.getInvoiceNo());
    return invoicePdfService.renderInvoiceHtml(request);
  }

  /**
   * Convert XHTML/HTML to PDF using the same OpenHTMLToPDF pipeline as scan-sell invoices.
   *
   * @param html XHTML/HTML body
   * @return PDF as byte array
   */
  public byte[] generatePdfFromHtml(String html) {
    log.info("Generating PDF from HTML ({} chars)", html != null ? html.length() : 0);
    return invoicePdfService.convertHtmlToPdf(html);
  }

  /**
   * Generate a simple single-sheet Excel workbook (.xlsx).
   *
   * @param sheetName worksheet name
   * @param title optional title row (null to skip)
   * @param subtitle optional subtitle/meta row (null to skip)
   * @param headers column headers
   * @param rows data rows (cell values: String, Number, BigDecimal, Boolean, or null)
   * @param totalsRow optional totals row after a blank line (null to skip)
   * @return XLSX bytes
   */
  public byte[] generateExcel(
      String sheetName,
      String title,
      String subtitle,
      List<String> headers,
      List<List<Object>> rows,
      List<Object> totalsRow) {
    log.info("Generating Excel sheet '{}'", sheetName);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet1");
      CellStyle headerStyle = workbook.createCellStyle();
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);

      int r = 0;
      if (title != null) {
        sheet.createRow(r++).createCell(0).setCellValue(title);
      }
      if (subtitle != null) {
        sheet.createRow(r++).createCell(0).setCellValue(subtitle);
      }
      if (title != null || subtitle != null) {
        r++;
      }

      if (headers != null && !headers.isEmpty()) {
        Row header = sheet.createRow(r++);
        for (int i = 0; i < headers.size(); i++) {
          Cell c = header.createCell(i);
          c.setCellValue(headers.get(i));
          c.setCellStyle(headerStyle);
        }
      }

      if (rows != null) {
        for (List<Object> rowValues : rows) {
          Row excelRow = sheet.createRow(r++);
          writeCells(excelRow, rowValues);
        }
      }

      if (totalsRow != null && !totalsRow.isEmpty()) {
        r++;
        writeCells(sheet.createRow(r), totalsRow);
      }

      int colCount = headers != null ? headers.size() : 0;
      for (int i = 0; i < colCount; i++) {
        sheet.autoSizeColumn(i);
      }
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      log.error("Error generating Excel: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to generate Excel", e);
    }
  }

  private static void writeCells(Row excelRow, List<Object> values) {
    if (values == null) {
      return;
    }
    for (int i = 0; i < values.size(); i++) {
      setCellValue(excelRow.createCell(i), values.get(i));
    }
  }

  private static void setCellValue(Cell cell, Object value) {
    if (value == null) {
      cell.setBlank();
      return;
    }
    if (value instanceof BigDecimal bd) {
      if (bd.signum() == 0) {
        cell.setBlank();
      } else {
        cell.setCellValue(bd.doubleValue());
      }
      return;
    }
    if (value instanceof Number n) {
      cell.setCellValue(n.doubleValue());
      return;
    }
    if (value instanceof Boolean b) {
      cell.setCellValue(b);
      return;
    }
    cell.setCellValue(String.valueOf(value));
  }
}
