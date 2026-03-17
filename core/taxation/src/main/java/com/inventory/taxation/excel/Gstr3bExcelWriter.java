package com.inventory.taxation.excel;

import com.inventory.taxation.domain.gstr3b.Gstr3bReportContext;
import org.apache.poi.ss.usermodel.*;
import java.math.BigDecimal;

/**
 * Writes GSTR-3B form to Excel (single sheet).
 */
public final class Gstr3bExcelWriter {

  private static final String SHEET_NAME = "GSTR3B";

  public static void write(Workbook workbook, Gstr3bReportContext ctx) {
    Sheet sheet = workbook.createSheet(SHEET_NAME);
    int rowNum = 0;

    rowNum = writeHeader(sheet, rowNum, ctx);
    rowNum = writeSection31(sheet, rowNum, ctx);
    rowNum = writeSection32(sheet, rowNum, ctx);
    rowNum = writeSection4(sheet, rowNum, ctx);
    rowNum = writeSection5(sheet, rowNum, ctx);
    rowNum = writeSection61(sheet, rowNum, ctx);
    writeFooter(sheet, rowNum);
  }

  private static int writeHeader(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    createCell(sheet, rowNum++, 0, "Form GSTR-3B");
    createCell(sheet, rowNum++, 0, "[See Rule 61(5)]");
    rowNum++;
    createCell(sheet, rowNum, 0, "1.  GSTIN :" + (ctx.getShopGstin() != null ? ctx.getShopGstin() : ""));
    createCell(sheet, rowNum, 11, "Year");
    createCell(sheet, rowNum, 12, ctx.getYear());
    rowNum++;
    createCell(sheet, rowNum, 11, "Month");
    createCell(sheet, rowNum, 12, ctx.getMonth());
    rowNum++;
    createCell(sheet, rowNum++, 0, "2. Legal name of the registered person :" + (ctx.getLegalName() != null ? ctx.getLegalName() : ""));
    return rowNum;
  }

  private static int writeSection31(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    var s = ctx.getSection31();
    if (s == null) return rowNum;

    createCell(sheet, rowNum++, 0, "3.1  Detail of Outward supplies and inward supplies liable to reverse charge");
    createCell(sheet, rowNum, 0, "Nature of Supplies");
    createCell(sheet, rowNum, 2, "Total Taxable Value");
    createCell(sheet, rowNum, 4, "Integrated Tax");
    createCell(sheet, rowNum, 6, "Central Tax");
    createCell(sheet, rowNum, 8, "State/UT Tax");
    createCell(sheet, rowNum, 10, "Cess");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 4, "3");
    createCell(sheet, rowNum, 6, "4");
    createCell(sheet, rowNum, 8, "5");
    createCell(sheet, rowNum, 10, "6");
    rowNum++;

    createCell(sheet, rowNum, 0, "(a) Outward taxable supplies (other than zero rated)");
    setNum(sheet, rowNum, 2, s.getOutwardTaxableValue());
    setNum(sheet, rowNum, 4, s.getOutwardTaxableIgst());
    setNum(sheet, rowNum, 6, s.getOutwardTaxableCgst());
    setNum(sheet, rowNum, 8, s.getOutwardTaxableSgst());
    setNum(sheet, rowNum, 10, s.getOutwardTaxableCess());
    rowNum++;

    createCell(sheet, rowNum, 0, "(b) Outward taxable supplies (zero rated)");
    setNum(sheet, rowNum, 2, s.getZeroRatedValue());
    setNum(sheet, rowNum, 4, s.getZeroRatedIgst());
    rowNum++;

    createCell(sheet, rowNum, 0, "(c) Other outward supplies, (Nil rated,exempted)");
    setNum(sheet, rowNum, 2, s.getNilExemptValue());
    rowNum++;

    createCell(sheet, rowNum, 0, "(d) Inward supplies (liable to reverse charge)");
    setNum(sheet, rowNum, 2, s.getInwardRcmValue());
    setNum(sheet, rowNum, 4, s.getInwardRcmIgst());
    setNum(sheet, rowNum, 6, s.getInwardRcmCgst());
    setNum(sheet, rowNum, 8, s.getInwardRcmSgst());
    setNum(sheet, rowNum, 10, s.getInwardRcmCess());
    rowNum++;

