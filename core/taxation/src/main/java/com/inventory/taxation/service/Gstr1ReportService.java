package com.inventory.taxation.service;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.tabs.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Builds GSTR-1 report (context + Excel download).
 */
@Service
@Slf4j
public class Gstr1ReportService {

  @Autowired
  private Gstr1DataAggregator dataAggregator;

  private static final List<Gstr1TabWriter> TAB_WRITERS = List.of(
      new Gstr1B2bTabWriter(),
      new Gstr1B2clTabWriter(),
      new Gstr1B2csTabWriter(),
      new Gstr1CdnrTabWriter(),
      new Gstr1CdnurTabWriter(),
      new Gstr1ExpTabWriter(),
      new Gstr1AtTabWriter(),
      new Gstr1AtadjTabWriter(),
      new Gstr1ExempTabWriter(),
      new Gstr1HsnB2bTabWriter(),
      new Gstr1HsnB2cTabWriter(),
      new Gstr1DocsTabWriter()
  );

  public Gstr1ReportContext getReportData(String shopId, String period) {
    return dataAggregator.buildContext(shopId, period);
  }

  public byte[] generateExcel(String shopId, String period) throws IOException {
    Gstr1ReportContext context = dataAggregator.buildContext(shopId, period);
    try (Workbook workbook = new XSSFWorkbook()) {
      for (Gstr1TabWriter writer : TAB_WRITERS) {
        writer.write(workbook, context);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
