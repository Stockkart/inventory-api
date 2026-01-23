package com.inventory.ocr.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.model.OcrCell;
import com.inventory.ocr.model.OcrResult;
import com.inventory.ocr.model.OcrTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for parsing invoice data extracted from OCR and extracting inventory items.
 * This service works with provider-agnostic OCR results, making it independent of the OCR provider used.
 */
@Service
@Slf4j
public class InvoiceParserService {

  @Autowired
  private OcrService ocrService;

  /**
   * Extract inventory items from an invoice image using OCR.
   *
   * @param imageBytes the image file as byte array
   * @return list of parsed inventory items
   */
  public List<ParsedInventoryItem> parseInvoiceImage(byte[] imageBytes) {
    log.info("Parsing invoice image to extract inventory items");
    
    try {
      // Analyze document using OCR provider
      OcrResult ocrResult = ocrService.analyzeDocument(imageBytes);
      log.info("OCR analysis completed. Found {} tables", 
          ocrResult.getTables() != null ? ocrResult.getTables().size() : 0);
      
      // Parse tables from OCR result
      return parseOcrTables(ocrResult);
    } catch (IOException e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to parse invoice image: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to parse invoice image: " + e.getMessage(), e);
    }
  }

  /**
   * Parse tables from OCR result and extract inventory items.
   * This method works with provider-agnostic OcrResult model.
   *
   * @param ocrResult the OCR analysis result
   * @return list of parsed inventory items
   */
  private List<ParsedInventoryItem> parseOcrTables(OcrResult ocrResult) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    
    if (ocrResult.getTables() == null || ocrResult.getTables().isEmpty()) {
      log.warn("No tables found in OCR result");
      return items;
    }

    log.info("Found {} tables in the document", ocrResult.getTables().size());

    // Process each table
    for (OcrTable table : ocrResult.getTables()) {
      List<ParsedInventoryItem> tableItems = parseTable(table);
      items.addAll(tableItems);
    }

    log.info("Total inventory items parsed from {} tables: {}", ocrResult.getTables().size(), items.size());
    
    // Log summary of extracted items
    if (!items.isEmpty()) {
      log.info("Sample extracted items:");
      for (int i = 0; i < Math.min(3, items.size()); i++) {
        ParsedInventoryItem item = items.get(i);
        log.info("  Item {}: {} - Qty: {}, MRP: {}, Company: {}", 
            i + 1, item.getName(), item.getQuantity(), item.getMrp(), item.getCompanyName());
      }
    }
    
