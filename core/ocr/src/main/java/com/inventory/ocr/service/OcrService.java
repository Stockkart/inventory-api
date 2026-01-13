package com.inventory.ocr.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Service for performing OCR (Optical Character Recognition) on images.
 */
@Service
@Slf4j
public class OcrService {

  private final Tesseract tesseract;

  /**
   * Constructor that injects the configured Tesseract bean.
   * 
   * @param tesseract the configured Tesseract instance from OcrConfig
   */
  @Autowired
  public OcrService(Tesseract tesseract) {
    this.tesseract = tesseract;
  }

  /**
   * Extract text from an image byte array.
   *
   * @param imageBytes the image file as byte array
   * @return extracted text as string
   * @throws IOException if image cannot be read
   * @throws TesseractException if OCR processing fails
   */
  public String extractText(byte[] imageBytes) throws IOException, TesseractException {
    log.info("Starting OCR processing for image of size: {} bytes", imageBytes.length);

    // Convert byte array to BufferedImage
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));

    if (image == null) {
      throw new IOException("Unable to read image from provided bytes");
    }

    // Perform OCR
    String extractedText = tesseract.doOCR(image);

    log.info("OCR processing completed. Extracted text length: {}", extractedText != null ? extractedText.length() : 0);

    return extractedText != null ? extractedText.trim() : "";
  }
}

