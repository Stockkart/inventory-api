package com.inventory.ocr.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.preprocess.ImagePreprocessor;
import com.inventory.ocr.provider.OcrProvider;
import com.inventory.ocr.constants.OcrConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoice parser using Google Gemini API with vision capabilities.
 * Sends image + prompt, parses JSON response directly to {@link ParsedInventoryItem}.
 */
@Slf4j
public class GeminiOcrProvider implements OcrProvider {

  private final String model;
  private final String apiUrl;

  private static final String PROMPT = """
      Extract ONLY product line items from the invoice image.

      Return ONLY a JSON array (no markdown/text). Each object MUST have these keys:
      barcode, name, description, companyName, maximumRetailPrice, costPrice, sellingPrice, additionalDiscount,
      businessType, location, count, thresholdCount, expiryDate, reminderAt, customReminders, hsn, batchNo, scheme, sgst, cgst.
  
      Rules:
      - Missing fields => null.
      - barcode must be null unless the invoice explicitly shows Barcode/EAN/UPC.
      - name MUST include full product name with pack size/strength if present.
        Do not shorten the name.
      - Numbers must be numeric (not strings).
      - count: use QTY/Qty/Quantity/Count/Nos/Units; if missing compute from PKG DETAIL like "3 x 56" => 168.
      - maximumRetailPrice: use Reduced MRP if present else MRP.
      - sellingPrice: prefer Selling Price / PTR / Price to Retail
      - costPrice: UNIT cost only from Rate / PTS / Price to Stockist / Cost Price (never total/taxable/value/net).
      - hsn must be copied exactly as printed (usually 8 digits).
      - expiryDate/reminderAt: ISO-8601 UTC; if month-year only use first day of month.
      - sgst/cgst: rate only like "2.5". Ignore totals/tax summary rows.
  """;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final ImagePreprocessor imagePreprocessor;
  private final String apiKey;

  public GeminiOcrProvider(String apiKey, String model, ImagePreprocessor imagePreprocessor) {
    this.apiKey = apiKey;
    this.model = model;
    this.apiUrl = OcrConstants.GEMINI_API_BASE_URL + model + ":generateContent";
    this.restClient = RestClient.builder()
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.objectMapper = new ObjectMapper();
    this.imagePreprocessor = imagePreprocessor;
  }

  @Override
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    log.info("Gemini ({}) invoice parse, image size before preprocess: {} bytes", model, imageBytes.length);
    byte[] toSend = imagePreprocessor.preprocess(imageBytes);
    log.info("Image size after preprocess: {} bytes", toSend.length);

    String base64 = Base64.getEncoder().encodeToString(toSend);

    // Build Gemini API request structure
    Map<String, Object> inlineData = new HashMap<>();
    inlineData.put("mime_type", "image/jpeg");
    inlineData.put("data", base64);

    List<Map<String, Object>> parts = List.of(
        Map.of("inline_data", inlineData),
        Map.of("text", PROMPT)
    );

    Map<String, Object> content = Map.of("parts", parts);

    Map<String, Object> generationConfig = new HashMap<>();
    generationConfig.put("temperature", OcrConstants.DEFAULT_TEMPERATURE);
    generationConfig.put("maxOutputTokens", OcrConstants.DEFAULT_MAX_OUTPUT_TOKENS_GEMINI);

    Map<String, Object> request = new HashMap<>();
    request.put("contents", List.of(content));
    request.put("generationConfig", generationConfig);

    String body = restClient.post()
        .uri(apiUrl + "?key=" + apiKey)
        .body(request)
        .retrieve()
        .body(String.class);

    log.info("Gemini API response: {}", body);

    String text = extractOutputText(body);
    if (text == null || text.isBlank()) {
      log.warn("No output text from Gemini API");
      return List.of();
    }

