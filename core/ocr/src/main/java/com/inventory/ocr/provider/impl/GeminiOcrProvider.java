package com.inventory.ocr.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.ocr.constants.OcrConstants;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.preprocess.ImagePreprocessor;
import com.inventory.ocr.prompt.InvoicePricingLayout;
import com.inventory.ocr.provider.OcrProvider;
import com.inventory.ocr.util.OcrResponseJsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoice parser using Google Gemini API with vision capabilities.
 * Sends image + layout-specific prompt, parses JSON response to {@link ParsedInventoryItem}.
 */
@Slf4j
public class GeminiOcrProvider implements OcrProvider {

  private final String model;
  private final String apiUrl;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final OcrResponseJsonParser jsonParser;
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
    this.jsonParser = new OcrResponseJsonParser(this.objectMapper);
    this.imagePreprocessor = imagePreprocessor;
  }

  @Override
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes, InvoicePricingLayout layout)
      throws IOException {
    InvoicePricingLayout resolved = InvoicePricingLayout.orDefault(layout);
    log.info("Gemini ({}) invoice parse layout={}, image size before preprocess: {} bytes",
        model, resolved, imageBytes.length);
    byte[] toSend = imagePreprocessor.preprocess(imageBytes);
    log.info("Image size after preprocess: {} bytes", toSend.length);

    String base64 = Base64.getEncoder().encodeToString(toSend);

    Map<String, Object> inlineData = new HashMap<>();
    inlineData.put("mime_type", "image/jpeg");
    inlineData.put("data", base64);

    List<Map<String, Object>> parts = List.of(
        Map.of("inline_data", inlineData),
        Map.of("text", resolved.promptText())
    );

    Map<String, Object> generationConfig = new HashMap<>();
    generationConfig.put("temperature", OcrConstants.DEFAULT_TEMPERATURE);
    generationConfig.put("maxOutputTokens", OcrConstants.DEFAULT_MAX_OUTPUT_TOKENS_GEMINI);
    generationConfig.put("responseMimeType", "application/json");

    Map<String, Object> request = new HashMap<>();
    request.put("contents", List.of(Map.of("parts", parts)));
    request.put("generationConfig", generationConfig);

    String body = restClient.post()
        .uri(apiUrl + "?key=" + apiKey)
        .body(request)
        .retrieve()
        .body(String.class);

    log.debug("Gemini API response : {}", body);

    String text = extractOutputText(body);
    if (text == null || text.isBlank()) {
      log.warn("No output text from Gemini API");
      return List.of();
    }

    return jsonParser.parse(text);
  }

  @Override
  public String getProviderName() {
    return "GEMINI_" + model.toUpperCase().replace("-", "_").replace(".", "_");
  }

  /**
   * Extracts the text content from Gemini API response.
   */
  private String extractOutputText(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode candidates = root.path("candidates");
      if (!candidates.isArray() || candidates.isEmpty()) {
        return null;
      }

      JsonNode firstCandidate = candidates.get(0);
      JsonNode content = firstCandidate.path("content");
      JsonNode parts = content.path("parts");

      if (!parts.isArray() || parts.isEmpty()) {
        return null;
      }

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
}
