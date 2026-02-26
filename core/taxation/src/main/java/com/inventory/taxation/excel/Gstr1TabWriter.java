package com.inventory.taxation.excel;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Writes a single sheet (tab) of GSTR-1 to the workbook.
 */
public interface Gstr1TabWriter {

  String getSheetName();

  void write(Workbook workbook, Gstr1ReportContext context);
}
