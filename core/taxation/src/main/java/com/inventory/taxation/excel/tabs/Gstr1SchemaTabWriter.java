package com.inventory.taxation.excel.tabs;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.PoiHelper;

import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * The GSTR-1 sheets the platform has no source data for, written with their
 * portal headers and no rows.
 *
 * <p>The offline utility expects the whole workbook, not the sheets that happen
 * to have data: it reads every tab of the template when importing, and a
 * workbook missing them is not the shape it was built to read. Eighteen sheets
 * were absent -- every amendment tab, and the whole e-commerce set.
 *
 * <p>They are empty rather than computed because the platform records nothing
 * that belongs on them. Amendment tabs restate invoices from an earlier period
 * that have since been corrected, and nothing here tracks a revision against
 * its original. The e-commerce tabs describe supplies made through an operator
 * under section 9(5), which this platform does not model at all. An empty tab
 * says "no such supplies this period", which is true, and is what the portal
 * expects; inventing rows for either would be worse than leaving them blank.
 *
 * <p>Headers are transcribed from a filed return rather than retyped, so the
 * column order matches the template exactly.
 */
public final class Gstr1SchemaTabWriter implements Gstr1TabWriter {

  private final String sheetName;
  private final String summaryTitle;
  private final List<String> headers;

  public Gstr1SchemaTabWriter(String sheetName, String summaryTitle, List<String> headers) {
    this.sheetName = sheetName;
    this.summaryTitle = summaryTitle;
    this.headers = headers;
  }

