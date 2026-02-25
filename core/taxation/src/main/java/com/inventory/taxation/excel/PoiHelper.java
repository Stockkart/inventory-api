package com.inventory.taxation.excel;

import org.apache.poi.ss.usermodel.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PoiHelper {

  public static CellStyle headerStyle(Workbook wb) {
    CellStyle s = wb.createCellStyle();
    Font f = wb.createFont();
    f.setBold(true);
    s.setFont(f);
    return s;
  }

  public static Row createHeaderRow(Sheet sheet, List<String> headers, CellStyle headerStyle) {
    return createHeaderRow(sheet, headers, headerStyle, 0);
  }

  public static Row createHeaderRow(Sheet sheet, List<String> headers, CellStyle headerStyle, int rowIndex) {
    Row row = sheet.createRow(rowIndex);
    for (int i = 0; i < headers.size(); i++) {
      Cell c = row.createCell(i);
      c.setCellValue(headers.get(i));
      c.setCellStyle(headerStyle);
    }
    return row;
  }

  public static void setCellValue(Cell cell, Object value) {
    if (value == null) return;
    if (value instanceof String) cell.setCellValue((String) value);
    else if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
    else if (value instanceof LocalDate) cell.setCellValue(((LocalDate) value).toString());
    else cell.setCellValue(value.toString());
  }

  public static void setCellValue(Cell cell, BigDecimal value) {
    if (value != null) cell.setCellValue(value.doubleValue());
  }
}
