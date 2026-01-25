package com.inventory.ocr.provider.impl;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.model.OcrCell;
import com.inventory.ocr.model.OcrTable;
import com.inventory.ocr.provider.OcrProvider;
import com.inventory.ocr.service.TableToItemsParser;
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
 * AWS Textract implementation. Extracts tables from the document, then parses them
 * to {@link ParsedInventoryItem} via {@link TableToItemsParser}.
 */
@Slf4j
public class AwsTextractOcrProvider implements OcrProvider {

  private final TextractClient textractClient;
  private final TableToItemsParser tableParser;

  public AwsTextractOcrProvider(TextractClient textractClient) {
    this.textractClient = textractClient;
    this.tableParser = new TableToItemsParser();
  }

  @Override
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    log.info("AWS Textract invoice parse, image size: {} bytes", imageBytes.length);

    Document document = Document.builder()
        .bytes(SdkBytes.fromByteArray(imageBytes))
        .build();
    AnalyzeDocumentRequest request = AnalyzeDocumentRequest.builder()
        .document(document)
        .featureTypes(FeatureType.TABLES)
        .build();

    AnalyzeDocumentResponse response = textractClient.analyzeDocument(request);
    log.info("Textract done, {} blocks", response.blocks() != null ? response.blocks().size() : 0);

    List<OcrTable> tables = extractTables(response);
    return tableParser.parse(tables);
  }

  @Override
  public String getProviderName() {
    return "AWS_TEXTTRACT";
  }

  private List<OcrTable> extractTables(AnalyzeDocumentResponse response) {
    if (response.blocks() == null || response.blocks().isEmpty()) {
      return List.of();
    }
    Map<String, Block> blockMap = response.blocks().stream()
        .collect(Collectors.toMap(Block::id, b -> b));

    List<OcrTable> tables = new ArrayList<>();
    List<Block> tableBlocks = response.blocks().stream()
        .filter(b -> b.blockType() != null && "TABLE".equals(b.blockType().toString()))
        .collect(Collectors.toList());

    for (Block tb : tableBlocks) {
      OcrTable t = buildTable(tb, blockMap);
      if (t != null) tables.add(t);
    }
    return tables;
  }

  private OcrTable buildTable(Block tableBlock, Map<String, Block> blockMap) {
    if (tableBlock.relationships() == null) return null;
    List<Block> cells = new ArrayList<>();
    for (Relationship rel : tableBlock.relationships()) {
      if (rel.type() != RelationshipType.CHILD) continue;
      for (String id : rel.ids()) {
        Block b = blockMap.get(id);
        if (b != null && b.blockType() != null && "CELL".equals(b.blockType().toString()))
          cells.add(b);
      }
    }
    if (cells.isEmpty()) return null;

    Map<Integer, Map<Integer, OcrCell>> tableCells = new HashMap<>();
    int maxRow = 0, maxCol = 0;
    for (Block cell : cells) {
      Integer ri = cell.rowIndex(), ci = cell.columnIndex();
      if (ri == null || ci == null) continue;
      maxRow = Math.max(maxRow, ri);
      maxCol = Math.max(maxCol, ci);
      String text = cellText(cell, blockMap);
      boolean isHeader = cell.entityTypes() != null &&
          cell.entityTypes().stream().anyMatch(e -> e != null && e.toString().contains("COLUMN_HEADER"));
      OcrCell oc = OcrCell.builder()
          .text(text)
          .rowIndex(ri)
          .columnIndex(ci)
          .isHeader(isHeader)
          .confidence(cell.confidence() != null ? cell.confidence().doubleValue() : null)
          .build();
      tableCells.computeIfAbsent(ri, k -> new HashMap<>()).put(ci, oc);
    }
    return OcrTable.builder()
        .cells(tableCells)
        .rowCount(maxRow)
        .columnCount(maxCol)
        .confidence(tableBlock.confidence() != null ? tableBlock.confidence().doubleValue() : null)
        .build();
  }

  private String cellText(Block cell, Map<String, Block> blockMap) {
    if (cell.text() != null && !cell.text().trim().isEmpty())
      return cell.text().trim();
    if (cell.relationships() == null) return "";
    StringBuilder sb = new StringBuilder();
    for (Relationship rel : cell.relationships()) {
      if (rel.type() != RelationshipType.CHILD) continue;
      for (String id : rel.ids()) {
        Block b = blockMap.get(id);
        if (b != null && b.text() != null) {
          if (sb.length() > 0) sb.append(" ");
          sb.append(b.text().trim());
        }
      }
    }
    return sb.toString().trim();
  }
}
