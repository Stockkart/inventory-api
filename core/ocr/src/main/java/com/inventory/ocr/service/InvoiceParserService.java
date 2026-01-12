package com.inventory.ocr.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for parsing invoice text extracted from OCR and extracting inventory items.
 */
@Service
@Slf4j
public class InvoiceParserService {

  @Autowired
  private OcrService ocrService;

  /**
   * Extract inventory items from an invoice image.
   *
   * @param imageBytes the image file as byte array
   * @return list of parsed inventory items
   */
  public List<ParsedInventoryItem> parseInvoiceImage(byte[] imageBytes) {
    log.info("Parsing invoice image to extract inventory items");
    
    try {
      // Extract text using OCR
      String ocrText = ocrService.extractText(imageBytes);
      log.info("OCR text extracted, length: {}", ocrText.length());
      
      // Parse the text to extract inventory items
      return parseInvoiceText(ocrText);
    } catch (Exception e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to parse invoice image: " + e.getMessage(), e);
    }
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
  }

  /**
   * Parse invoice text and extract inventory items.
   * Strategy: Find header line, map column positions, then parse data rows based on those positions.
   * 
   * @param text the OCR extracted text
   * @return list of parsed inventory items
   */
  private List<ParsedInventoryItem> parseInvoiceText(String text) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    
    // Split text into lines for processing
    String[] lines = text.split("\n");
    
    // Keywords that indicate a header row
    String[] headerKeywords = {"CGST", "SGST", "HSN", "MRP", "RATE", "BATCH", "BATCH NO", 
                                "PRODUCT", "DESCRIPTION", "CODE", "SL NO", "QTY", "QUANTITY"};
    
    int headerLineIndex = -1;
    
