package com.inventory.taxation.service;

import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.excel.Gstr1TabWriter;
import com.inventory.taxation.excel.tabs.*;
import com.inventory.taxation.utils.constants.TaxationMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
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

  @Autowired
  private Gstr1OfflinePortalJsonService gstr1OfflinePortalJsonService;

  @Autowired
  private MetricsWrapper metrics;

  /**
   * Every sheet of the GSTR-1 workbook, in the order the portal lists them.
   *
   * <p>The amendment tabs sit next to the tab they amend, and the e-commerce set
   * follows the summaries, because that is the order the offline utility's own
   * template uses. The amendment and e-commerce tabs are the ones the platform
   * holds no data for; they are present with their headers and no rows, which is
   * what "nothing to declare this period" looks like to the portal.
   */
  private static final List<Gstr1TabWriter> TAB_WRITERS = List.of(
      new Gstr1B2bTabWriter(),
      new Gstr1B2baTabWriter(),
      new Gstr1B2clTabWriter(),
      new Gstr1B2claTabWriter(),
      new Gstr1B2csTabWriter(),
      new Gstr1B2csaTabWriter(),
      new Gstr1CdnrTabWriter(),
      new Gstr1CdnraTabWriter(),
      new Gstr1CdnurTabWriter(),
      new Gstr1CdnuraTabWriter(),
      new Gstr1ExpTabWriter(),
      new Gstr1ExpaTabWriter(),
      new Gstr1AtTabWriter(),
      new Gstr1AtaTabWriter(),
      new Gstr1AtadjTabWriter(),
      new Gstr1AtadjaTabWriter(),
      new Gstr1ExempTabWriter(),
      new Gstr1HsnB2bTabWriter(),
      new Gstr1HsnB2cTabWriter(),
      new Gstr1DocsTabWriter(),
      new Gstr1EcoTabWriter(),
      new Gstr1EcoaTabWriter(),
      new Gstr1Ecob2bTabWriter(),
      new Gstr1Ecourp2bTabWriter(),
      new Gstr1Ecob2cTabWriter(),
      new Gstr1Ecourp2cTabWriter(),
      new Gstr1Ecoab2bTabWriter(),
      new Gstr1Ecoab2cTabWriter(),
      new Gstr1Ecoaurp2bTabWriter(),
      new Gstr1Ecoaurp2cTabWriter()
  );

  public Gstr1ReportContext getReportData(String shopId, String period) {
    recordReport();
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

  /** GST offline utility / portal style JSON ({@code fp}, {@code b2b}, {@code b2cs}, {@code hsn}, …). */
  public byte[] generateOfflinePortalJson(String shopId, String period) {
    Gstr1ReportContext context = dataAggregator.buildContext(shopId, period);
    return gstr1OfflinePortalJsonService.toJsonUtf8(context);
  }

  private void recordReport() {
    metrics.record(
        TaxationMetricsConstants.REPORTS_TOTAL,
        1,
        "module",
        TaxationMetricsConstants.MODULE,
        "operation",
        "gstr1");
  }
}
