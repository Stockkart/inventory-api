package com.inventory.taxation.service;

import com.inventory.taxation.domain.gstr3b.Gstr3bReportContext;
import com.inventory.taxation.excel.Gstr3bExcelWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Builds GSTR-3B report (context + Excel download).
 */
@Service
@Slf4j
public class Gstr3bReportService {

  @Autowired
  private Gstr3bDataAggregator dataAggregator;

  public Gstr3bReportContext getReportData(String shopId, String period) {
    return dataAggregator.buildContext(shopId, period);
  }

  public byte[] generateExcel(String shopId, String period) throws IOException {
    Gstr3bReportContext context = dataAggregator.buildContext(shopId, period);
    try (Workbook workbook = new XSSFWorkbook()) {
      Gstr3bExcelWriter.write(workbook, context);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