    // Find the header line containing keywords
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].toUpperCase().trim();
      boolean hasKeywords = false;
      
      for (String keyword : headerKeywords) {
        if (line.contains(keyword)) {
          hasKeywords = true;
          break;
        }
      }
      
      if (hasKeywords) {
        headerLineIndex = i;
        log.info("Found header line at index {}: {}", i, lines[i]);
        break;
      }
    }
    
    if (headerLineIndex == -1) {
      log.warn("Could not find header line with keywords");
      return items;
    }
    
    // Parse header to create column mapping
    ColumnMapping columnMapping = parseHeaderLine(lines[headerLineIndex]);
    log.info("Column mapping - SL_NO: {}, CODE: {}, HSN: {}, DESC: {}, BATCH: {}, QTY: {}, RATE: {}, MRP: {}, REDUCED_MRP: {}",
        columnMapping.slNo, columnMapping.code, columnMapping.hsn, columnMapping.description,
        columnMapping.batchNo, columnMapping.qty, columnMapping.rate, columnMapping.mrp, columnMapping.reducedMrp);
    
    // Parse lines after the header that look like inventory items
    for (int i = headerLineIndex + 1; i < lines.length; i++) {
      String line = lines[i].trim();

      // Skip empty lines
      if (line.isEmpty()) {
        continue;
      }
      
      // Stop if we hit summary/total lines
      if (isSummaryLine(line)) {
        log.info("Reached summary line, stopping parsing: {}", line);
        break;
      }
      
      // Check if this line looks like an inventory item
      if (isInventoryItemLine(line)) {
        ParsedInventoryItem item = parseItemLineWithMapping(line, columnMapping);
        if (item != null && item.getName() != null && !item.getName().isEmpty()) {
          items.add(item);
          log.info("Parsed inventory item: {} - Qty: {}", item.getName(), item.getQuantity());
        }
      }
    }
    
    log.info("Total inventory items parsed: {}", items.size());
    return items;
  }
  
  /**
   * Parse header line to determine column positions for each field.
   * 
   * @param headerLine the header line text
   * @return ColumnMapping with positions for each field
   */
  private ColumnMapping parseHeaderLine(String headerLine) {
    ColumnMapping mapping = new ColumnMapping();
    
    // Split header by whitespace
    String[] headerParts = headerLine.split("\\s+");
    
    for (int i = 0; i < headerParts.length; i++) {
      String part = headerParts[i].toUpperCase().trim();
      
      // Match field names (case-insensitive, handle variations)
      if (part.contains("SL") && (part.contains("NO") || part.contains("NUM"))) {
        mapping.slNo = i;
      } else if (part.contains("CODE") && !part.contains("HSN")) {
        mapping.code = i;
      } else if (part.contains("HSN")) {
        mapping.hsn = i;
      } else if (part.contains("DESC") || part.contains("PRODUCT") || part.contains("NAME")) {
        mapping.description = i;
      } else if (part.contains("BATCH")) {
        mapping.batchNo = i;
      } else if (part.contains("MFD") || part.contains("MANUFACTURE")) {
        mapping.mfd = i;
      } else if (part.contains("EXPIRY") || part.contains("EXP") || part.contains("EXP DATE")) {
        mapping.expiry = i;
      } else if (part.contains("QTY") || part.contains("QUANTITY")) {
        mapping.qty = i;
      } else if (part.contains("RATE") && !part.contains("MRP")) {
        mapping.rate = i;
      } else if (part.contains("MRP") && !part.contains("REDUCED")) {
        mapping.mrp = i;
      } else if (part.contains("REDUCED") || (part.contains("MRP") && i > 0 && headerParts[i-1].toUpperCase().contains("REDUCED"))) {
        mapping.reducedMrp = i;
      } else if (part.contains("PKG") || part.contains("PACKAGE")) {
        mapping.pkgDetail = i;
      }
    }
    
    return mapping;
  }
  
  /**
   * Check if a line is a summary/total line (should stop parsing).
   */
  private boolean isSummaryLine(String line) {
    String upperLine = line.toUpperCase();
    return upperLine.contains("GRAND TOTAL") || 
           upperLine.contains("TOTAL") && (upperLine.contains("AMOUNT") || upperLine.contains("INVOICE")) ||
           upperLine.contains("GST@") ||
           upperLine.contains("ROUND OFF") ||
           upperLine.contains("E-WAY BILL") ||
           upperLine.contains("REMARKS");
  }
  
  /**
   * Check if a line looks like an inventory item row.
   * Criteria: Starts with a number and contains multiple numeric values.
   */
  private boolean isInventoryItemLine(String line) {
    if (line.isEmpty()) {
      return false;
    }
    
    // Should start with a number (SL NO)
    String[] parts = line.split("\\s+");
    if (parts.length < 3) {
      return false;
    }
    
    // First part should be a number (SL NO)
    if (!isNumeric(parts[0])) {
      return false;
    }
    
    // Should have multiple numeric values (at least code, HSN, batch, qty, prices)
    int numericCount = 0;
    for (String part : parts) {
      if (isNumeric(part) || isDecimal(part.replace(",", ""))) {
        numericCount++;
      }
    }
    
    // Should have at least 5 numeric values to be considered an item row
    return numericCount >= 5;
  }

  /**
   * Parse a single inventory item line using column mapping from header.
   * 
   * @param line the data line to parse
   * @param mapping the column mapping from header
   * @return parsed inventory item
   */
  private ParsedInventoryItem parseItemLineWithMapping(String line, ColumnMapping mapping) {
    try {
      // Split by whitespace (single or multiple spaces)
      String[] parts = line.split("\\s+");

      log.info("Line parts: {}", (Object) parts);

      if (parts.length < 3) {
        log.debug("Line too short to be an item: {}", line);
        return null;
      }
      
      ParsedInventoryItem item = new ParsedInventoryItem();
      
      // Extract values based on column positions from header
      if (mapping.slNo != null && mapping.slNo < parts.length) {
        // Skip SL NO, but validate it's a number
        if (!isNumeric(parts[mapping.slNo])) {
          log.debug("SL NO is not numeric, skipping line: {}", line);
          return null;
        }
      }

      // CODE
      if (mapping.code != null && mapping.code < parts.length) {
        item.setCode(parts[mapping.code]);
      }
      
      // HSN
      if (mapping.hsn != null && mapping.hsn < parts.length) {
        item.setHsn(parts[mapping.hsn]);
      }
      
      // DESCRIPTION - may span multiple columns, collect until next mapped field
      // Descriptions can span multiple columns, so we need to collect all words until
      // we hit something that's clearly not part of the description (batch number, date, etc.)
      if (mapping.description != null && mapping.description < parts.length) {
        int descStart = mapping.description;
        
        // Find the next mapped field positions as hints (not hard limits)
        // These help us know where to start being more careful
        Integer nextFieldHint = null;
        if (mapping.batchNo != null && mapping.batchNo > descStart) {
          nextFieldHint = mapping.batchNo;
        } else if (mapping.mfd != null && mapping.mfd > descStart) {
          nextFieldHint = mapping.mfd;
        } else if (mapping.qty != null && mapping.qty > descStart) {
          nextFieldHint = mapping.qty;
        }
        
        StringBuilder description = new StringBuilder();
        // Collect words from descStart, continue until we hit something clearly not description
        // Don't use maxSafeEnd as a hard limit - descriptions can span multiple columns
        for (int i = descStart; i < parts.length; i++) {
          String part = parts[i];
          
          // If we've passed the next field hint position, be more careful
          boolean pastHint = (nextFieldHint != null && i >= nextFieldHint);
          
          // Stop if we hit a long numeric value (likely batch number, 6+ digits)
          if (isNumeric(part) && part.length() >= 6) {
            log.debug("Stopping description at index {} due to long numeric (batch?): {}", i, part);
            break;
          }
          
          // Stop if we hit a date pattern (likely MFD or EXPIRY)
          if (part.matches("[A-Z]{3}[-.]?\\d{2}")) {
            log.debug("Stopping description at index {} due to date pattern: {}", i, part);
            break;
          }
          
          // If we're past the hint position, check more carefully
          if (pastHint) {
            // At or past the mapped field position - check if this looks like that field
            if (mapping.batchNo != null && i == mapping.batchNo) {
              // This is the batch position - if it's numeric and long, it's batch, not description
              if (isNumeric(part) && part.length() >= 6) {
                log.debug("Stopping description at batch position {}: {}", i, part);
                break;
              }
            }
            if (mapping.mfd != null && i == mapping.mfd) {
              // This is the MFD position - if it's a date, it's MFD, not description
              if (part.matches("[A-Z]{3}[-.]?\\d{2}")) {
                log.debug("Stopping description at MFD position {}: {}", i, part);
                break;
              }
            }
            
            // If we're well past the hint and hit a numeric/decimal that looks like a field value
            if (i > nextFieldHint + 1) {
              // We're well past where the next field should be
              // If we hit a clear numeric/date, stop
              if ((isNumeric(part) && part.length() >= 4) || 
                  part.matches("[A-Z]{3}[-.]?\\d{2}") ||
                  (isDecimal(part.replace(",", "")) && part.length() > 5)) {
                log.debug("Stopping description well past hint at index {}: {}", i, part);
                break;
              }
            }
          }
          
          // Stop if we hit a decimal number that looks like a price (but allow short numbers that might be part of description like "100GM")
          if (isDecimal(part.replace(",", "")) && part.length() > 6 && part.contains(".")) {
            // This is likely a price, not part of description
            log.debug("Stopping description at index {} due to price-like value: {}", i, part);
            break;
          }
          
          // Add this word to description
          if (description.length() > 0) {
            description.append(" ");
          }
          description.append(part);
        }
        
        String descText = description.toString().trim();
        item.setName(descText);
        log.debug("Extracted description from index {}: '{}'", descStart, descText);
      }
      
      // BATCH NO
      if (mapping.batchNo != null && mapping.batchNo < parts.length) {
        item.setBatchNo(parts[mapping.batchNo]);
      }
      
      // MFD (Manufacture Date)
      if (mapping.mfd != null && mapping.mfd < parts.length) {
        String mfdValue = parts[mapping.mfd];
        if (mfdValue.matches("[A-Z]{3}[-.]?\\d{2}")) {
          item.setManufactureDate(mfdValue.replace(".", "-"));
        }
      }
      
      // EXPIRY (Expiry Date)
      if (mapping.expiry != null && mapping.expiry < parts.length) {
        String expiryValue = parts[mapping.expiry];
        // Handle cases where expiry might be "—" or "-" followed by date
        if (expiryValue.equals("—") || expiryValue.equals("-")) {
          if (mapping.expiry + 1 < parts.length) {
            expiryValue = parts[mapping.expiry + 1];
          }
        }
        if (expiryValue.matches("[A-Z]{3}[-.]?\\d{2}")) {
          item.setExpiryDate(expiryValue.replace(".", "-"));
        }
      }
      
      // QTY (Quantity)
      if (mapping.qty != null && mapping.qty < parts.length) {
        try {
          String qtyValue = parts[mapping.qty].replace(",", "");
          item.setQuantity(Integer.parseInt(qtyValue));
        } catch (NumberFormatException e) {
          log.debug("Could not parse quantity: {}", parts[mapping.qty]);
        }
      }
      
      // RATE
      if (mapping.rate != null && mapping.rate < parts.length) {
        try {
          String rateValue = parts[mapping.rate].replace(",", "");
          item.setRate(new BigDecimal(rateValue));
        } catch (NumberFormatException e) {
          log.debug("Could not parse rate: {}", parts[mapping.rate]);
        }
      }
      
      // MRP
      if (mapping.mrp != null && mapping.mrp < parts.length) {
        try {
          String mrpValue = parts[mapping.mrp].replace(",", "");
          BigDecimal mrp = new BigDecimal(mrpValue);
          // Handle missing decimal point (15000 -> 150.00)
          if (mrp.compareTo(BigDecimal.valueOf(1000)) > 0) {
            item.setMrp(mrp.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
          } else {
            item.setMrp(mrp);
          }
        } catch (NumberFormatException e) {
          log.debug("Could not parse MRP: {}", parts[mapping.mrp]);
        }
      }
      
      // REDUCED MRP
      if (mapping.reducedMrp != null && mapping.reducedMrp < parts.length) {
        try {
          String reducedMrpValue = parts[mapping.reducedMrp].replace(",", "");
          BigDecimal reducedMrp = new BigDecimal(reducedMrpValue);
          // Handle missing decimal point
          if (reducedMrp.compareTo(BigDecimal.valueOf(1000)) > 0) {
            item.setReducedMrp(reducedMrp.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
          } else {
            item.setReducedMrp(reducedMrp);
          }
        } catch (NumberFormatException e) {
          log.debug("Could not parse Reduced MRP: {}", parts[mapping.reducedMrp]);
        }
      }
      
      // PKG DETAIL
      if (mapping.pkgDetail != null && mapping.pkgDetail < parts.length) {
        String pkgValue = parts[mapping.pkgDetail];
        if (pkgValue.matches("\\d+[xX]\\d+")) {
          item.setPackageDetail(pkgValue);
        }
      } else {
        // Try to find package detail at the end of the line
        for (int i = parts.length - 1; i >= 0; i--) {
          if (parts[i].matches("\\d+[xX]\\d+")) {
            item.setPackageDetail(parts[i]);
            break;
          }
        }
      }
      
      // Validate minimum required fields
      if (item.getName() != null && !item.getName().isEmpty() && 
          (item.getQuantity() != null || item.getCode() != null)) {
        return item;
      }
      
      log.debug("Item validation failed - name: {}, qty: {}, code: {}", 
          item.getName(), item.getQuantity(), item.getCode());
      
    } catch (Exception e) {
      log.debug("Error parsing item line '{}': {}", line, e.getMessage());
    }
    
    return null;
  }

  /**
   * Parse a single inventory item line (fallback method without mapping).
   * This is kept as a backup but should not be used if header mapping is available.
   */
  private ParsedInventoryItem parseItemLine(String line) {
    try {
      // Split by whitespace (single or multiple spaces)
      String[] parts = line.split("\\s+");
      
      if (parts.length < 8) {
        log.debug("Line too short to be an item: {}", line);
        return null;
      }
      
      ParsedInventoryItem item = new ParsedInventoryItem();
      int index = 0;
      
      // Skip SL NO (first part)
      if (isNumeric(parts[index])) {
        index++;
      }
      
      // CODE (7-digit number typically)
      if (index < parts.length && isNumeric(parts[index]) && parts[index].length() >= 6) {
        item.setCode(parts[index]);
        index++;
      }
      
      // HSN CODE (8-digit number)
      if (index < parts.length && isNumeric(parts[index]) && parts[index].length() >= 7) {
        item.setHsn(parts[index]);
        index++;
      }
      
      // DESCRIPTION - collect all non-numeric, non-date words until we hit batch number
      StringBuilder description = new StringBuilder();
      while (index < parts.length) {
        String part = parts[index];
        
        // Stop if we hit a long numeric value (likely batch number)
        if (isNumeric(part) && part.length() >= 6) {
          break;
        }
        
        // Stop if we hit a date pattern
        if (part.matches("[A-Z]{3}[-.]?\\d{2}")) {
          break;
        }
        
        // Stop if we hit common invoice keywords
        if (part.equalsIgnoreCase("FOR") && index > 0 && description.length() > 10) {
          // "FOR" might be part of description, but if description is already long, might be end
          description.append(" ").append(part);
          index++;
          continue;
        }
        
        if (description.length() > 0) {
          description.append(" ");
        }
        description.append(part);
        index++;
      }
      item.setName(description.toString().trim());
      
      // BATCH NO (long numeric, typically 9 digits)
      if (index < parts.length && isNumeric(parts[index]) && parts[index].length() >= 6) {
        item.setBatchNo(parts[index]);
        index++;
      }
      
      // MFD (Manufacture Date) - pattern like NOV-26, NOV.26, JUL-25
      if (index < parts.length && parts[index].matches("[A-Z]{3}[-.]?\\d{2}")) {
        item.setManufactureDate(parts[index].replace(".", "-"));
        index++;
      }
      
      // EXPIRY (Expiry Date) - pattern like OCT-27, OCT.27, JUN-28
      // Skip "—" or "-" if present
      while (index < parts.length && (parts[index].equals("—") || parts[index].equals("-"))) {
        index++;
      }
      if (index < parts.length && parts[index].matches("[A-Z]{3}[-.]?\\d{2}")) {
        item.setExpiryDate(parts[index].replace(".", "-"));
        index++;
      }
      
      // QTY (Quantity) - should be an integer
      if (index < parts.length && isNumeric(parts[index])) {
        try {
          item.setQuantity(Integer.parseInt(parts[index]));
          index++;
        } catch (NumberFormatException e) {
          log.debug("Could not parse quantity: {}", parts[index]);
        }
      }
      
      // RATE (per unit) - decimal number
      if (index < parts.length && isDecimal(parts[index].replace(",", ""))) {
        try {
          item.setRate(new BigDecimal(parts[index].replace(",", "")));
          index++;
        } catch (NumberFormatException e) {
          log.debug("Could not parse rate: {}", parts[index]);
        }
      }
      
      // Skip TAXABLE VALUE, GST values, CGST values, PTR (we don't need these for inventory)
      // After QTY and RATE, we expect: TAXABLE_VALUE, SGST%, SGST_VALUE, CGST%, CGST_VALUE, PTR, MRP, REDUCED_MRP, PKG
      // Count how many numeric values we've seen after expiry to find MRP and Reduced MRP
      int numericCountAfterExpiry = 0;
      BigDecimal mrpCandidate = null;
      BigDecimal reducedMrpCandidate = null;
      
      while (index < parts.length) {
        String part = parts[index].replace(",", "");
        
        if (isNumeric(part) || isDecimal(part)) {
          try {
            BigDecimal value = new BigDecimal(part);
            numericCountAfterExpiry++;
            
            // Based on typical invoice structure:
            // After QTY and RATE: TAXABLE(1), SGST%(2), SGST_VAL(3), CGST%(4), CGST_VAL(5), PTR(6), MRP(7), REDUCED_MRP(8)
            // So MRP is around position 7, Reduced MRP is around position 8
            // But we'll be more flexible and look for larger values (likely prices)
            
            if (value.compareTo(BigDecimal.valueOf(20)) > 0) {
              // This could be a price value
              if (mrpCandidate == null) {
                mrpCandidate = value;
              } else if (reducedMrpCandidate == null) {
                reducedMrpCandidate = value;
              }
            }
          } catch (NumberFormatException e) {
            // Continue
          }
        } else if (parts[index].matches("\\d+[xX]\\d+")) {
          // Found package detail, stop here
          break;
        }
        
        index++;
        
        // If we found both MRP candidates and package detail pattern, we're done
        if (mrpCandidate != null && reducedMrpCandidate != null && index < parts.length && 
            parts[index].matches("\\d+[xX]\\d+")) {
          break;
        }
      }
      
      // Set MRP and Reduced MRP (they might be in hundreds, so divide by 100 if needed)
      // OCR might read 150.00 as 15000, so check if values are too large
      if (mrpCandidate != null) {
        if (mrpCandidate.compareTo(BigDecimal.valueOf(1000)) > 0) {
          // Likely missing decimal point, divide by 100
          item.setMrp(mrpCandidate.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
        } else {
          item.setMrp(mrpCandidate);
        }
      }
      
      if (reducedMrpCandidate != null) {
        if (reducedMrpCandidate.compareTo(BigDecimal.valueOf(1000)) > 0) {
          // Likely missing decimal point, divide by 100
          item.setReducedMrp(reducedMrpCandidate.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
        } else {
          item.setReducedMrp(reducedMrpCandidate);
        }
      }
      
      // Find package detail
      if (index < parts.length && parts[index].matches("\\d+[xX]\\d+")) {
        item.setPackageDetail(parts[index]);
      } else {
        // Try to find it in remaining parts
        for (int i = index; i < parts.length; i++) {
          if (parts[i].matches("\\d+[xX]\\d+")) {
            item.setPackageDetail(parts[i]);
            break;
          }
        }
      }
      
      // PKG DETAIL (package detail) - usually at the end, pattern like "1x50", "1X40"
      if (index < parts.length && parts[index].matches("\\d+[xX]\\d+")) {
        item.setPackageDetail(parts[index]);
      }
      
      // Validate minimum required fields
      if (item.getName() != null && !item.getName().isEmpty() && 
          (item.getQuantity() != null || item.getCode() != null)) {
        return item;
      }
      
      log.debug("Item validation failed - name: {}, qty: {}, code: {}", 
          item.getName(), item.getQuantity(), item.getCode());
      
    } catch (Exception e) {
      log.debug("Error parsing item line '{}': {}", line, e.getMessage());
    }
    
    return null;
  }

  /**
   * Alternative parsing method for different invoice formats.
   */
  private List<ParsedInventoryItem> parseAlternativeFormat(String text) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    
    // Use regex patterns to find product information
    // Pattern for product codes (7-digit numbers)
    Pattern codePattern = Pattern.compile("\\b(\\d{7})\\b");
    // Pattern for HSN codes (8-digit numbers)
    Pattern hsnPattern = Pattern.compile("\\b(\\d{8})\\b");
    // Pattern for batch numbers (9-digit numbers)
    Pattern batchPattern = Pattern.compile("\\b(\\d{9})\\b");
    // Pattern for dates (MON-YY format)
    Pattern datePattern = Pattern.compile("\\b([A-Z]{3}-\\d{2})\\b");
    // Pattern for prices (decimal numbers)
    Pattern pricePattern = Pattern.compile("\\b(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})?)\\b");
    
    // This is a simplified alternative parser
    // In a real scenario, you'd need more sophisticated parsing based on actual OCR output
    
    log.warn("Alternative parsing not fully implemented - returning empty list");
    return items;
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


