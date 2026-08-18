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
   * template uses. Tabs written by {@link Gstr1SchemaTabWriter} are the ones the
   * platform holds no data for; they are present with their headers and no rows,
   * which is what "nothing to declare this period" looks like to the portal.
   */
  private static final List<Gstr1TabWriter> TAB_WRITERS = List.of(
      new Gstr1B2bTabWriter(),
      Gstr1SchemaTabWriter.of("b2ba"),
      new Gstr1B2clTabWriter(),
      Gstr1SchemaTabWriter.of("b2cla"),
      new Gstr1B2csTabWriter(),
      Gstr1SchemaTabWriter.of("b2csa"),
      new Gstr1CdnrTabWriter(),
      Gstr1SchemaTabWriter.of("cdnra"),
      new Gstr1CdnurTabWriter(),
      Gstr1SchemaTabWriter.of("cdnura"),
      new Gstr1ExpTabWriter(),
      Gstr1SchemaTabWriter.of("expa"),
      new Gstr1AtTabWriter(),
      Gstr1SchemaTabWriter.of("ata"),
      new Gstr1AtadjTabWriter(),
      Gstr1SchemaTabWriter.of("atadja"),
      new Gstr1ExempTabWriter(),
      new Gstr1HsnB2bTabWriter(),
      new Gstr1HsnB2cTabWriter(),
      new Gstr1DocsTabWriter(),
      Gstr1SchemaTabWriter.of("eco"),
      Gstr1SchemaTabWriter.of("ecoa"),
      Gstr1SchemaTabWriter.of("ecob2b"),
      Gstr1SchemaTabWriter.of("ecourp2b"),
      Gstr1SchemaTabWriter.of("ecob2c"),
      Gstr1SchemaTabWriter.of("ecourp2c"),
      Gstr1SchemaTabWriter.of("ecoab2b"),
      Gstr1SchemaTabWriter.of("ecoab2c"),
      Gstr1SchemaTabWriter.of("ecoaurp2b"),
      Gstr1SchemaTabWriter.of("ecoaurp2c")
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