    return items;
  }

  /**
   * Parse a single table and extract inventory items.
   * Works with provider-agnostic OcrTable model.
   *
   * @param table the OCR table
   * @return list of parsed inventory items from the table
   */
  private List<ParsedInventoryItem> parseTable(OcrTable table) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    
    if (table.getCells() == null || table.getCells().isEmpty()) {
      log.debug("Table has no cells");
      return items;
    }

    log.debug("Processing table with {} rows x {} columns", 
        table.getRowCount(), table.getColumnCount());

    // Convert OcrTable to internal format for processing
    Map<Integer, Map<Integer, CellData>> tableData = new HashMap<>();
    int maxRow = 0;
    int maxCol = 0;

    for (Map.Entry<Integer, Map<Integer, OcrCell>> rowEntry : table.getCells().entrySet()) {
      Integer rowIndex = rowEntry.getKey();
      maxRow = Math.max(maxRow, rowIndex);

      Map<Integer, CellData> rowData = new HashMap<>();
      for (Map.Entry<Integer, OcrCell> cellEntry : rowEntry.getValue().entrySet()) {
        Integer colIndex = cellEntry.getKey();
        OcrCell ocrCell = cellEntry.getValue();
        maxCol = Math.max(maxCol, colIndex);

        CellData cellData = new CellData(
            ocrCell.getText() != null ? ocrCell.getText() : "",
            ocrCell.getIsHeader() != null && ocrCell.getIsHeader()
        );
        rowData.put(colIndex, cellData);
      }
      tableData.put(rowIndex, rowData);
    }

    log.debug("Table dimensions: {} rows x {} columns", maxRow, maxCol);

    // Find header row - prefer rows with COLUMN_HEADER entity types, otherwise use keyword matching
    int headerRow = findHeaderRow(tableData, maxRow, maxCol);
    if (headerRow == -1) {
      log.warn("Could not find header row in table, skipping");
      return items;
    }

    // Create column mapping from header
    Map<Integer, String> headerRowData = new HashMap<>();
    for (Map.Entry<Integer, CellData> entry : tableData.get(headerRow).entrySet()) {
      headerRowData.put(entry.getKey(), entry.getValue().text);
    }
    
    ColumnMapping columnMapping = createColumnMapping(headerRowData, maxCol);
    
    // Skip if no meaningful columns found
    if (columnMapping.isEmpty()) {
      log.debug("No inventory-related columns found in table header, skipping");
      return items;
    }
    
    // Parse data rows - skip any rows that are marked as headers
    for (int row = headerRow + 1; row <= maxRow; row++) {
      Map<Integer, CellData> rowCells = tableData.get(row);
      if (rowCells == null || rowCells.isEmpty()) {
        continue;
      }

      // Skip rows that are marked as COLUMN_HEADER
      boolean isHeaderRow = rowCells.values().stream()
          .anyMatch(cell -> cell.isHeader);
      if (isHeaderRow) {
        log.debug("Skipping row {} - marked as COLUMN_HEADER", row);
        continue;
      }

      // Convert CellData map to String map for parsing
      Map<Integer, String> rowData = new HashMap<>();
      for (Map.Entry<Integer, CellData> entry : rowCells.entrySet()) {
        rowData.put(entry.getKey(), entry.getValue().text);
      }

      log.debug("Parsing row {} with data: {}", row, rowData);

      ParsedInventoryItem item = parseTableRow(rowData, columnMapping);
      if (item != null && isValidItem(item)) {
        items.add(item);
        log.info("Parsed inventory item from table row {}: {} - Qty: {}, MRP: {}, Company: {}", 
            row, item.getName(), item.getQuantity(), item.getMrp(), item.getCompanyName());
      } else {
        log.debug("Row {} did not produce a valid item", row);
      }
    }

    log.info("Parsed {} items from table", items.size());
    return items;
  }

  /**
   * Helper class to store cell data with header flag.
   */
  private static class CellData {
    String text;
    boolean isHeader;

    CellData(String text, boolean isHeader) {
      this.text = text != null ? text.trim() : "";
      this.isHeader = isHeader;
    }
  }


  /**
   * Find the header row in the table by looking for COLUMN_HEADER entity types or common header keywords.
   *
   * @param tableData the table data organized by row and column
   * @param maxRow maximum row index
   * @param maxCol maximum column index
   * @return header row index, or -1 if not found
   */
  private int findHeaderRow(Map<Integer, Map<Integer, CellData>> tableData, int maxRow, int maxCol) {
    String[] headerKeywords = {"CGST", "SGST", "HSN", "MRP", "RATE", "BATCH", "BATCH NO", 
                                "PRODUCT", "DESCRIPTION", "CODE", "SL NO", "SLNO", "QTY", "QUANTITY",
                                "EXPIRY", "EXP", "MFD", "MANUFACTURE", "PACK", "PACKAGE", "MFG", "MKD"};

    // First, try to find row with COLUMN_HEADER entity types (most reliable)
    for (int row = 0; row <= Math.min(3, maxRow); row++) {
      Map<Integer, CellData> rowData = tableData.get(row);
      if (rowData == null || rowData.isEmpty()) {
        continue;
      }

      // Count header cells in this row
      long headerCellCount = rowData.values().stream()
          .filter(cell -> cell.isHeader)
          .count();

      // If more than half the cells are headers, this is likely the header row
      if (headerCellCount > 0 && headerCellCount >= rowData.size() / 2) {
        log.info("Found header row at index {} using COLUMN_HEADER entity types ({} header cells)", 
            row, headerCellCount);
        return row;
      }
    }

    // Fallback: Check first few rows for header keywords
    for (int row = 0; row <= Math.min(3, maxRow); row++) {
      Map<Integer, CellData> rowData = tableData.get(row);
      if (rowData == null || rowData.isEmpty()) {
        continue;
      }

      String rowText = String.join(" ", 
          rowData.values().stream()
              .map(cell -> cell.text)
              .collect(Collectors.toList()))
          .toUpperCase();
      
      int keywordMatches = 0;
      for (String keyword : headerKeywords) {
        if (rowText.contains(keyword)) {
          keywordMatches++;
        }
      }

      // If we find multiple header keywords, this is likely the header row
      if (keywordMatches >= 2) {
        log.info("Found header row at index {} using keyword matching ({} matches)", row, keywordMatches);
        return row;
      }
    }

    // Last resort: default to row 0 or 1 (depending on which has more non-empty cells)
    if (maxRow >= 0) {
      int row0Cells = tableData.getOrDefault(0, new HashMap<>()).size();
      int row1Cells = maxRow >= 1 ? tableData.getOrDefault(1, new HashMap<>()).size() : 0;
      
      int defaultRow = (row1Cells > row0Cells) ? 1 : 0;
      log.warn("Could not definitively identify header row, defaulting to row {}", defaultRow);
      return defaultRow;
    }

    return -1;
  }

  /**
   * Create column mapping from header row data.
   * Handles various column name variations and formats.
   * Note: Textract uses 1-based column indexing.
   *
   * @param headerRow the header row data (column index -> header text)
   * @param maxCol maximum column index
   * @return ColumnMapping with column positions
   */
  private ColumnMapping createColumnMapping(Map<Integer, String> headerRow, int maxCol) {
    ColumnMapping mapping = new ColumnMapping();

    // Textract uses 1-based indexing, so iterate from 1 to maxCol
    for (int col = 1; col <= maxCol; col++) {
      String headerText = headerRow.getOrDefault(col, "").toUpperCase().trim();
      // Remove leading colons and special characters
      headerText = headerText.replaceAll("^[:\\s]+", "").replaceAll("[\\s:]+$", "");
      // Remove periods for comparison (e.g., "M.R.P." -> "MRP", "MFG/MKD." -> "MFG/MKD")
      String headerTextNormalized = headerText.replaceAll("\\.", "");
      
      if (headerText.isEmpty()) {
        continue;
      }
      
      log.debug("Checking column {} with header: '{}' (normalized: '{}')", col, headerText, headerTextNormalized);

      // Match field names (case-insensitive, handle variations and abbreviations)
      // Use normalized text (without periods) for matching
      // SL NO / Serial Number
      if ((headerTextNormalized.contains("SL") && (headerTextNormalized.contains("NO") || headerTextNormalized.contains("NUM"))) ||
          headerTextNormalized.equals("SR") || headerTextNormalized.equals("SRNO") || headerTextNormalized.equals("SNO")) {
        mapping.slNo = col;
      }
      // CODE (but not HSN code)
      else if (headerTextNormalized.contains("CODE") && !headerTextNormalized.contains("HSN") && !headerTextNormalized.contains("SAC")) {
        mapping.code = col;
      }
      // HSN/SAC
      else if (headerTextNormalized.contains("HSN") || headerTextNormalized.contains("SAC") || 
               headerTextNormalized.equals("HSN/SAC") || headerTextNormalized.contains("HSN/SAC")) {
        mapping.hsn = col;
      }
      // PRODUCT / DESCRIPTION / NAME / ITEM
      else if (headerTextNormalized.contains("PRODUCT") || headerTextNormalized.contains("DESC") || 
               headerTextNormalized.contains("NAME") || headerTextNormalized.contains("ITEM") ||
               headerTextNormalized.equals("PRODUCTS")) {
        // Only set if not already set, or if this is more specific
        if (mapping.description == null || headerTextNormalized.contains("PRODUCT")) {
          mapping.description = col;
        }
      }
      // BATCH / BATCH NO
      else if (headerTextNormalized.contains("BATCH")) {
        mapping.batchNo = col;
      }
      // MFD / MANUFACTURE / MFG / MKD - handle "MFG/MKD." with period
      else if (headerTextNormalized.contains("MFD") || headerTextNormalized.contains("MANUFACTURE") ||
               headerTextNormalized.contains("MFG") || headerTextNormalized.contains("MKD") ||
               headerTextNormalized.equals("MFG/MKD") || headerTextNormalized.contains("MFG/MKD")) {
        mapping.mfd = col;
        log.debug("Found MFG/MKD column at index {} with header: {}", col, headerText);
      }
      // EXPIRY / EXP / EXP DATE / Exp.Dt
      else if (headerTextNormalized.contains("EXPIRY") || headerTextNormalized.contains("EXP DATE") ||
               (headerTextNormalized.contains("EXP") && !headerTextNormalized.contains("TAX")) ||
               headerTextNormalized.equals("EXP") || headerTextNormalized.contains("EXPDT") ||
               headerTextNormalized.contains("EXP DT")) {
        mapping.expiry = col;
      }
      // QTY / QUANTITY
      else if (headerTextNormalized.contains("QTY") || headerTextNormalized.equals("QUANTITY")) {
        mapping.qty = col;
      }
      // RATE (but not MRP)
      else if (headerTextNormalized.contains("RATE") && !headerTextNormalized.contains("MRP") &&
               !headerTextNormalized.contains("GST")) {
        mapping.rate = col;
      }
      // MRP / M.R.P. - handle both formats
      else if (headerTextNormalized.contains("MRP") && !headerTextNormalized.contains("REDUCED") &&
               !headerTextNormalized.contains("RED")) {
        mapping.mrp = col;
        log.debug("Found MRP column at index {} with header: {}", col, headerText);
      }
      // REDUCED MRP
      else if (headerTextNormalized.contains("REDUCED") || 
               (headerTextNormalized.contains("MRP") && col > 0 && 
                headerRow.getOrDefault(col - 1, "").toUpperCase().replaceAll("\\.", "").contains("REDUCED")) ||
               headerTextNormalized.contains("RED MRP")) {
        mapping.reducedMrp = col;
      }
      // PACK / PACKAGE / PKG
      else if (headerTextNormalized.contains("PACK") || headerTextNormalized.contains("PKG")) {
        mapping.pkgDetail = col;
      }
    }

    log.info("Column mapping - SL_NO: {}, CODE: {}, HSN: {}, DESC: {}, BATCH: {}, MFD/MFG: {}, EXPIRY: {}, QTY: {}, RATE: {}, MRP: {}, REDUCED_MRP: {}, PKG: {}",
        mapping.slNo, mapping.code, mapping.hsn, mapping.description,
        mapping.batchNo, mapping.mfd, mapping.expiry, mapping.qty, 
        mapping.rate, mapping.mrp, mapping.reducedMrp, mapping.pkgDetail);

    return mapping;
  }

  /**
   * Parse a single table row into a ParsedInventoryItem.
   * Handles various data formats and edge cases.
   *
   * @param rowData the row data as a map of column index to cell text
   * @param mapping the column mapping from header
   * @return parsed inventory item
   */
  private ParsedInventoryItem parseTableRow(Map<Integer, String> rowData, ColumnMapping mapping) {
    try {
      ParsedInventoryItem item = new ParsedInventoryItem();

      // Extract CODE
      if (mapping.code != null && rowData.containsKey(mapping.code)) {
        String codeValue = cleanCellText(rowData.get(mapping.code));
        if (!codeValue.isEmpty()) {
          item.setCode(codeValue);
        }
      }

      // Extract HSN/SAC - handle cases where it might be in MFG/MKD column
      if (mapping.hsn != null && rowData.containsKey(mapping.hsn)) {
        String hsnValue = cleanCellText(rowData.get(mapping.hsn));
        if (!hsnValue.isEmpty() && isNumeric(hsnValue.replaceAll("[^0-9]", ""))) {
          item.setHsn(hsnValue.replaceAll("[^0-9]", ""));
        }
      }
      
      // Fallback: check MFG/MKD column for HSN if HSN column is empty
      // This handles cases where HSN code appears at the start of MFG/MKD column
      if ((item.getHsn() == null || item.getHsn().isEmpty()) && 
          mapping.mfd != null && rowData.containsKey(mapping.mfd)) {
        String mfdValue = cleanCellText(rowData.get(mapping.mfd));
        // Extract HSN code if present at the start (7-9 digits)
        java.util.regex.Pattern hsnPattern = java.util.regex.Pattern.compile("^(\\d{7,9})");
        java.util.regex.Matcher hsnMatcher = hsnPattern.matcher(mfdValue);
        if (hsnMatcher.find()) {
          item.setHsn(hsnMatcher.group(1));
        }
      }

      // Extract PRODUCT/DESCRIPTION
      if (mapping.description != null && rowData.containsKey(mapping.description)) {
        String descValue = cleanCellText(rowData.get(mapping.description));
        if (!descValue.isEmpty()) {
          item.setName(descValue);
        }
      }

      // Extract BATCH NO
      if (mapping.batchNo != null && rowData.containsKey(mapping.batchNo)) {
        String batchValue = cleanCellText(rowData.get(mapping.batchNo));
        if (!batchValue.isEmpty()) {
          item.setBatchNo(batchValue);
        }
      }

      // Extract MFD/MFG/MKD column - can contain company name, date, or both
      if (mapping.mfd != null && rowData.containsKey(mapping.mfd)) {
        String rawMfdValue = rowData.get(mapping.mfd);
        String mfdValue = cleanCellText(rawMfdValue);
        log.debug("Processing MFG/MKD column value: '{}' (cleaned: '{}')", rawMfdValue, mfdValue);
        
        // Extract company name from MFG/MKD column
        String companyName = extractCompanyName(mfdValue);
        if (companyName != null && !companyName.isEmpty()) {
          item.setCompanyName(companyName);
          log.debug("Extracted company name: {}", companyName);
        } else {
          log.debug("No company name extracted from: {}", mfdValue);
        }
        
        // Extract manufacture date if present (skip if it looks like an HSN code)
        String numericOnly = mfdValue.replaceAll("[^0-9]", "");
        if (numericOnly.length() < 7) {  // Not an HSN code
          String dateStr = parseDate(mfdValue);
          if (dateStr != null) {
            item.setManufactureDate(dateStr);
            log.debug("Extracted manufacture date: {}", dateStr);
          }
        } else {
          log.debug("Skipping date extraction - value looks like HSN code: {}", mfdValue);
        }
      } else {
        log.debug("MFG/MKD column not found. Mapping MFD: {}, Row keys: {}", 
            mapping.mfd, rowData.keySet());
      }

      // Extract EXPIRY DATE - handle MM/YY and MON-YY formats
      if (mapping.expiry != null && rowData.containsKey(mapping.expiry)) {
        String expiryValue = cleanCellText(rowData.get(mapping.expiry));
        String dateStr = parseDate(expiryValue);
        if (dateStr != null) {
          item.setExpiryDate(dateStr);
        }
      }

      // Extract QTY - handle leading colons and special characters
      if (mapping.qty != null && rowData.containsKey(mapping.qty)) {
        try {
          String qtyValue = cleanCellText(rowData.get(mapping.qty))
              .replace(",", "")
              .replaceAll("[^0-9]", "");
          if (!qtyValue.isEmpty()) {
            item.setQuantity(Integer.parseInt(qtyValue));
          }
        } catch (NumberFormatException e) {
          log.debug("Could not parse quantity: {}", rowData.get(mapping.qty));
        }
      }

      // Extract RATE
      if (mapping.rate != null && rowData.containsKey(mapping.rate)) {
        try {
          String rateValue = cleanCellText(rowData.get(mapping.rate))
              .replace(",", "")
              .replaceAll("[^0-9.]", "");
          if (!rateValue.isEmpty()) {
            item.setRate(new BigDecimal(rateValue));
          }
        } catch (NumberFormatException e) {
          log.debug("Could not parse rate: {}", rowData.get(mapping.rate));
        }
      }

      // Extract MRP
      if (mapping.mrp != null && rowData.containsKey(mapping.mrp)) {
        try {
          String rawMrpValue = rowData.get(mapping.mrp);
          log.debug("Raw MRP value from column {}: '{}'", mapping.mrp, rawMrpValue);
          
          String mrpValue = cleanCellText(rawMrpValue)
              .replace(",", "")
              .replaceAll("[^0-9.]", "");
          
          log.debug("Cleaned MRP value: '{}'", mrpValue);
          
          if (!mrpValue.isEmpty()) {
            BigDecimal mrp = new BigDecimal(mrpValue);
            // Handle missing decimal point (15000 -> 150.00) - but be careful
            // Only divide if value is very large (likely missing decimal) and has no decimal point
            if (mrp.compareTo(BigDecimal.valueOf(1000)) > 0 && !mrpValue.contains(".")) {
              BigDecimal adjustedMrp = mrp.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
              log.debug("Adjusted MRP from {} to {} (divided by 100)", mrp, adjustedMrp);
              item.setMrp(adjustedMrp);
            } else {
              item.setMrp(mrp);
              log.debug("Set MRP to: {}", mrp);
            }
          } else {
            log.debug("MRP value is empty after cleaning");
          }
        } catch (NumberFormatException e) {
          log.warn("Could not parse MRP from '{}': {}", rowData.get(mapping.mrp), e.getMessage());
        }
      } else {
        log.debug("MRP column not found or not in row data. Mapping MRP: {}, Row keys: {}", 
            mapping.mrp, rowData.keySet());
      }

      // Extract REDUCED MRP
      if (mapping.reducedMrp != null && rowData.containsKey(mapping.reducedMrp)) {
        try {
          String reducedMrpValue = cleanCellText(rowData.get(mapping.reducedMrp))
              .replace(",", "")
              .replaceAll("[^0-9.]", "");
          if (!reducedMrpValue.isEmpty()) {
            BigDecimal reducedMrp = new BigDecimal(reducedMrpValue);
            if (reducedMrp.compareTo(BigDecimal.valueOf(1000)) > 0 && !reducedMrpValue.contains(".")) {
              item.setReducedMrp(reducedMrp.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
            } else {
              item.setReducedMrp(reducedMrp);
            }
          }
        } catch (NumberFormatException e) {
          log.debug("Could not parse Reduced MRP: {}", rowData.get(mapping.reducedMrp));
        }
      }

      // Extract PACKAGE DETAIL - handle formats like "1X16", "1x16", ":1X60"
      if (mapping.pkgDetail != null && rowData.containsKey(mapping.pkgDetail)) {
        String pkgValue = cleanCellText(rowData.get(mapping.pkgDetail));
        // Normalize to uppercase and remove leading colon
        pkgValue = pkgValue.toUpperCase().replaceAll("^[:\\s]+", "");
        if (pkgValue.matches("\\d+[xX]\\d+")) {
          item.setPackageDetail(pkgValue);
        }
      }

      // Log extracted item details for debugging
      log.debug("Extracted item - Name: {}, Qty: {}, HSN: {}, MRP: {}, Company: {}, Expiry: {}", 
          item.getName(), item.getQuantity(), item.getHsn(), item.getMrp(), 
          item.getCompanyName(), item.getExpiryDate());

      return item;

    } catch (Exception e) {
      log.warn("Error parsing table row: {}", e.getMessage(), e);
    }

    return null;
  }

  /**
   * Clean cell text by removing leading/trailing colons, whitespace, and special characters.
   */
  private String cleanCellText(String text) {
    if (text == null) {
      return "";
    }
    return text.trim()
        .replaceAll("^[:\\s]+", "")  // Remove leading colons and spaces
        .replaceAll("[\\s:;]+$", "") // Remove trailing spaces, colons, semicolons
        .replaceAll("\\s+", " ");    // Normalize multiple spaces
  }

  /**
   * Extract company name from MFG/MKD column.
   * The column can contain: company name only, company name + date, or HSN + company name + date.
   * Examples: "CHARAK PI", "CHARAK PI 8/28", "30049011 : CHARAK PI 8/28", "CHARAK P: 8/28"
   *
   * @param mfdValue the value from MFG/MKD column
   * @return extracted company name, or null if not found
   */
  private String extractCompanyName(String mfdValue) {
    if (mfdValue == null || mfdValue.trim().isEmpty()) {
      return null;
    }
    
    String value = mfdValue.trim();
    log.debug("Extracting company name from: {}", value);
    
    // Remove HSN codes (7-9 digit numbers) at the start, with optional colon and spaces
    value = value.replaceAll("^\\d{7,9}\\s*[:\\-]?\\s*", "");
    
    // Remove dates (MM/YY, MON-YY formats) - be more aggressive
    value = value.replaceAll("\\d{1,2}/\\d{2}", "");  // Remove MM/YY
    value = value.replaceAll("[A-Z]{3}[-.]?\\d{2}", "");  // Remove MON-YY
    
    // Remove trailing colons and dates that might be at the end
    value = value.replaceAll("[:\\s]+\\d{1,2}/\\d{2}\\s*$", "");  // Remove ": 8/28" at end
    value = value.replaceAll("[:\\s]+[A-Z]{3}[-.]?\\d{2}\\s*$", "");  // Remove ": NOV-24" at end
    
    // Remove leading/trailing colons, dashes, and extra whitespace
    value = value.replaceAll("^[:\\-\\s]+", "").replaceAll("[:\\-\\s]+$", "");
    value = value.replaceAll("\\s+", " ").trim();
    
    log.debug("After cleaning, company name candidate: '{}'", value);
    
    // If what remains looks like a company name (has letters, not just numbers/symbols)
    if (!value.isEmpty() && value.matches(".*[A-Za-z].*") && 
        !value.matches("^\\d+$") && value.length() >= 2) {
      log.debug("Extracted company name: {}", value);
      return value;
    }
    
    log.debug("No valid company name found in: {}", mfdValue);
    return null;
  }

  /**
   * Parse date string in various formats (MM/YY, MON-YY, etc.) and return normalized format.
   */
  private String parseDate(String dateStr) {
    if (dateStr == null || dateStr.trim().isEmpty()) {
      return null;
    }
    
    dateStr = dateStr.trim().toUpperCase();
    
    // Handle MM/YY format (e.g., "8/28", "08/28")
    if (dateStr.matches(".*\\d{1,2}/\\d{2}.*")) {
      // Extract the date part
      java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d{1,2})/(\\d{2})");
      java.util.regex.Matcher matcher = pattern.matcher(dateStr);
      if (matcher.find()) {
        int month = Integer.parseInt(matcher.group(1));
        String year = matcher.group(2);
        if (month >= 1 && month <= 12) {
          String[] monthNames = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                                 "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
          return monthNames[month - 1] + "-" + year;
        }
      }
    }
    
    // Handle MON-YY or MON.YY format (e.g., "NOV-24", "OCT.27")
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([A-Z]{3})[-.]?(\\d{2})");
    java.util.regex.Matcher matcher = pattern.matcher(dateStr);
    if (matcher.find()) {
      return matcher.group(1) + "-" + matcher.group(2);
    }
    
    return null;
  }

  /**
   * Validate if an item has minimum required fields.
   */
  private boolean isValidItem(ParsedInventoryItem item) {
    // Must have at least a name or code, and ideally a quantity
    boolean hasName = item.getName() != null && !item.getName().trim().isEmpty();
    boolean hasCode = item.getCode() != null && !item.getCode().trim().isEmpty();
    boolean hasQty = item.getQuantity() != null && item.getQuantity() > 0;
    
    // Valid if has name or code, and preferably has quantity
    return (hasName || hasCode) && (hasQty || hasName);
  }

  /**
   * Column mapping to store positions of each field in the header.
   */
  private static class ColumnMapping {
    Integer slNo = null;
    Integer code = null;
    Integer hsn = null;
    Integer description = null;
    Integer batchNo = null;
    Integer mfd = null;
    Integer expiry = null;
    Integer qty = null;
    Integer rate = null;
    Integer mrp = null;
    Integer reducedMrp = null;
    Integer pkgDetail = null;

    /**
     * Check if mapping has any meaningful columns.
     */
    boolean isEmpty() {
      return description == null && code == null && qty == null;
    }
  }

  /**
   * Helper method to check if a string is numeric.
   */
  private boolean isNumeric(String str) {
    if (str == null || str.isEmpty()) {
      return false;
    }
    return str.matches("\\d+");
  }

  /**
   * Helper method to check if a string is a decimal number.
   */
  private boolean isDecimal(String str) {
    if (str == null || str.isEmpty()) {
      return false;
    }
    return str.replace(",", "").matches("\\d+\\.?\\d*");
  }
}


