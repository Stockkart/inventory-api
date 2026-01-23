package com.inventory.ocr.provider.impl;

import com.inventory.ocr.model.OcrCell;
import com.inventory.ocr.model.OcrResult;
import com.inventory.ocr.model.OcrTable;
import com.inventory.ocr.provider.OcrProvider;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AWS Textract implementation of OCR provider.
 * This class converts AWS Textract responses to the provider-agnostic OcrResult model.
 */
@Slf4j
public class AwsTextractOcrProvider implements OcrProvider {

  private final TextractClient textractClient;

  /**
   * Constructor for AWS Textract OCR provider.
   * 
   * @param textractClient the configured AWS Textract client
   */
  public AwsTextractOcrProvider(TextractClient textractClient) {
    this.textractClient = textractClient;
  }

  @Override
  public OcrResult analyzeDocument(byte[] imageBytes) throws IOException {
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

      // Convert Textract response to provider-agnostic model
      return convertToOcrResult(response);

    } catch (Exception e) {
      log.error("AWS Textract processing failed: {}", e.getMessage(), e);
      throw new IOException("Failed to process document with Textract: " + e.getMessage(), e);
    }
  }

  @Override
  public String getProviderName() {
    return "AWS_TEXTTRACT";
  }

  /**
   * Convert AWS Textract response to provider-agnostic OcrResult model.
   */
  private OcrResult convertToOcrResult(AnalyzeDocumentResponse response) {
    if (response.blocks() == null || response.blocks().isEmpty()) {
      return OcrResult.builder()
          .tables(new ArrayList<>())
          .fullText("")
          .pageCount(response.documentMetadata() != null ? response.documentMetadata().pages() : 1)
          .build();
    }

    // Create block map for quick lookup
    Map<String, Block> blockMap = response.blocks().stream()
        .collect(Collectors.toMap(Block::id, block -> block));

    // Extract tables
    List<OcrTable> tables = extractTables(response, blockMap);

    // Extract full text
    String fullText = extractFullText(response);

    return OcrResult.builder()
        .tables(tables)
        .fullText(fullText)
        .pageCount(response.documentMetadata() != null ? response.documentMetadata().pages() : 1)
        .providerMetadata("AWS Textract Model Version: " + 
            (response.analyzeDocumentModelVersion() != null ? response.analyzeDocumentModelVersion() : "unknown"))
        .build();
  }

  /**
   * Extract tables from Textract response.
   */
  private List<OcrTable> extractTables(AnalyzeDocumentResponse response, Map<String, Block> blockMap) {
    List<OcrTable> tables = new ArrayList<>();

    // Find all table blocks
    List<Block> tableBlocks = response.blocks().stream()
        .filter(block -> block.blockType() != null && 
                block.blockType().toString().equals("TABLE"))
        .collect(Collectors.toList());

    log.debug("Found {} tables in Textract response", tableBlocks.size());

    for (Block tableBlock : tableBlocks) {
      OcrTable table = extractTable(tableBlock, blockMap);
      if (table != null) {
        tables.add(table);
      }
    }

    return tables;
  }

  /**
   * Extract a single table from Textract table block.
   */
  private OcrTable extractTable(Block tableBlock, Map<String, Block> blockMap) {
    if (tableBlock.relationships() == null) {
      return null;
    }

    // Find all cells in this table
    List<Block> cells = new ArrayList<>();
    for (Relationship relationship : tableBlock.relationships()) {
      if (relationship.type() == RelationshipType.CHILD) {
        for (String childId : relationship.ids()) {
          Block child = blockMap.get(childId);
          if (child != null && child.blockType() != null && 
              child.blockType().toString().equals("CELL")) {
            cells.add(child);
          }
        }
      }
    }

    if (cells.isEmpty()) {
      return null;
    }

    // Organize cells by row and column
    Map<Integer, Map<Integer, OcrCell>> tableCells = new HashMap<>();
    int maxRow = 0;
    int maxCol = 0;

    for (Block cell : cells) {
      Integer rowIndex = cell.rowIndex();
      Integer colIndex = cell.columnIndex();

      if (rowIndex == null || colIndex == null) {
        continue;
      }

      maxRow = Math.max(maxRow, rowIndex);
      maxCol = Math.max(maxCol, colIndex);

      // Get cell text
      String cellText = getCellText(cell, blockMap);

      // Check if this is a header cell
      boolean isHeader = cell.entityTypes() != null &&
          cell.entityTypes().stream()
              .anyMatch(et -> et != null && et.toString().contains("COLUMN_HEADER"));

      OcrCell ocrCell = OcrCell.builder()
          .text(cellText)
          .rowIndex(rowIndex)
          .columnIndex(colIndex)
          .isHeader(isHeader)
          .confidence(cell.confidence() != null ? cell.confidence().doubleValue() : null)
          .build();

      tableCells.computeIfAbsent(rowIndex, k -> new HashMap<>()).put(colIndex, ocrCell);
    }

    return OcrTable.builder()
        .cells(tableCells)
        .rowCount(maxRow)
        .columnCount(maxCol)
        .confidence(tableBlock.confidence() != null ? tableBlock.confidence().doubleValue() : null)
        .build();
  }

  /**
   * Get text content from a cell block by following its relationships.
   */
  private String getCellText(Block cell, Map<String, Block> blockMap) {
    if (cell.text() != null && !cell.text().trim().isEmpty()) {
      return cell.text().trim();
    }

    // If no direct text, check child relationships
    if (cell.relationships() != null) {
      StringBuilder text = new StringBuilder();
      for (Relationship relationship : cell.relationships()) {
        if (relationship.type() == RelationshipType.CHILD) {
          for (String childId : relationship.ids()) {
            Block child = blockMap.get(childId);
            if (child != null && child.text() != null) {
              if (text.length() > 0) {
                text.append(" ");
              }
              text.append(child.text().trim());
            }
          }
        }
      }
      return text.toString().trim();
    }

    return "";
  }

  /**
   * Extract all text from the document for backward compatibility.
   */
  private String extractFullText(AnalyzeDocumentResponse response) {
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
    return text.toString().trim();
  }
}
