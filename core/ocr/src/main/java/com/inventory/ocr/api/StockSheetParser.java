package com.inventory.ocr.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads inventory lines from an Excel stock sheet exported by another app (.xls or .xlsx). The
 * header row and column meanings are detected automatically.
 */
public interface StockSheetParser {

  /**
   * @param filename original file name; {@code .xlsx} selects the newer format, else {@code .xls}
   * @return the valid lines found, in sheet order (at most 5,000)
   */
  List<ParsedInvoiceItem> parse(InputStream inputStream, String filename) throws IOException;
}
