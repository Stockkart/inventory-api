package com.inventory.product.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.product.rest.dto.ocr.OcrResponse;
import com.inventory.ocr.service.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Controller for OCR (Optical Character Recognition) operations.
 */
@RestController
@RequestMapping("/api/v1/ocr")
@Slf4j
public class OcrController {

  @Autowired
  private OcrService ocrService;

  /**
   * Extract text from an uploaded image.
   *
   * @param image the image file to process
   * @return extracted text as string
   */
  @PostMapping(value = "/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<OcrResponse>> extractText(
      @RequestParam("image") MultipartFile image) {
    log.info("Received OCR request for image: {}, size: {} bytes", 
        image.getOriginalFilename(), image.getSize());

    // Validate file
    if (image.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.error("Image file is empty"));
    }

    // Validate content type
    String contentType = image.getContentType();
    if (contentType == null || 
        (!contentType.startsWith("image/") && !contentType.equals("application/octet-stream"))) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.error("File must be an image"));
    }

    try {
      // Extract text using OCR service
      byte[] imageBytes = image.getBytes();
      String extractedText = ocrService.extractText(imageBytes);

      OcrResponse response = new OcrResponse(extractedText);

      log.info("OCR processing completed successfully. Extracted text length: {}", 
          extractedText.length());

      return ResponseEntity.ok(ApiResponse.success(response));
    } catch (IOException e) {
      log.error("Error reading image file: {}", e.getMessage(), e);
      return ResponseEntity.badRequest()
          .body(ApiResponse.error("Error reading image file: " + e.getMessage()));
    } catch (Exception e) {
      log.error("Unexpected error during OCR processing: {}", e.getMessage(), e);
      return ResponseEntity.internalServerError()
          .body(ApiResponse.error("OCR processing failed: " + e.getMessage()));
    }
  }
}

