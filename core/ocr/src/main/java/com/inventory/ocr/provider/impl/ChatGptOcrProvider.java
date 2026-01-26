package com.inventory.ocr.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.provider.OcrProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Invoice parser using OpenAI Responses API (gpt-4o-mini) with vision.
 * Sends image + prompt, parses JSON response directly to {@link ParsedInventoryItem}.
 */
@Slf4j
public class ChatGptOcrProvider implements OcrProvider {

  private static final String MODEL = "gpt-4o-mini";
  private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

  /** Max length of the longest side (px). Larger images are scaled down. */
  private static final int MAX_DIMENSION = 1280;
  /** Min bytes to trigger compression (resize and/or re-encode). Smaller images are sent as-is. */
  private static final int MIN_BYTES_TO_COMPRESS = 50_000;
  /** JPEG quality when re-encoding (0.0–1.0). Lower = smaller files. */
  private static final float JPEG_QUALITY = 0.55f;

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
  ;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public ChatGptOcrProvider(String apiKey) {
    this.restClient = RestClient.builder()
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    log.info("ChatGPT (gpt-4o-mini) invoice parse, image size before: {} bytes", imageBytes.length);
    byte[] toSend = resizeIfNeeded(imageBytes);
    log.info("Image size after conversion: {} bytes", toSend.length);

    String base64 = Base64.getEncoder().encodeToString(toSend);
    String dataUrl = "data:image/jpeg;base64," + base64;

    List<Map<String, Object>> content = List.of(
        Map.<String, Object>of("type", "input_text", "text", PROMPT),
        Map.<String, Object>of("type", "input_image", "image_url", dataUrl)
    );
    Map<String, Object> userMessage = Map.of("role", "user", "content", content);

    Map<String, Object> request = Map.of(
        "model", MODEL,
        "input", List.of(userMessage),
        "max_output_tokens", 4096
    );

    String body = restClient.post()
        .uri(RESPONSES_URL)
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
    return "CHATGPT_4O_MINI";
  }

  /**
   * Resize and/or compress image: scale down if too large, re-encode as JPEG at lower quality.
   * Never returns a larger payload than the original; if compression would increase size, original is returned.
   */
  private byte[] resizeIfNeeded(byte[] imageBytes) {
    BufferedImage img;
    try {
      img = ImageIO.read(new ByteArrayInputStream(imageBytes));
    } catch (IOException e) {
      log.warn("Could not decode image for resize/compress: {}", e.getMessage());
      return imageBytes;
    }
    if (img == null) return imageBytes;

    int w = img.getWidth();
    int h = img.getHeight();
    int max = Math.max(w, h);
    boolean needResize = max > MAX_DIMENSION;
    boolean needCompress = imageBytes.length > MIN_BYTES_TO_COMPRESS;
    if (!needResize && !needCompress) return imageBytes;

    BufferedImage gray = toGrayscale(img);
    BufferedImage toEncode;
    if (needResize) {
      double scale = (double) MAX_DIMENSION / max;
      int nw = Math.max(1, (int) Math.round(w * scale));
      int nh = Math.max(1, (int) Math.round(h * scale));
      toEncode = new BufferedImage(nw, nh, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D g = toEncode.createGraphics();
      try {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(gray, 0, 0, nw, nh, null);
      } finally {
        g.dispose();
      }
    } else {
      toEncode = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D g = toEncode.createGraphics();
      try {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(gray, 0, 0, null);
      } finally {
        g.dispose();
      }
    }

    byte[] encoded = encodeJpeg(toEncode);
    if (encoded == null || encoded.length >= imageBytes.length) {
      log.debug("Compression would not reduce size ({} >= {}), using original", encoded == null ? 0 : encoded.length, imageBytes.length);
      return imageBytes;
    }
    return encoded;
  }

  /** Convert to greyscale via luminance; reduces JPEG size vs RGB. */
  private BufferedImage toGrayscale(BufferedImage src) {
    ColorConvertOp op = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
    return op.filter(src, null);
  }

  private byte[] encodeJpeg(BufferedImage img) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) return null;
    ImageWriter writer = writers.next();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      ImageWriteParam param = writer.getDefaultWriteParam();
      if (param.canWriteCompressed()) {
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
      }
      writer.write(null, new IIOImage(img, null, null), param);
    } catch (IOException e) {
      log.warn("Could not encode image as JPEG: {}", e.getMessage());
      return null;
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
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
    try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
  }

  private static Integer intNum(JsonNode n, String key) {
    JsonNode v = n.path(key);
    if (v.isNull() || v.isMissingNode()) return null;
    if (v.isInt()) return v.intValue();
    try { return Integer.parseInt(v.asText()); } catch (Exception e) { return null; }
  }
}
