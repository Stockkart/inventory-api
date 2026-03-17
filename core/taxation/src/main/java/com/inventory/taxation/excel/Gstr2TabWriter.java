package com.inventory.taxation.excel;

import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Writes a single tab/sheet for GSTR-2 Excel export.
 */
public interface Gstr2TabWriter {

  String getSheetName();

  void write(Workbook workbook, Gstr2ReportContext context);
}
