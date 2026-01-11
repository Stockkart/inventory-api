package com.inventory.ocr.service;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
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

  public OcrService() {
    this.tesseract = new Tesseract();
    
    // Set data path - Check TESSDATA_PREFIX environment variable first, then try common system paths
    String tessdataPrefix = System.getenv("TESSDATA_PREFIX");
    String datapath = null;
    
    if (tessdataPrefix != null && !tessdataPrefix.isEmpty()) {
      // Use TESSDATA_PREFIX if set (common in Docker containers)
      java.io.File tessdataDir = new java.io.File(tessdataPrefix);
      if (tessdataDir.exists() && tessdataDir.isDirectory()) {
        datapath = tessdataPrefix;
        log.info("Using TESSDATA_PREFIX for tessdata path: {}", tessdataPrefix);
      } else {
        log.warn("TESSDATA_PREFIX is set to '{}' but directory does not exist", tessdataPrefix);
      }
    }
    
    // If TESSDATA_PREFIX not set or invalid, try common system paths
    if (datapath == null) {
      String[] commonPaths = {
        "/usr/share/tesseract-ocr/5/tessdata",  // Tesseract 5.x (Debian/Ubuntu)
        "/usr/share/tesseract-ocr/4.00/tessdata", // Tesseract 4.x (Debian/Ubuntu)
        "/usr/share/tesseract-ocr/tessdata",     // Older versions
        "/usr/local/share/tessdata",              // macOS Homebrew
        System.getProperty("user.dir") + "/tessdata" // Custom project path
      };
      
      for (String path : commonPaths) {
        java.io.File dir = new java.io.File(path);
        if (dir.exists() && dir.isDirectory()) {
          datapath = path;
          log.info("Found tessdata at: {}", path);
          break;
        }
      }
    }
    
    // Set datapath if found, otherwise let Tesseract use system default
    if (datapath != null) {
      try {
        tesseract.setDatapath(datapath);
        log.info("Tesseract datapath set to: {}", datapath);
      } catch (Exception e) {
        log.warn("Failed to set datapath to '{}', using system default: {}", datapath, e.getMessage());
      }
    } else {
      log.info("Using system default tessdata path (Tesseract will auto-detect)");
    }
    
    // Set language (default is English)
    tesseract.setLanguage("eng");
    // Set OCR engine mode
    tesseract.setPageSegMode(1);
    // Set OCR engine mode to use LSTM neural network
    tesseract.setOcrEngineMode(1);
    
    log.info("Tesseract OCR service initialized");
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

