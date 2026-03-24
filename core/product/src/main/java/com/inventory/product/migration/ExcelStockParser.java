package com.inventory.product.migration;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.stereotype.Component;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Excel stock snapshot files (.xls, .xlsx) from legacy apps into inventory items.
 * Uses auto-detection of column headers to support various export formats.
 * Adapted from OCR TableToItemsParser for Excel input.
 */
@Slf4j
@Component
public class ExcelStockParser {

  private static final int MAX_ROWS = 5000;
  private static final int HEADER_SEARCH_ROWS = 5;
  private static final Pattern MON_YY = Pattern.compile("([A-Z]{3})[-.]?(\\d{2,4})");
  private static final Pattern MM_YY = Pattern.compile("(\\d{1,2})/(\\d{2})");
  private static final Pattern HSN_PREFIX = Pattern.compile("^(\\d{7,9})");
  private static final String[] HEADER_KEYWORDS = {
      "CGST", "SGST", "HSN", "MRP", "RATE", "BATCH", "BATCH NO",
      "PRODUCT", "DESCRIPTION", "CODE", "SL NO", "SLNO", "QTY", "QUANTITY",
      "EXPIRY", "EXP", "MFD", "MANUFACTURE", "PACK", "PKG", "MFG", "MKD",
      "NAME", "ITEM", "SKU", "BARCODE", "STOCK", "QTY", "PRICE", "COST", "UNIT"
  };