    return parseJsonToItems(text);
  }

  @Override
  public String getProviderName() {
    return "GEMINI_" + model.toUpperCase().replace("-", "_").replace(".", "_");
  }

  /**
   * Extracts the text content from Gemini API response.
   * Response format:
   * {
   *   "candidates": [{
   *     "content": {
   *       "parts": [{"text": "..."}],
   *       "role": "model"
   *     }
   *   }]
   * }
   */
  private String extractOutputText(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode candidates = root.path("candidates");
      if (!candidates.isArray() || candidates.isEmpty()) return null;

      JsonNode firstCandidate = candidates.get(0);
      JsonNode content = firstCandidate.path("content");
      JsonNode parts = content.path("parts");

      if (!parts.isArray() || parts.isEmpty()) return null;

      for (JsonNode part : parts) {
        JsonNode textNode = part.path("text");
        if (textNode.isTextual()) {
          return textNode.asText();
        }
      }
    } catch (Exception e) {
      log.warn("Failed to extract output text from Gemini response: {}", e.getMessage());
    }
    return null;
  }

  private List<ParsedInventoryItem> parseJsonToItems(String raw) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    try {
      String json = raw.trim();
      // Strip markdown code fences if present
      if (json.startsWith("```")) {
        int start = json.indexOf('\n') + 1;
        int end = json.lastIndexOf("```");
        if (end > start) json = json.substring(start, end).trim();
      }

      JsonNode parsed = objectMapper.readTree(json);
      JsonNode arr;

      // Handle both {"items":[...]} and direct [...] array formats
      if (parsed.isArray()) {
        arr = parsed;
      } else if (parsed.has("items") && parsed.get("items").isArray()) {
        arr = parsed.get("items");
      } else {
        log.warn("Unexpected JSON structure from Gemini: {}", json.substring(0, Math.min(200, json.length())));
        return items;
      }

      for (JsonNode n : arr) {
        ParsedInventoryItem item = jsonToItem(n);
        if (item != null) items.add(item);
      }
    } catch (Exception e) {
      log.warn("Parse Gemini JSON failed: {}", e.getMessage());
    }
    return items;
  }

  private ParsedInventoryItem jsonToItem(JsonNode n) {
    ParsedInventoryItem item = new ParsedInventoryItem();
    item.setCustomReminders(new ArrayList<>());
    item.setBusinessType("PHARMACEUTICAL");
    item.setThresholdCount(10);
    item.setBarcode(str(n, "barcode"));
    item.setName(str(n, "name"));
    item.setDescription(str(n, "description"));
    item.setCompanyName(str(n, "companyName"));
    item.setMaximumRetailPrice(num(n, "maximumRetailPrice"));
    item.setCostPrice(num(n, "costPrice"));
    item.setSellingPrice(num(n, "sellingPrice"));
    item.setAdditionalDiscount(num(n, "additionalDiscount"));
    item.setBusinessType(str(n, "businessType") != null ? str(n, "businessType") : "PHARMACEUTICAL");
    item.setLocation(str(n, "location"));
    item.setCount(intNum(n, "count"));
    item.setThresholdCount(intNum(n, "thresholdCount") != null ? intNum(n, "thresholdCount") : 10);
    item.setExpiryDate(str(n, "expiryDate"));
    item.setReminderAt(str(n, "reminderAt"));
    item.setHsn(str(n, "hsn"));
    item.setBatchNo(str(n, "batchNo"));
    item.setScheme(str(n, "scheme"));
    item.setSgst(str(n, "sgst"));
    item.setCgst(str(n, "cgst"));
    if (item.getName() == null || item.getName().isBlank()) return null;
    return item;
  }

  private static String str(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) return null;
    String s = v.asText(null);
    return (s != null && !s.isBlank()) ? s : null;
  }

  private static BigDecimal num(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) return null;
    if (v.isNumber()) return v.decimalValue();
    try {
      return new BigDecimal(v.asText());
    } catch (Exception e) {
      return null;
    }
  }

  private static Integer intNum(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) return null;
    if (v.isInt()) return v.intValue();
    try {
      return Integer.parseInt(v.asText());
    } catch (Exception e) {
      return null;
    }
  }
}
