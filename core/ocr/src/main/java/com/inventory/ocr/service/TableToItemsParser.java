package com.inventory.ocr.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import com.inventory.ocr.model.OcrCell;
import com.inventory.ocr.model.OcrTable;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses OCR tables (e.g. from AWS Textract) into {@link ParsedInventoryItem} list.
 * Used by table-based providers only.
 */
@Slf4j
public class TableToItemsParser {

  private static final Pattern MON_YY = Pattern.compile("([A-Z]{3})[-.]?(\\d{2,4})");
  private static final Pattern MM_YY = Pattern.compile("(\\d{1,2})/(\\d{2})");
  private static final Pattern HSN_PREFIX = Pattern.compile("^(\\d{7,9})");
  private static final String[] HEADER_KEYWORDS = {
      "CGST", "SGST", "HSN", "MRP", "RATE", "BATCH", "BATCH NO",
      "PRODUCT", "DESCRIPTION", "CODE", "SL NO", "SLNO", "QTY", "QUANTITY",
      "EXPIRY", "EXP", "MFD", "MANUFACTURE", "PACK", "PACKAGE", "MFG", "MKD"
  };

  public List<ParsedInventoryItem> parse(List<OcrTable> tables) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    if (tables == null || tables.isEmpty()) {
      return items;
    }
    for (OcrTable table : tables) {
      items.addAll(parseTable(table));
    }
    return items;
  }

  private List<ParsedInventoryItem> parseTable(OcrTable table) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    if (table.getCells() == null || table.getCells().isEmpty()) {
      return items;
    }
    Map<Integer, Map<Integer, CellData>> tableData = toCellData(table);
    int maxRow = tableData.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    int maxCol = tableData.values().stream()
        .flatMap(m -> m.keySet().stream())
        .mapToInt(Integer::intValue)
        .max().orElse(0);

    int headerRow = findHeaderRow(tableData, maxRow, maxCol);
    if (headerRow < 0) {
      return items;
    }
    Map<Integer, String> header = new HashMap<>();
    for (Map.Entry<Integer, CellData> e : tableData.get(headerRow).entrySet()) {
      header.put(e.getKey(), e.getValue().text);
    }
    ColumnMapping mapping = createColumnMapping(header, maxCol);
    if (mapping.isEmpty()) {
      return items;
    }

    for (int row = headerRow + 1; row <= maxRow; row++) {
      Map<Integer, CellData> rowCells = tableData.get(row);
      if (rowCells == null || rowCells.isEmpty()) continue;
      if (rowCells.values().stream().anyMatch(c -> c.isHeader)) continue;

      Map<Integer, String> rowData = new HashMap<>();
      for (Map.Entry<Integer, CellData> e : rowCells.entrySet()) {
        rowData.put(e.getKey(), e.getValue().text);
      }
      ParsedInventoryItem item = parseRow(rowData, mapping);
      if (item != null && isValid(item)) {
        items.add(item);
      }
    }
    return items;
  }

  private Map<Integer, Map<Integer, CellData>> toCellData(OcrTable table) {
    Map<Integer, Map<Integer, CellData>> out = new HashMap<>();
    for (Map.Entry<Integer, Map<Integer, OcrCell>> row : table.getCells().entrySet()) {
      Map<Integer, CellData> rowData = new HashMap<>();
      for (Map.Entry<Integer, OcrCell> cell : row.getValue().entrySet()) {
        OcrCell c = cell.getValue();
        rowData.put(cell.getKey(), new CellData(
            c.getText() != null ? c.getText().trim() : "",
            c.getIsHeader() != null && c.getIsHeader()));
      }
      out.put(row.getKey(), rowData);
    }
    return out;
  }

  private int findHeaderRow(Map<Integer, Map<Integer, CellData>> data, int maxRow, int maxCol) {
    for (int r = 0; r <= Math.min(3, maxRow); r++) {
      Map<Integer, CellData> row = data.get(r);
      if (row == null || row.isEmpty()) continue;
      long headers = row.values().stream().filter(c -> c.isHeader).count();
      if (headers > 0 && headers >= row.size() / 2) return r;
    }
    for (int r = 0; r <= Math.min(3, maxRow); r++) {
      Map<Integer, CellData> row = data.get(r);
      if (row == null || row.isEmpty()) continue;
      String text = row.values().stream().map(c -> c.text).collect(Collectors.joining(" ")).toUpperCase();
      int matches = 0;
      for (String kw : HEADER_KEYWORDS) {
        if (text.contains(kw)) matches++;
      }
      if (matches >= 2) return r;
    }
    return maxRow >= 0 ? (data.getOrDefault(1, Map.of()).size() > data.getOrDefault(0, Map.of()).size() ? 1 : 0) : -1;
  }

  private ColumnMapping createColumnMapping(Map<Integer, String> header, int maxCol) {
    ColumnMapping m = new ColumnMapping();
    for (int col = 1; col <= maxCol; col++) {
      String h = header.getOrDefault(col, "").toUpperCase().trim()
          .replaceAll("^[:\\s]+", "").replaceAll("[\\s:]+$", "")
          .replaceAll("\\.", "");
      if (h.isEmpty()) continue;
      if ((h.contains("SL") && (h.contains("NO") || h.contains("NUM"))) || "SR".equals(h) || "SRNO".equals(h) || "SNO".equals(h)) m.slNo = col;
      else if (h.contains("CODE") && !h.contains("HSN") && !h.contains("SAC")) m.code = col;
      else if (h.contains("HSN") || h.contains("SAC")) m.hsn = col;
      else if (h.contains("PRODUCT") || h.contains("DESC") || h.contains("NAME") || h.contains("ITEM") || "PRODUCTS".equals(h)) {
        if (m.description == null || h.contains("PRODUCT")) m.description = col;
      } else if (h.contains("BATCH")) m.batchNo = col;
      else if (h.contains("MFD") || h.contains("MANUFACTURE") || h.contains("MFG") || h.contains("MKD")) m.mfd = col;
      else if (h.contains("EXPIRY") || h.contains("EXP DATE") || (h.contains("EXP") && !h.contains("TAX")) || "EXP".equals(h) || h.contains("EXPDT") || h.contains("EXP DT")) m.expiry = col;
      else if (h.contains("QTY") || "QUANTITY".equals(h)) m.qty = col;
      else if (h.contains("RATE") && !h.contains("MRP") && !h.contains("GST")) m.rate = col;
      else if (h.contains("MRP") && !h.contains("REDUCED") && !h.contains("RED")) m.mrp = col;
      else if (h.contains("REDUCED") || h.contains("RED MRP")) m.reducedMrp = col;
      else if (h.contains("PACK") || h.contains("PKG")) m.pkgDetail = col;
      else if ("SGST".equals(h) || (h.contains("SGST") && !h.contains("VALUE") && !h.contains("AMOUNT"))) m.sgst = col;
      else if ("CGST".equals(h) || (h.contains("CGST") && !h.contains("VALUE") && !h.contains("AMOUNT"))) m.cgst = col;
      else if (h.contains("DISC") || h.contains("DISCOUNT")) m.discount = col;
      else if (h.contains("SCHEME")) m.scheme = col;
    }
    return m;
  }

  private ParsedInventoryItem parseRow(Map<Integer, String> row, ColumnMapping m) {
    try {
      ParsedInventoryItem item = new ParsedInventoryItem();
      item.setCustomReminders(new ArrayList<>());
      item.setBusinessType("PHARMACEUTICAL");
      item.setThresholdCount(10);

      setStr(row, m.code, item::setBarcode);
      setHsn(row, m, item);
      setStr(row, m.description, v -> { item.setName(v); item.setDescription(v); });
      setStr(row, m.batchNo, item::setBatchNo);
      setCompanyFromMfd(row, m.mfd, item);
      setExpiryAndReminder(row, m.expiry, item);
      setInt(row, m.qty, item::setCount);
      setPrices(row, m, item);
      setDecimal(row, m.discount, item::setSaleAdditionalDiscount);
      setInt(row, m.scheme, item::setScheme);
      if (m.sgst != null && row.containsKey(m.sgst)) {
        String v = clean(row.get(m.sgst)).replaceAll("[^0-9.]", "");
        if (!v.isEmpty()) item.setSgst(v);
      }
      if (m.cgst != null && row.containsKey(m.cgst)) {
        String v = clean(row.get(m.cgst)).replaceAll("[^0-9.]", "");
        if (!v.isEmpty()) item.setCgst(v);
      }

      return item;
    } catch (Exception e) {
      log.debug("Parse row failed: {}", e.getMessage());
      return null;
    }
  }

  private void setStr(Map<Integer, String> row, Integer col, java.util.function.Consumer<String> setter) {
    if (col == null) return;
    String v = clean(row.get(col));
    if (!v.isEmpty()) setter.accept(v);
  }

  private void setHsn(Map<Integer, String> row, ColumnMapping m, ParsedInventoryItem item) {
    if (m.hsn != null && row.containsKey(m.hsn)) {
      String v = clean(row.get(m.hsn)).replaceAll("[^0-9]", "");
      if (!v.isEmpty() && v.matches("\\d+")) item.setHsn(v);
    }
    if ((item.getHsn() == null || item.getHsn().isEmpty()) && m.mfd != null && row.containsKey(m.mfd)) {
      String v = clean(row.get(m.mfd));
      Matcher mat = HSN_PREFIX.matcher(v);
      if (mat.find()) item.setHsn(mat.group(1));
    }
  }

  private void setCompanyFromMfd(Map<Integer, String> row, Integer col, ParsedInventoryItem item) {
    if (col == null || !row.containsKey(col)) return;
    String v = extractCompanyName(clean(row.get(col)));
    if (v != null && !v.isEmpty()) item.setCompanyName(v);
  }

  private void setExpiryAndReminder(Map<Integer, String> row, Integer col, ParsedInventoryItem item) {
    if (col == null || !row.containsKey(col)) return;
    String raw = clean(row.get(col));
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

  private void setInt(Map<Integer, String> row, Integer col, java.util.function.Consumer<Integer> setter) {
    if (col == null || !row.containsKey(col)) return;
    String v = clean(row.get(col)).replace(",", "").replaceAll("[^0-9]", "");
    if (!v.isEmpty()) {
      try { setter.accept(Integer.parseInt(v)); } catch (NumberFormatException ignored) {}
    }
  }

  private void setDecimal(Map<Integer, String> row, Integer col, java.util.function.Consumer<BigDecimal> setter) {
    if (col == null || !row.containsKey(col)) return;
    BigDecimal d = parseDecimal(clean(row.get(col)));
    if (d != null) setter.accept(d);
  }

  private void setPrices(Map<Integer, String> row, ColumnMapping m, ParsedInventoryItem item) {
    if (m.rate != null && row.containsKey(m.rate)) {
      BigDecimal r = parseDecimal(clean(row.get(m.rate)));
      if (r != null) {
        item.setCostPrice(r);
        if (item.getPriceToRetail() == null) item.setPriceToRetail(r);
      }
    }
    if (m.reducedMrp != null && row.containsKey(m.reducedMrp)) {
      BigDecimal r = parseDecimal(clean(row.get(m.reducedMrp)));
      if (r != null) item.setPriceToRetail(r);
    }
    if (m.mrp != null && row.containsKey(m.mrp)) {
      String raw = row.get(m.mrp);
      BigDecimal r = parseDecimal(clean(raw));
      if (r != null) {
        if (r.compareTo(BigDecimal.valueOf(1000)) > 0 && raw != null && !raw.contains("."))
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
        String[] names = {"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
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
    try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
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
    switch (m.toUpperCase()) {
      case "JAN": return 1; case "FEB": return 2; case "MAR": return 3; case "APR": return 4;
      case "MAY": return 5; case "JUN": return 6; case "JUL": return 7; case "AUG": return 8;
      case "SEP": return 9; case "OCT": return 10; case "NOV": return 11; case "DEC": return 12;
      default: return -1;
    }
  }

  private boolean isValid(ParsedInventoryItem item) {
    boolean hasName = item.getName() != null && !item.getName().trim().isEmpty();
    boolean hasBarcode = item.getBarcode() != null && !item.getBarcode().trim().isEmpty();
    boolean hasCount = item.getCount() != null && item.getCount() > 0;
    return (hasName || hasBarcode) && (hasCount || hasName);
  }

  private static class CellData {
    final String text;
    final boolean isHeader;
    CellData(String text, boolean isHeader) {
      this.text = text != null ? text.trim() : "";
      this.isHeader = isHeader;
    }
  }

  private static class ColumnMapping {
    Integer slNo, code, hsn, description, batchNo, mfd, expiry, qty, rate, mrp, reducedMrp, pkgDetail;
    Integer sgst, cgst, discount, scheme;
    boolean isEmpty() {
      return description == null && code == null && qty == null;
    }
  }
}
