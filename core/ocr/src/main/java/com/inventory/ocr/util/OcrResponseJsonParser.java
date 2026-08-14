package com.inventory.ocr.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.ocr.dto.ParsedInventoryItem;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses vision-model JSON (raw array or {@code {"items":[...]}}) into {@link ParsedInventoryItem}s.
 * Shared by Gemini and ChatGPT so both accept the same contract.
 */
@Slf4j
public final class OcrResponseJsonParser {

  private final ObjectMapper objectMapper;

  public OcrResponseJsonParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<ParsedInventoryItem> parse(String raw) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    String json = stripMarkdownAndExtractJson(raw);
    if (json == null || json.isBlank()) {
      return items;
    }

    try {
      JsonNode parsed = objectMapper.readTree(json);
      JsonNode arr = extractItemsArray(parsed);
      if (arr == null) {
        log.warn("Unexpected OCR JSON structure: {}", json.substring(0, Math.min(200, json.length())));
        return items;
      }
      for (JsonNode n : arr) {
        ParsedInventoryItem item = jsonToItem(n);
        if (item != null) {
          items.add(item);
        }
      }
    } catch (Exception e) {
      log.warn("Parse OCR JSON failed: {}, attempting truncated JSON extraction", e.getMessage());
      items = extractCompleteObjectsFromTruncatedJson(json);
    }
    return items;
  }

  static String stripMarkdownAndExtractJson(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.startsWith("```")) {
      int start = s.indexOf('\n');
      s = start >= 0 ? s.substring(start + 1) : s.substring(3);
      int end = s.lastIndexOf("```");
      if (end >= 0) {
        s = s.substring(0, end);
      }
      s = s.trim();
    }
    int arrStart = s.indexOf('[');
    int objStart = s.indexOf('{');
    int jsonStart = -1;
    if (arrStart >= 0 && (objStart < 0 || arrStart <= objStart)) {
      jsonStart = arrStart;
    } else if (objStart >= 0) {
      jsonStart = objStart;
    }
    if (jsonStart > 0) {
      s = s.substring(jsonStart);
    }
    return s;
  }

  static JsonNode extractItemsArray(JsonNode parsed) {
    if (parsed == null) {
      return null;
    }
    if (parsed.isArray()) {
      return parsed;
    }
    if (parsed.has("items") && parsed.get("items").isArray()) {
      return parsed.get("items");
    }
    return null;
  }

  private List<ParsedInventoryItem> extractCompleteObjectsFromTruncatedJson(String json) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    int i = json.indexOf('[');
    if (i < 0) {
      i = 0;
    }
    int len = json.length();
    while (i < len) {
      char c = json.charAt(i);
      if (c == '{') {
        int depth = 1;
        int start = i;
        i++;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;
        while (i < len && depth > 0) {
          char ch = json.charAt(i);
          if (escaped) {
            escaped = false;
            i++;
            continue;
          }
          if (ch == '\\' && inString) {
            escaped = true;
            i++;
            continue;
          }
          if (!inString) {
            if (ch == '"' || ch == '\'') {
              inString = true;
              stringChar = ch;
            } else if (ch == '{') {
              depth++;
            } else if (ch == '}') {
              depth--;
            }
          } else if (ch == stringChar) {
            inString = false;
          }
          i++;
        }
        if (depth == 0) {
          String objStr = json.substring(start, i);
          try {
            JsonNode node = objectMapper.readTree(objStr);
            JsonNode arr = extractItemsArray(node);
            if (arr != null) {
              for (JsonNode n : arr) {
                ParsedInventoryItem item = jsonToItem(n);
                if (item != null) {
                  items.add(item);
                }
              }
            } else {
              ParsedInventoryItem item = jsonToItem(node);
              if (item != null) {
                items.add(item);
              }
            }
          } catch (Exception ignored) {
            /* skip malformed object */
          }
        }
      } else if (c == ']' || c == '}') {
        break;
      } else {
        i++;
      }
    }
    if (!items.isEmpty()) {
      log.info("Recovered {} items from truncated OCR JSON", items.size());
    }
    return items;
  }

  static ParsedInventoryItem jsonToItem(JsonNode n) {
    ParsedInventoryItem item = new ParsedInventoryItem();
    item.setCustomReminders(new ArrayList<>());
    item.setThresholdCount(10);
    item.setBarcode(str(n, "barcode"));
    String name = str(n, "name");
    item.setName(name != null ? PackTextParser.cleanProductName(name) : null);
    item.setDescription(str(n, "description"));
    item.setCompanyName(str(n, "companyName"));
    item.setMaximumRetailPrice(num(n, "maximumRetailPrice"));
    item.setCostPrice(num(n, "costPrice"));
    item.setPriceToRetail(num(n, "priceToRetail"));
    BigDecimal addDisc = num(n, "saleAdditionalDiscount");
    if (addDisc == null) {
      addDisc = num(n, "additionalDiscount");
    }
    item.setSaleAdditionalDiscount(addDisc);
    item.setBusinessType(str(n, "businessType") != null ? str(n, "businessType") : "PHARMACEUTICAL");
    item.setLocation(str(n, "location"));
    item.setCount(intNum(n, "count"));
    item.setThresholdCount(intNum(n, "thresholdCount") != null ? intNum(n, "thresholdCount") : 10);
    item.setExpiryDate(str(n, "expiryDate"));
    item.setReminderAt(str(n, "reminderAt"));
    item.setHsn(str(n, "hsn"));
    item.setBatchNo(str(n, "batchNo"));
    OcrJsonSchemeSupport.applySchemeFromJson(n, item);
    OcrJsonPackagingSupport.applyPackagingFromJson(n, item);
    item.setSgst(str(n, "sgst"));
    item.setCgst(str(n, "cgst"));
    if (item.getName() == null || item.getName().isBlank()) {
      return null;
    }
    return item;
  }

  private static String str(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    String s = v.asText(null);
    return (s != null && !s.isBlank()) ? s : null;
  }

  private static BigDecimal num(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isNumber()) {
      return v.decimalValue();
    }
    try {
      return new BigDecimal(v.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private static Integer intNum(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) {
      return null;
    }
    if (v.isInt()) {
      return v.intValue();
    }
    try {
      return Integer.parseInt(v.asText());
    } catch (Exception e) {
      return null;
    }
  }
}
