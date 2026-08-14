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
 * Invoice parser using OpenAI Responses API with vision.
 * Sends image + layout-specific prompt, parses JSON response to {@link ParsedInventoryItem}.
 */
@Slf4j
public class ChatGptOcrProvider implements OcrProvider {

  private final String model;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final OcrResponseJsonParser jsonParser;
  private final ImagePreprocessor imagePreprocessor;

  public ChatGptOcrProvider(String apiKey, String model, ImagePreprocessor imagePreprocessor) {
    this.model = model;
    this.restClient = RestClient.builder()
        .defaultHeader("Authorization", "Bearer " + apiKey)
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
    log.info("ChatGPT ({}) invoice parse layout={}, image size before preprocess: {} bytes",
        model, resolved, imageBytes.length);
    byte[] toSend = imagePreprocessor.preprocess(imageBytes);
    log.info("Image size after preprocess: {} bytes", toSend.length);

    String base64 = Base64.getEncoder().encodeToString(toSend);
    String dataUrl = "data:image/jpeg;base64," + base64;

    List<Map<String, Object>> content = List.of(
        Map.<String, Object>of("type", "input_text", "text", resolved.promptText()),
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

    return jsonParser.parse(text);
  }

  @Override
  public String getProviderName() {
    return "CHATGPT_" + model.toUpperCase().replace("-", "_").replace(".", "_");
  }

  private String extractOutputText(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      JsonNode output = root.path("output");
      if (!output.isArray()) {
        return null;
      }
      for (JsonNode item : output) {
        if (!"message".equals(item.path("type").asText(null))) {
          continue;
        }
        JsonNode content = item.path("content");
        if (!content.isArray()) {
          continue;
        }
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
}
