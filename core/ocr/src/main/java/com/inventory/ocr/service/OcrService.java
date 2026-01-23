package com.inventory.ocr.service;

import com.inventory.ocr.model.OcrResult;
import com.inventory.ocr.provider.OcrProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Service for performing OCR (Optical Character Recognition) on images.
 * This service uses the OcrProvider interface, allowing easy swapping of OCR implementations.
 */
@Service
@Slf4j
public class OcrService {

  private final OcrProvider ocrProvider;

  /**
   * Constructor that injects the configured OCR provider.
   * The provider is selected based on configuration (currently AWS Textract).
   * 
   * @param ocrProvider the configured OCR provider implementation
   */
  @Autowired
  public OcrService(OcrProvider ocrProvider) {
    this.ocrProvider = ocrProvider;
    log.info("OcrService initialized with provider: {}", ocrProvider.getProviderName());
  }

  /**
   * Analyze document and extract structured data (tables) from an image byte array.
   *
   * @param imageBytes the image file as byte array
   * @return OcrResult containing extracted tables and text
   * @throws IOException if image cannot be read or processing fails
   */
  public OcrResult analyzeDocument(byte[] imageBytes) throws IOException {
    log.info("Starting OCR analysis using provider: {} for image of size: {} bytes", 
        ocrProvider.getProviderName(), imageBytes.length);

    OcrResult result = ocrProvider.analyzeDocument(imageBytes);

    log.info("OCR analysis completed. Found {} tables, {} pages", 
        result.getTables() != null ? result.getTables().size() : 0,
        result.getPageCount());

    return result;
  }

  /**
   * Extract text from an image byte array (legacy method for backward compatibility).
   * This method extracts all text from the document.
   *
   * @param imageBytes the image file as byte array
   * @return extracted text as string
   * @throws IOException if image cannot be read
   */
  public String extractText(byte[] imageBytes) throws IOException {
    log.info("Extracting text using provider: {} for image of size: {} bytes", 
        ocrProvider.getProviderName(), imageBytes.length);

    OcrResult result = ocrProvider.analyzeDocument(imageBytes);
    String extractedText = result.getFullText() != null ? result.getFullText() : "";

    log.info("Text extraction completed. Extracted text length: {}", extractedText.length());

    return extractedText;
  }
}