    createCell(sheet, rowNum, 0, "(e) Non-GST Outward supplies");
    setNum(sheet, rowNum, 2, s.getNonGstValue());
    rowNum++;
    return rowNum;
  }

  private static int writeSection32(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    createCell(sheet, rowNum++, 0, "3.2 of the supplies show in 3.1(a) above,details of inter-State supplies made to unregistered persons");
    createCell(sheet, rowNum, 2, "Place of Supply(State/UT)");
    createCell(sheet, rowNum, 4, "Total Taxable Value");
    createCell(sheet, rowNum, 6, "Amount of Integrated Tax");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 4, "3");
    createCell(sheet, rowNum, 6, "4");
    rowNum++;
    for (var line : ctx.getInterStateSupplies()) {
      createCell(sheet, rowNum, 2, line.getPlaceOfSupply());
      setNum(sheet, rowNum, 4, line.getTaxableValue());
      setNum(sheet, rowNum, 6, line.getIntegratedTax());
      rowNum++;
    }
    rowNum++;
    return rowNum;
  }

  private static int writeSection4(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    var s = ctx.getSection4();
    if (s == null) return rowNum;

    createCell(sheet, rowNum++, 0, "4. Eligible ITC");
    createCell(sheet, rowNum, 0, "Details");
    createCell(sheet, rowNum, 2, "Integrated Tax");
    createCell(sheet, rowNum, 4, "Central Tax");
    createCell(sheet, rowNum, 6, "State/UT Tax");
    createCell(sheet, rowNum, 8, "Cess");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 4, "3");
    createCell(sheet, rowNum, 6, "4");
    createCell(sheet, rowNum, 8, "5");
    rowNum++;

    createCell(sheet, rowNum++, 0, "(A) ITC available (whether in full or part)");
    createCell(sheet, rowNum, 0, "(1) Import of Goods");
    setNum(sheet, rowNum, 2, s.getItcImportGoodsIgst());
    setNum(sheet, rowNum, 4, s.getItcImportGoodsCgst());
    setNum(sheet, rowNum, 6, s.getItcImportGoodsSgst());
    setNum(sheet, rowNum, 8, s.getItcImportGoodsCess());
    rowNum++;
    createCell(sheet, rowNum, 0, "(2) Import of Services");
    setNum(sheet, rowNum, 2, s.getItcImportServicesIgst());
    setNum(sheet, rowNum, 4, s.getItcImportServicesCgst());
    setNum(sheet, rowNum, 6, s.getItcImportServicesSgst());
    setNum(sheet, rowNum, 8, s.getItcImportServicesCess());
    rowNum++;
    createCell(sheet, rowNum, 0, "(3) Inward Supplies liable to reverse charge (other than 1 & 2 above)");
    setNum(sheet, rowNum, 2, s.getItcRcmIgst());
    setNum(sheet, rowNum, 4, s.getItcRcmCgst());
    setNum(sheet, rowNum, 6, s.getItcRcmSgst());
    setNum(sheet, rowNum, 8, s.getItcRcmCess());
    rowNum++;
    createCell(sheet, rowNum, 0, "(4) Inward supplies from ISD");
    setNum(sheet, rowNum, 2, s.getItcIsdIgst());
    setNum(sheet, rowNum, 4, s.getItcIsdCgst());
    setNum(sheet, rowNum, 6, s.getItcIsdSgst());
    setNum(sheet, rowNum, 8, s.getItcIsdCess());
    rowNum++;
    createCell(sheet, rowNum, 0, "(5) All other ITC");
    setNum(sheet, rowNum, 2, s.getItcOtherIgst());
    setNum(sheet, rowNum, 4, s.getItcOtherCgst());
    setNum(sheet, rowNum, 6, s.getItcOtherSgst());
    setNum(sheet, rowNum, 8, s.getItcOtherCess());
    rowNum++;

    createCell(sheet, rowNum++, 0, "(B) ITC reversed");
    createCell(sheet, rowNum, 0, "(1) As per rules 38,42 & 43 of CGST Rules and section 17(5)");
    setNum(sheet, rowNum, 2, s.getItcReversedRulesIgst());
    setNum(sheet, rowNum, 4, s.getItcReversedRulesCgst());
    setNum(sheet, rowNum, 6, s.getItcReversedRulesSgst());
    setNum(sheet, rowNum, 8, s.getItcReversedRulesCess());
    rowNum++;
    createCell(sheet, rowNum, 0, "(2) Others");
    setNum(sheet, rowNum, 2, s.getItcReversedOthersIgst());
    setNum(sheet, rowNum, 4, s.getItcReversedOthersCgst());
    setNum(sheet, rowNum, 6, s.getItcReversedOthersSgst());
    setNum(sheet, rowNum, 8, s.getItcReversedOthersCess());
    rowNum++;

    BigDecimal netIgst = orZero(s.getItcOtherIgst()).subtract(orZero(s.getItcReversedOthersIgst()).add(orZero(s.getItcReversedRulesIgst())));
    BigDecimal netCgst = orZero(s.getItcOtherCgst()).subtract(orZero(s.getItcReversedOthersCgst()).add(orZero(s.getItcReversedRulesCgst())));
    BigDecimal netSgst = orZero(s.getItcOtherSgst()).subtract(orZero(s.getItcReversedOthersSgst()).add(orZero(s.getItcReversedRulesSgst())));
    createCell(sheet, rowNum, 0, "(C) Net ITC Available (A)-(B)");
    setNum(sheet, rowNum, 2, netIgst);
    setNum(sheet, rowNum, 4, netCgst);
    setNum(sheet, rowNum, 6, netSgst);
    rowNum++;

    createCell(sheet, rowNum++, 0, "(d) Other Details");
    createCell(sheet, rowNum++, 0, "(1) ITC reclaimed which was reversed under Table 4(B)(2) in earlier tax period");
    createCell(sheet, rowNum++, 0, "(2) Ineligible ITC under section 16(4) & ITC restricted to section 17(5)");
    rowNum++;
    return rowNum;
  }

  private static int writeSection5(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    var s = ctx.getSection5();
    if (s == null) return rowNum;

    createCell(sheet, rowNum++, 0, "5. Values of exempt,nil-rated and non-gst inward supplies");
    createCell(sheet, rowNum, 0, "Nature of Supplies");
    createCell(sheet, rowNum, 2, "Inter-state supplies");
    createCell(sheet, rowNum, 4, "Intra-State supplies");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 4, "3");
    rowNum++;
    createCell(sheet, rowNum, 0, "From a supplier under composition scheme,exempt and nil rated");
    setNum(sheet, rowNum, 2, s.getCompExemptInterState());
    setNum(sheet, rowNum, 4, s.getCompExemptIntraState());
    rowNum++;
    createCell(sheet, rowNum, 0, "Non GST Supply");
    setNum(sheet, rowNum, 2, s.getNonGstInterState());
    setNum(sheet, rowNum, 4, s.getNonGstIntraState());
    rowNum++;
    rowNum++;
    return rowNum;
  }

  private static int writeSection61(Sheet sheet, int rowNum, Gstr3bReportContext ctx) {
    var s = ctx.getSection61();
    if (s == null) return rowNum;

    createCell(sheet, rowNum++, 0, "6.1  Payment of Tax");
    createCell(sheet, rowNum, 0, "Description");
    createCell(sheet, rowNum, 2, "Tax payble");
    createCell(sheet, rowNum, 3, "Paid through ITC");
    createCell(sheet, rowNum, 7, "Tax paid TDS/TCS");
    createCell(sheet, rowNum, 8, "Tax/cess paid in cash");
    createCell(sheet, rowNum, 9, "interest");
    createCell(sheet, rowNum, 10, "Late fee");
    rowNum++;
    createCell(sheet, rowNum, 0, "");
    createCell(sheet, rowNum, 2, "");
    createCell(sheet, rowNum, 3, "Integrated Tax");
    createCell(sheet, rowNum, 4, "Central Tax");
    createCell(sheet, rowNum, 5, "State/UT Tax");
    createCell(sheet, rowNum, 6, "Cess");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 3, "3");
    createCell(sheet, rowNum, 4, "4");
    createCell(sheet, rowNum, 5, "5");
    createCell(sheet, rowNum, 6, "6");
    createCell(sheet, rowNum, 7, "7");
    createCell(sheet, rowNum, 8, "8");
    createCell(sheet, rowNum, 9, "9");
    createCell(sheet, rowNum, 10, "10");
    rowNum++;

    createCell(sheet, rowNum, 0, "Integrated Tax");
    setNum(sheet, rowNum, 2, s.getIgstPayable());
    setNum(sheet, rowNum, 3, s.getIgstPaidByItc());
    setNum(sheet, rowNum, 8, s.getIgstPaidByCash());
    rowNum++;

    createCell(sheet, rowNum, 0, "Central Tax");
    setNum(sheet, rowNum, 2, s.getCgstPayable());
    setNum(sheet, rowNum, 3, s.getCgstPaidByItcIgst());
    setNum(sheet, rowNum, 4, s.getCgstPaidByItcCgst());
    setNum(sheet, rowNum, 5, s.getCgstPaidByItcSgst());
    setNum(sheet, rowNum, 8, s.getCgstPaidByCash());
    rowNum++;

    createCell(sheet, rowNum, 0, "State/UT Tax");
    setNum(sheet, rowNum, 2, s.getSgstPayable());
    setNum(sheet, rowNum, 3, s.getSgstPaidByItcIgst());
    setNum(sheet, rowNum, 4, s.getSgstPaidByItcCgst());
    setNum(sheet, rowNum, 5, s.getSgstPaidByItcSgst());
    setNum(sheet, rowNum, 8, s.getSgstPaidByCash());
    rowNum++;

    createCell(sheet, rowNum, 0, "Cess");
    setNum(sheet, rowNum, 2, s.getCessPayable());
    setNum(sheet, rowNum, 6, s.getCessPaidByItc());
    setNum(sheet, rowNum, 8, s.getCessPaidByCash());
    rowNum++;

    rowNum += 2;
    createCell(sheet, rowNum++, 0, "6.2  TDS/TCS Credit");
    createCell(sheet, rowNum, 0, "Details");
    createCell(sheet, rowNum, 2, "Integrated Tax ");
    createCell(sheet, rowNum, 4, "Central Tax");
    createCell(sheet, rowNum, 6, "State/UT Tax");
    rowNum++;
    createCell(sheet, rowNum, 0, "1");
    createCell(sheet, rowNum, 2, "2");
    createCell(sheet, rowNum, 4, "3");
    createCell(sheet, rowNum, 6, "4");
    rowNum++;
    createCell(sheet, rowNum++, 0, "TDS");
    createCell(sheet, rowNum++, 0, "TCS");
    return rowNum;
  }

  private static void writeFooter(Sheet sheet, int rowNum) {
    rowNum += 2;
    createCell(sheet, rowNum++, 0, "Verification (by Authorised signatory)");
    rowNum += 3;
    createCell(sheet, rowNum++, 0, "I hereby solemnly affirm and declare that the information given above is correct to the best of my knowledge and belief.");
    createCell(sheet, rowNum++, 0, "correct to the best of my knowledge and belief and nothing has been concealed therefrom.");
  }

  private static void createCell(Sheet sheet, int rowNum, int col, Object value) {
    Row row = sheet.getRow(rowNum);
    if (row == null) row = sheet.createRow(rowNum);
    Cell cell = row.createCell(col);
    if (value instanceof BigDecimal) {
      PoiHelper.setCellValue(cell, (BigDecimal) value);
    } else if (value instanceof Number) {
      cell.setCellValue(((Number) value).doubleValue());
    } else {
      cell.setCellValue(value != null ? value.toString() : "");
    }
  }

  private static void setNum(Sheet sheet, int rowNum, int col, BigDecimal value) {
    if (value == null) return;
    Row row = sheet.getRow(rowNum);
    if (row == null) row = sheet.createRow(rowNum);
    PoiHelper.setCellValue(row.createCell(col), value);
  }

  private static BigDecimal orZero(BigDecimal b) {
    return b != null ? b : BigDecimal.ZERO;
  }
}