  /** Every sheet the platform cannot populate, in the order the portal lists them. */
  public static List<Gstr1TabWriter> all() {
    return List.of(
      new Gstr1SchemaTabWriter(
          "b2ba",
          "Summary For B2BA",
          List.of(
              "GSTIN/UIN of Recipient",
              "Receiver Name",
              "Original Invoice Number",
              "Original Invoice date",
              "Revised Invoice Number",
              "Revised Invoice date",
              "Invoice Value",
              "Place Of Supply",
              "Reverse Charge",
              "Applicable % of Tax Rate",
              "Invoice Type",
              "E-Commerce GSTIN",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "b2cla",
          "Summary For B2CLA",
          List.of(
              "Original Invoice Number",
              "Original Invoice date",
              "Original Place Of Supply",
              "Revised Invoice Number",
              "Revised Invoice date",
              "Invoice Value",
              "Applicable % of Tax Rate",
              "Rate",
              "Taxable Value",
              "Cess Amount",
              "E-Commerce GSTIN")),
      new Gstr1SchemaTabWriter(
          "b2csa",
          "Summary For B2CSA",
          List.of(
              "Financial Year",
              "Original Month",
              "Place Of Supply",
              "Type",
              "Applicable % of Tax Rate",
              "Rate",
              "Taxable Value",
              "Cess Amount",
              "E-Commerce GSTIN")),
      new Gstr1SchemaTabWriter(
          "cdnra",
          "Summary For CDNRA",
          List.of(
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
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "cdnura",
          "Summary For CDNURA",
          List.of(
              "UR Type",
              "Original Note Number",
              "Original Note Date",
              "Revised Note Number",
              "Revised Note Date",
              "Note Type",
              "Place Of Supply",
              "Note Value",
              "Applicable % of Tax Rate",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "expa",
          "Summary For EXPA",
          List.of(
              "Export Type",
              "Original Invoice Number",
              "Original Invoice date",
              "Revised Invoice Number",
              "Revised Invoice date",
              "Invoice Value",
              "Port Code",
              "Shipping Bill Number",
              "Shipping Bill Date",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ata",
          "Summary For Amended Tax Liability(Advance Received)",
          List.of(
              "Financial Year",
              "Original Month",
              "Original Place Of Supply",
              "Applicable % of Tax Rate",
              "Rate",
              "Gross Advance Received",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "atadja",
          "Summary For Amendement Of Adjustment Advances",
          List.of(
              "Financial Year",
              "Original Month",
              "Original Place Of Supply",
              "Applicable % of Tax Rate",
              "Rate",
              "Gross Advance Adjusted",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "eco",
          "Summary For Supplies through ECO-14",
          List.of(
              "Nature of Supply",
              "GSTIN of E-Commerce Operator",
              "E-Commerce Operator Name",
              "Net value of supplies",
              "Integrated tax",
              "Central tax",
              "State/UT tax",
              "Cess")),
      new Gstr1SchemaTabWriter(
          "ecoa",
          "Summary For Amended Supplies through ECO-14A",
          List.of(
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
              "Cess")),
      new Gstr1SchemaTabWriter(
          "ecob2b",
          "Summary For Supplies U/s 9(5)-15-B2B",
          List.of(
              "Supplier GSTIN/UIN",
              "Supplier Name",
              "Recipient GSTIN/UIN",
              "Recipient Name",
              "Document Number",
              "Document Date",
              "Value of supplies made",
              "Place Of Supply",
              "Document type",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecourp2b",
          "Summary For Supplies U/s 9(5)-15-URP2B",
          List.of(
              "Recipient GSTIN/UIN",
              "Recipient Name",
              "Document Number",
              "Document Date",
              "Value of supplies made",
              "Place Of Supply",
              "Document type",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecob2c",
          "Summary For Supplies U/s 9(5)-15-B2C",
          List.of(
              "Supplier GSTIN/UIN",
              "Supplier Name",
              "Place Of Supply",
              "Taxable Value",
              "Rate",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecourp2c",
          "Summary For Supplies U/s 9(5)-15-URP2C",
          List.of(
              "Place Of Supply",
              "Taxable Value",
              "Rate",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecoab2b",
          "Summary For Supplies U/s 9(5) - 15A-B2B",
          List.of(
              "Supplier GSTIN/UIN",
              "Supplier Name",
              "Recipient GSTIN/UIN",
              "Recipient Name",
              "Original Document Number",
              "Original Document Date",
              "Revised Document Number",
              "Revised Document Date",
              "Value of supplies made",
              "Place Of Supply",
              "Document type",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecoab2c",
          "Summary For Supplies U/s 9(5)-15A-B2C",
          List.of(
              "Financial Year",
              "Original Month",
              "Supplier GSTIN/UIN",
              "Supplier Name",
              "Place Of Supply",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecoaurp2b",
          "Summary For Supplies U/s 9(5)-15A-URP2B",
          List.of(
              "Recipient GSTIN/UIN",
              "Recipient Name",
              "Original Document Number",
              "Original Document Date",
              "Revised Document Number",
              "Revised Document Date",
              "Value of supplies made",
              "Document type",
              "Place Of Supply",
              "Rate",
              "Taxable Value",
              "Cess Amount")),
      new Gstr1SchemaTabWriter(
          "ecoaurp2c",
          "Summary For Supplies U/s 9(5)-15A-URP2C",
          List.of(
              "Financial Year",
              "Original Month",
              "Place Of Supply",
              "Rate",
              "Taxable Value",
              "Cess Amount")));
  }

  /** The writer for one sheet, by the portal's name for it. */
  public static Gstr1TabWriter of(String sheetName) {
    return all().stream()
        .filter(w -> w.getSheetName().equals(sheetName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "no schema tab named " + sheetName));
  }

  @Override
  public String getSheetName() {
    return sheetName;
  }

  @Override
  public void write(Workbook workbook, Gstr1ReportContext context) {
    Sheet sheet = workbook.createSheet(sheetName);
    CellStyle headerStyle = PoiHelper.headerStyle(workbook);
    int rowNum = 0;
    sheet.createRow(rowNum++).createCell(0).setCellValue(summaryTitle);
    // The populated tabs carry a summary block above the header; keep the same
    // shape so a reader finds the header on the row they expect.
    rowNum += 2;
    PoiHelper.createHeaderRow(sheet, headers, headerStyle, rowNum);
  }
}