  /**
   * Parse Excel file and extract inventory items.
   *
   * @param inputStream Excel file input stream (.xls or .xlsx)
   * @param filename    original filename for logging
   * @return list of parsed inventory items
   */
  public List<ParsedInventoryItem> parse(InputStream inputStream, String filename) throws IOException {
    List<ParsedInventoryItem> items = new ArrayList<>();
    try (Workbook workbook = createWorkbook(inputStream, filename)) {
      for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
        if (items.size() >= MAX_ROWS) break;
        Sheet sheet = workbook.getSheetAt(s);
        items.addAll(parseSheet(sheet, MAX_ROWS - items.size()));
      }
    }
    if (items.size() >= MAX_ROWS) {
      log.warn("Reached max rows limit ({}), stopping parse. Consider splitting the file.", MAX_ROWS);
    }
    log.info("Parsed {} items from Excel file: {}", items.size(), filename);
    return items;
  }

  private Workbook createWorkbook(InputStream inputStream, String filename) throws IOException {
    if (filename != null && filename.toLowerCase().endsWith(".xlsx")) {
      return new XSSFWorkbook(inputStream);
    }
    return new HSSFWorkbook(inputStream);
  }

  private List<ParsedInventoryItem> parseSheet(Sheet sheet, int maxItems) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    int lastRowNum = sheet.getLastRowNum();
    if (lastRowNum < 0 || maxItems <= 0) return items;

    int headerRowIdx = findHeaderRow(sheet, lastRowNum);
    if (headerRowIdx < 0) return items;

    Row headerRow = sheet.getRow(headerRowIdx);
    if (headerRow == null) return items;

    ColumnMapping mapping = createColumnMapping(headerRow);
    if (mapping.isEmpty()) return items;

    int startRow = headerRowIdx + 1;

    for (int r = startRow; r <= lastRowNum && items.size() < maxItems; r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;

      ParsedInventoryItem item = parseRow(row, mapping);
      if (item != null && isValid(item)) {
        items.add(item);
      }
    }
    return items;
  }

  private int findHeaderRow(Sheet sheet, int lastRowNum) {
    for (int r = 0; r <= Math.min(HEADER_SEARCH_ROWS, lastRowNum); r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;
      String text = getRowText(row);
      int matches = 0;
      for (String kw : HEADER_KEYWORDS) {
        if (text.contains(kw)) matches++;
      }
      if (matches >= 2) return r;
    }
    return 0;
  }

  private String getRowText(Row row) {
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c < row.getLastCellNum(); c++) {
      Cell cell = row.getCell(c);
      if (cell != null) {
        sb.append(getCellString(cell)).append(" ");
      }
    }
    return sb.toString().toUpperCase();
  }

  private ColumnMapping createColumnMapping(Row headerRow) {
    ColumnMapping m = new ColumnMapping();
    for (int col = 0; col < headerRow.getLastCellNum(); col++) {
      Cell cell = headerRow.getCell(col);
      String h = (cell != null ? getCellString(cell) : "").toUpperCase().trim()
          .replaceAll("^[:\\s]+", "").replaceAll("[\\s:]+$", "")
          .replaceAll("\\.", "");
      if (h.isEmpty()) continue;

      if ((h.contains("SL") && (h.contains("NO") || h.contains("NUM"))) || "SR".equals(h) || "SRNO".equals(h) || "SNO".equals(h))
        m.slNo = col;
      else if (h.contains("BARCODE") || "BC".equals(h))
        m.barcode = col;
      else if (h.contains("HSN") || h.contains("SAC"))
        m.hsn = col;
      else if (h.contains("PRODUCT") || h.contains("DESC") || h.contains("NAME") || h.contains("ITEM") || "PRODUCTS".equals(h)) {
        if (m.description == null || h.contains("PRODUCT")) m.description = col;
      } else if (h.contains("BATCH"))
        m.batchNo = col;
      else if (h.contains("MFD") || h.contains("MANUFACTURE") || h.contains("MFG") || h.contains("MKD"))
        m.mfd = col;
      else if (h.contains("EXPIRY") || h.contains("EXP DATE") || (h.contains("EXP") && !h.contains("TAX")) || "EXP".equals(h) || h.contains("EXPDT") || h.contains("EXP DT"))
        m.expiry = col;
      else if (h.contains("CURRENT") && h.contains("STOCK"))
        m.qty = col;
      else if (h.contains("QTY") || h.contains("QUANTITY") || "STOCK".equals(h) || "BALANCE".equals(h))
        m.qty = col;
      else if (h.contains("PURCHASE") && h.contains("PRICE"))
        m.purchasePrice = col;
      else if (h.contains("SALES") && h.contains("PRICE"))
        m.salesPrice = col;
      else if ((h.contains("RATE") || h.contains("PRICE") || h.contains("COST")) && !h.contains("MRP") && !h.contains("GST"))
        m.rate = col;
      else if (h.contains("MRP") && !h.contains("REDUCED") && !h.contains("RED"))
        m.mrp = col;
      else if (h.contains("REDUCED") || h.contains("RED MRP"))
        m.reducedMrp = col;
      else if (h.contains("PACK") || h.contains("PKG"))
        m.pkgDetail = col;
      else if ("SGST".equals(h) || (h.contains("SGST") && !h.contains("VALUE") && !h.contains("AMOUNT")))
        m.sgst = col;
      else if ("CGST".equals(h) || (h.contains("CGST") && !h.contains("VALUE") && !h.contains("AMOUNT")))
        m.cgst = col;
      else if (h.contains("DISC") || h.contains("DISCOUNT"))
        m.discount = col;
      else if (h.contains("SCHEME") && !"DEAL".equals(h) && !"FREE".equals(h))
        m.scheme = col;
      else if ("DEAL".equals(h) || (h.contains("SALES") && h.contains("SCHEME") && h.contains("DEAL")))
        m.schemePayFor = col;
      else if ("FREE".equals(h) || (h.contains("SALES") && h.contains("SCHEME") && h.contains("FREE")))
        m.schemeFree = col;
      else if ((h.contains("REC") && (h.contains("DATE") || h.contains("DT"))) || "RECDATE".equals(h.replaceAll("\\s", "")))
        m.recDate = col;
      else if (h.contains("UNIT") || h.contains("UOM"))
        m.unit = col;
      else if (h.contains("COMPANY") || h.contains("MFG") || h.contains("MANUFACTURER"))
        m.company = col;
    }
    return m;
  }

  private ParsedInventoryItem parseRow(Row row, ColumnMapping m) {
    try {
      ParsedInventoryItem item = new ParsedInventoryItem();
      item.setCustomReminders(new ArrayList<>());
      item.setBusinessType("PHARMACEUTICAL");
      item.setThresholdCount(10);

      setStr(row, m.barcode, item::setBarcode);
      setHsn(row, m, item);
      setStr(row, m.description, v -> {
        item.setName(v);
        item.setDescription(v);
      });
      setStr(row, m.batchNo, item::setBatchNo);
      setStr(row, m.company, item::setCompanyName);
      setCompanyFromMfd(row, m.mfd, item);
      setExpiryAndReminder(row, m.expiry, item);
      setInt(row, m.qty, item::setCount);
      setPrices(row, m, item);
      setDecimal(row, m.discount, item::setAdditionalDiscount);
      setInt(row, m.scheme, item::setScheme);
      setInt(row, m.schemePayFor, item::setSchemePayFor);
      setInt(row, m.schemeFree, item::setSchemeFree);
      setRecDate(row, m.recDate, item);
      if (m.sgst != null) {
        String v = clean(getCellStringSafe(row, m.sgst)).replaceAll("[^0-9.]", "");
        if (!v.isEmpty()) item.setSgst(v);
      }
      if (m.cgst != null) {
        String v = clean(getCellStringSafe(row, m.cgst)).replaceAll("[^0-9.]", "");
        if (!v.isEmpty()) item.setCgst(v);
      }

      if (item.getName() == null && item.getBarcode() != null) {
        item.setName(item.getBarcode());
        item.setDescription(item.getBarcode());
      }
      if (item.getCount() == null && (item.getName() != null || item.getBarcode() != null)) {
        item.setCount(1);
      }

      return item;
    } catch (Exception e) {
      log.debug("Parse row failed: {}", e.getMessage());
      return null;
    }
  }

  private String getCellString(Cell cell) {
    if (cell == null) return "";
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> {
        if (DateUtil.isCellDateFormatted(cell)) {
          try {
            java.util.Date d = cell.getDateCellValue();
            if (d != null) {
              yield d.toInstant().toString();
            }
          } catch (Exception ignored) {}
        }
        double n = cell.getNumericCellValue();
        if (n == (long) n) yield String.valueOf((long) n);
        yield String.valueOf(n);
      }
      case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
      case FORMULA -> {
        try {
          FormulaEvaluator eval = cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator();
          CellValue cv = eval.evaluate(cell);
          if (cv != null) yield String.valueOf(cv.getNumberValue());
        } catch (Exception ignored) {}
        yield cell.toString();
      }
      default -> cell.toString();
    };
  }

  private String getCellStringSafe(Row row, int col) {
    Cell cell = row.getCell(col);
    return cell != null ? getCellString(cell) : "";
  }

  private void setStr(Row row, Integer col, java.util.function.Consumer<String> setter) {
    if (col == null) return;
    String v = clean(getCellStringSafe(row, col));
    if (!v.isEmpty()) setter.accept(v);
  }

  private void setHsn(Row row, ColumnMapping m, ParsedInventoryItem item) {
    if (m.hsn != null) {
      String v = clean(getCellStringSafe(row, m.hsn)).replaceAll("[^0-9]", "");
      if (!v.isEmpty() && v.matches("\\d+")) item.setHsn(v);
    }
    if ((item.getHsn() == null || item.getHsn().isEmpty()) && m.mfd != null) {
      String v = clean(getCellStringSafe(row, m.mfd));
      Matcher mat = HSN_PREFIX.matcher(v);
      if (mat.find()) item.setHsn(mat.group(1));
    }
  }

  private void setCompanyFromMfd(Row row, Integer col, ParsedInventoryItem item) {
    if (col == null) return;
    if (item.getCompanyName() != null && !item.getCompanyName().isEmpty()) return;
    String v = extractCompanyName(clean(getCellStringSafe(row, col)));
    if (v != null && !v.isEmpty()) item.setCompanyName(v);
  }

  private void setRecDate(Row row, Integer col, ParsedInventoryItem item) {
    if (col == null) return;
    String raw = clean(getCellStringSafe(row, col));
    if (raw.isEmpty()) return;
    String iso = null;
    if (isIso8601(raw)) {
      iso = raw;
    } else {
      String monYy = parseDate(raw);
      if (monYy != null) iso = toIso8601(monYy);
    }
    if (iso != null) item.setPurchaseDate(iso);
  }

  private void setExpiryAndReminder(Row row, Integer col, ParsedInventoryItem item) {
    if (col == null) return;
    String raw = clean(getCellStringSafe(row, col));
    String iso = null;
    if (isIso8601(raw)) iso = raw;
    else {
      String monYy = parseDate(raw);
      if (monYy != null) iso = toIso8601(monYy);
    }
    if (iso == null) return;
    item.setExpiryDate(iso);
    String rem = reminderIso(iso, 30);
    item.setReminderAt(rem);
    List<ParsedReminderDto> list = new ArrayList<>();
    ParsedReminderDto r = new ParsedReminderDto();
    r.setReminderAt(rem);
    r.setEndDate(iso);
    r.setNotes("Expiry reminder - 30 days before expiry");
    list.add(r);
    item.setCustomReminders(list);
  }

  private void setInt(Row row, Integer col, java.util.function.Consumer<Integer> setter) {
    if (col == null) return;
    BigDecimal d = parseDecimal(clean(getCellStringSafe(row, col)));
    if (d != null && d.signum() >= 0) {
      setter.accept(d.intValue());
    }
  }

  private void setDecimal(Row row, Integer col, java.util.function.Consumer<BigDecimal> setter) {
    if (col == null) return;
    BigDecimal d = parseDecimal(clean(getCellStringSafe(row, col)));
    if (d != null) setter.accept(d);
  }

  private void setPrices(Row row, ColumnMapping m, ParsedInventoryItem item) {
    if (m.purchasePrice != null) {
      BigDecimal r = parseDecimal(clean(getCellStringSafe(row, m.purchasePrice)));
      if (r != null) item.setCostPrice(r);
    }
    if (item.getCostPrice() == null && m.rate != null) {
      BigDecimal r = parseDecimal(clean(getCellStringSafe(row, m.rate)));
      if (r != null) item.setCostPrice(r);
    }
    if (m.salesPrice != null) {
      BigDecimal r = parseDecimal(clean(getCellStringSafe(row, m.salesPrice)));
      if (r != null) item.setPriceToRetail(r);
    }
    if (item.getPriceToRetail() == null && m.reducedMrp != null) {
      BigDecimal r = parseDecimal(clean(getCellStringSafe(row, m.reducedMrp)));
      if (r != null) item.setPriceToRetail(r);
    }
    if (item.getPriceToRetail() == null && item.getCostPrice() != null) {
      item.setPriceToRetail(item.getCostPrice());
    }
    if (m.mrp != null) {
      String raw = getCellStringSafe(row, m.mrp);
      BigDecimal r = parseDecimal(clean(raw));
      if (r != null) {
        if (r.compareTo(BigDecimal.valueOf(1000)) > 0 && !raw.contains("."))
          r = r.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        item.setMaximumRetailPrice(r);
      }
    }
  }

  private String clean(String s) {
    if (s == null) return "";
    return s.trim().replaceAll("^[:\\s]+", "").replaceAll("[\\s:;]+$", "").replaceAll("\\s+", " ");
  }

  private String extractCompanyName(String mfd) {
    if (mfd == null || mfd.trim().isEmpty()) return null;
    String v = mfd.trim()
        .replaceAll("^\\d{7,9}\\s*[:\\-]?\\s*", "")
        .replaceAll("\\d{1,2}/\\d{2}", "")
        .replaceAll("[A-Z]{3}[-.]?\\d{2}", "")
        .replaceAll("[:\\s]+\\d{1,2}/\\d{2}\\s*$", "")
        .replaceAll("[:\\s]+[A-Z]{3}[-.]?\\d{2}\\s*$", "")
        .replaceAll("^[:\\-\\s]+", "").replaceAll("[:\\-\\s]+$", "")
        .replaceAll("\\s+", " ").trim();
    if (!v.isEmpty() && v.matches(".*[A-Za-z].*") && !v.matches("^\\d+$") && v.length() >= 2) return v;
    return null;
  }

  private String parseDate(String s) {
    if (s == null || s.trim().isEmpty()) return null;
    s = s.trim().toUpperCase();
    Matcher mm = MM_YY.matcher(s);
    if (mm.find()) {
      int month = Integer.parseInt(mm.group(1));
      if (month >= 1 && month <= 12) {
        String[] names = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
        return names[month - 1] + "-" + mm.group(2);
      }
    }
    Matcher mon = MON_YY.matcher(s);
    if (mon.find()) return mon.group(1) + "-" + mon.group(2);
    return null;
  }

  private BigDecimal parseDecimal(String s) {
    if (s == null || s.trim().isEmpty()) return null;
    String v = s.replace(",", "").replaceAll("[^0-9.]", "");
    if (v.isEmpty()) return null;
    try {
      return new BigDecimal(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String toIso8601(String monYy) {
    if (monYy == null || monYy.trim().isEmpty()) return null;
    Matcher m = MON_YY.matcher(monYy.trim().toUpperCase());
    if (!m.find()) return null;
    int month = monthNum(m.group(1));
    if (month < 1) return null;
    String y = m.group(2);
    int year = y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y);
    try {
      return LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    } catch (Exception e) {
      return null;
    }
  }

  private String reminderIso(String expiryIso, int daysBefore) {
    if (expiryIso == null || expiryIso.isEmpty()) return null;
    try {
      LocalDate d = java.time.Instant.parse(expiryIso).atOffset(ZoneOffset.UTC).toLocalDate();
      return d.minusDays(daysBefore).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isIso8601(String s) {
    return s != null && s.length() >= 20 && s.contains("T") && (s.endsWith("Z") || s.contains("+") || s.matches(".*\\d{2}:\\d{2}:\\d{2}.*"));
  }

  private int monthNum(String m) {
    return switch (m.toUpperCase()) {
      case "JAN" -> 1;
      case "FEB" -> 2;
      case "MAR" -> 3;
      case "APR" -> 4;
      case "MAY" -> 5;
      case "JUN" -> 6;
      case "JUL" -> 7;
      case "AUG" -> 8;
      case "SEP" -> 9;
      case "OCT" -> 10;
      case "NOV" -> 11;
      case "DEC" -> 12;
      default -> -1;
    };
  }

  private boolean isValid(ParsedInventoryItem item) {
    boolean hasName = item.getName() != null && !item.getName().trim().isEmpty();
    boolean hasBarcode = item.getBarcode() != null && !item.getBarcode().trim().isEmpty();
    boolean hasCount = item.getCount() != null && item.getCount() > 0;
    return (hasName || hasBarcode) && (hasCount || hasName);
  }

  private static class ColumnMapping {
    Integer slNo, barcode, hsn, description, batchNo, mfd, expiry, qty, rate, purchasePrice, salesPrice, mrp, reducedMrp, pkgDetail;
    Integer sgst, cgst, discount, scheme, schemePayFor, schemeFree, unit, company;
    Integer recDate;

    boolean isEmpty() {
      return description == null && barcode == null && qty == null;
    }
  }
}
