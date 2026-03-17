package com.inventory.taxation.service;

import com.inventory.taxation.domain.gstr2.Gstr2ReportContext;
import com.inventory.taxation.excel.Gstr2TabWriter;
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
 * Builds GSTR-2 report (context + Excel download).
 */
@Service
@Slf4j
public class Gstr2ReportService {

  @Autowired
  private Gstr2DataAggregator dataAggregator;

  private static final List<Gstr2TabWriter> TAB_WRITERS = List.of(
      new Gstr2B2bTabWriter(),
      new Gstr2B2burTabWriter(),
      new Gstr2ImpsTabWriter(),
      new Gstr2ImpgTabWriter(),
      new Gstr2CdnrTabWriter(),
      new Gstr2CdnurTabWriter(),
      new Gstr2AtTabWriter(),
      new Gstr2AtadjTabWriter(),
      new Gstr2ExempTabWriter(),
      new Gstr2ItcrTabWriter(),
      new Gstr2HsnsumTabWriter()
  );

  public Gstr2ReportContext getReportData(String shopId, String period) {
    return dataAggregator.buildContext(shopId, period);
  }

  public byte[] generateExcel(String shopId, String period) throws IOException {
    Gstr2ReportContext context = dataAggregator.buildContext(shopId, period);
    try (Workbook workbook = new XSSFWorkbook()) {
      for (Gstr2TabWriter writer : TAB_WRITERS) {
        writer.write(workbook, context);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
