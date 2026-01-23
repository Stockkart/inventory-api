package com.inventory.ocr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.TextractException;

import java.io.IOException;
import java.util.List;

/**
 * Service for performing OCR (Optical Character Recognition) on images using AWS Textract.
 */
@Service
@Slf4j
public class OcrService {

  private final TextractClient textractClient;

  /**
   * Constructor that injects the configured Textract client.
   * 
   * @param textractClient the configured AWS Textract client
   */
  @Autowired
  public OcrService(TextractClient textractClient) {
    this.textractClient = textractClient;
  }

  /**
   * Analyze document and extract structured data (tables) from an image byte array.
   *
   * @param imageBytes the image file as byte array
   * @return AnalyzeDocumentResponse containing blocks with table data
   * @throws IOException if image cannot be read
   * @throws TextractException if OCR processing fails
   */
  public AnalyzeDocumentResponse analyzeDocument(byte[] imageBytes) throws IOException, TextractException {
    log.info("Starting AWS Textract analysis for image of size: {} bytes", imageBytes.length);

    try {
      // Create document from byte array
      Document document = Document.builder()
          .bytes(SdkBytes.fromByteArray(imageBytes))
          .build();

      // Build request with TABLES feature for invoice table extraction
      AnalyzeDocumentRequest request = AnalyzeDocumentRequest.builder()
          .document(document)
          .featureTypes(FeatureType.TABLES)
          .build();

      // Analyze document
      AnalyzeDocumentResponse response = textractClient.analyzeDocument(request);

      log.info("Textract analysis completed. Found {} blocks", 
          response.blocks() != null ? response.blocks().size() : 0);

      return response;
    } catch (TextractException e) {
      log.error("AWS Textract processing failed: {}", e.getMessage(), e);
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error during Textract processing: {}", e.getMessage(), e);
      throw new IOException("Failed to process document with Textract: " + e.getMessage(), e);
    }
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
    log.info("Extracting text using AWS Textract for image of size: {} bytes", imageBytes.length);

    try {
      AnalyzeDocumentResponse response = analyzeDocument(imageBytes);
      
      // Extract all text from blocks
      StringBuilder text = new StringBuilder();
      if (response.blocks() != null) {
        for (Block block : response.blocks()) {
          if (block.blockType() != null && 
              (block.blockType().toString().equals("LINE") || 
               block.blockType().toString().equals("WORD"))) {
            if (block.text() != null) {
              if (text.length() > 0) {
                text.append(" ");
              }
              text.append(block.text());
            }
          }
        }
      }

      String extractedText = text.toString().trim();
      log.info("Text extraction completed. Extracted text length: {}", extractedText.length());

      return extractedText;
    } catch (TextractException e) {
      log.error("AWS Textract processing failed: {}", e.getMessage(), e);
      throw new IOException("Failed to extract text: " + e.getMessage(), e);
    }
  }
}

