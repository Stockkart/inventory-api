package com.inventory.ocr.constants;

/**
 * Constants for OCR providers including default model names and API endpoints.
 */
public final class OcrConstants {

  private OcrConstants() {
    // Prevent instantiation
  }

  // ========================
  // OpenAI / ChatGPT
  // ========================
  public static final String OPENAI_DEFAULT_MODEL = "gpt-4.1";
  public static final String OPENAI_API_URL = "https://api.openai.com/v1/responses";

  // ========================
  // Google Gemini
  // ========================
  public static final String GEMINI_DEFAULT_MODEL = "gemini-2.5-flash";
  public static final String GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

  // ========================
  // Provider Names
  // ========================
  public static final String PROVIDER_AWS_TEXTRACT = "aws-textract";
  public static final String PROVIDER_CHATGPT = "chatgpt";
  public static final String PROVIDER_GEMINI = "gemini";

  // ========================
  // Default Values
  // ========================
  public static final int DEFAULT_MAX_OUTPUT_TOKENS_OPENAI = 4096;
  public static final int DEFAULT_MAX_OUTPUT_TOKENS_GEMINI = 8192;
  public static final int DEFAULT_TEMPERATURE = 0;
}
