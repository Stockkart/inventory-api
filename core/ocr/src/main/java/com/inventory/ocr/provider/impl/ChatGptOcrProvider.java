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
 * Invoice parser using OpenAI Responses API with vision.
 * Sends image + prompt, parses JSON response directly to {@link ParsedInventoryItem}.
 */
@Slf4j
public class ChatGptOcrProvider implements OcrProvider {

  private final String model;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final ImagePreprocessor imagePreprocessor;

  private static final String PROMPT = """
      Extract ONLY product line items from the invoice image.
      
      Return ONLY JSON object exactly like:
      {"items":[{...},{...}]}
      
      Rules:
      - Missing fields => null.
      - customReminders MUST ALWAYS be [] (never null).
      - barcode must be null unless invoice explicitly shows Barcode/EAN/UPC.
      - name must include full product name with pack size/strength (do not shorten).
      
      Quantity (count):
      - count must come from the quantity field of the product row (Qty/Quantity/Units/Pack/etc).
      - Do NOT use serial number / line number as count.
      - If quantity is not given but pack detail like "3 x 56" exists, set count = 3*56.
      
      Dates:
      - expiryDate must come ONLY from expiry/exp field (not mfg date).
      - Use ISO UTC like 2027-10-01T00:00:00Z. If month-year only, use first day of month.
      
      Pricing:
      - maximumRetailPrice must come from MRP field.
        If Reduced MRP / discounted MRP exists, use Reduced MRP as maximumRetailPrice.
      - priceToRetail must come from retail selling price field (PTR / Price to Retail / Selling Price / Retail Price).
      - costPrice must come from unit purchase price field (Rate / Cost Price / PTS / Price to Stockist).
      - priceToRetail and costPrice must NEVER be taken from MRP or Reduced MRP.
      - Do NOT use taxable amount / SGST value / CGST value / totals as costPrice or priceToRetail.
      - If multiple unit price numbers exist in the same product row, choose:
        costPrice = lowest unit price,
        priceToRetail = next higher unit price,
        maximumRetailPrice = highest unit price (or Reduced MRP if present).
      
      Tax:
      - sgst/cgst must be rate only like "2.5".
      
      Other:
      - Do not guess or calculate values from totals. Copy values only from the same product row.
      """
  ;

  public ChatGptOcrProvider(String apiKey, String model, ImagePreprocessor imagePreprocessor) {
    this.model = model;
    this.restClient = RestClient.builder()
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.objectMapper = new ObjectMapper();
    this.imagePreprocessor = imagePreprocessor;
  }

  @Override
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    log.info("ChatGPT ({}) invoice parse, image size before preprocess: {} bytes", model, imageBytes.length);
    byte[] toSend = imagePreprocessor.preprocess(imageBytes);
    log.info("Image size after preprocess: {} bytes", toSend.length);

    String base64 = Base64.getEncoder().encodeToString(toSend);
    String dataUrl = "data:image/jpeg;base64," + base64;

    List<Map<String, Object>> content = List.of(
        Map.<String, Object>of("type", "input_text", "text", PROMPT),
        Map.<String, Object>of("type", "input_image", "image_url", dataUrl)
    );
    Map<String, Object> userMessage = Map.of("role", "user", "content", content);

    Map<String, Object> request = new HashMap<>();
    request.put("model", model);
    request.put("input", List.of(userMessage));
    request.put("max_output_tokens", OcrConstants.DEFAULT_MAX_OUTPUT_TOKENS_OPENAI);
    request.put("temperature", OcrConstants.DEFAULT_TEMPERATURE);

    String body = restClient.post()
        .uri(OcrConstants.OPENAI_API_URL)
        .body(request)
        .retrieve()
        .body(String.class);

    String text = extractOutputText(body);
    if (text == null || text.isBlank()) {
      log.warn("No output text from Responses API");
      return List.of();
    }

    return parseJsonToItems(text);
  }

  @Override
  public String getProviderName() {
    return "CHATGPT_" + model.toUpperCase().replace("-", "_").replace(".", "_");
  }

  private String extractOutputText(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode output = root.path("output");
      if (!output.isArray()) return null;
      for (JsonNode item : output) {
        if (!"message".equals(item.path("type").asText(null))) continue;
        JsonNode content = item.path("content");
        if (!content.isArray()) continue;
        for (JsonNode part : content) {
          if ("output_text".equals(part.path("type").asText(null))) {
            JsonNode t = part.path("text");
            return t.isTextual() ? t.asText() : null;
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to extract output text: {}", e.getMessage());
    }
    return null;
  }

  private List<ParsedInventoryItem> parseJsonToItems(String raw) {
    List<ParsedInventoryItem> items = new ArrayList<>();
    try {
      String json = raw.trim();
      if (json.startsWith("```")) {
        int start = json.indexOf('\n') + 1;
        int end = json.lastIndexOf("```");
        if (end > start) json = json.substring(start, end).trim();
      }
      JsonNode arr = objectMapper.readTree(json);
      if (!arr.isArray()) return items;
      for (JsonNode n : arr) {
        ParsedInventoryItem item = jsonToItem(n);
        if (item != null) items.add(item);
      }
    } catch (Exception e) {
      log.warn("Parse ChatGPT JSON failed: {}", e.getMessage());
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
    item.setPriceToRetail(num(n, "priceToRetail"));
    item.setAdditionalDiscount(num(n, "additionalDiscount"));
    item.setBusinessType(str(n, "businessType") != null ? str(n, "businessType") : "PHARMACEUTICAL");
    item.setLocation(str(n, "location"));
    item.setCount(intNum(n, "count"));
    item.setThresholdCount(intNum(n, "thresholdCount") != null ? intNum(n, "thresholdCount") : 10);
    item.setExpiryDate(str(n, "expiryDate"));
    item.setReminderAt(str(n, "reminderAt"));
    item.setHsn(str(n, "hsn"));
    item.setBatchNo(str(n, "batchNo"));
    item.setScheme(intNum(n, "scheme"));
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
    try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
  }

  private static Integer intNum(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) return null;
    if (v.isInt()) return v.intValue();
    try { return Integer.parseInt(v.asText()); } catch (Exception e) { return null; }
  }
}
