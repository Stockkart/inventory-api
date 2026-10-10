package com.inventory.product.domain.model;

import com.inventory.documentservice.domain.DotMatrixDocumentKind;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintJobStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The durable record of one attempt to print a document on a dot-matrix printer. The bridge
 * keeps its own job history in memory only, capped and lost on restart; this is the record a
 * shop can rely on. Indexes are created by {@code PrintJobIndexMigration}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "print_jobs")
public class PrintJob {

  @Id
  private String id;

  private String shopId;

  private PrintDocumentSource source;

  /** The purchase, refund or vendor return id, per {@link #source}. */
  private String documentId;

  private DotMatrixDocumentKind documentKind;

  /** The invoice, estimate or note number printed on the document. */
  private String documentNumber;

  private PrintJobStatus status;

  private PrintAction action;

  private PrintActionReason reason;

  /** The bridge as the browser reported it when the job was created. */
  private PrintBridgeState bridgeState;

  private String bridgeVersion;

  private String selectedPrinter;

  /** The bridge's own job id ("j-N"), for matching against its window during a support call. */
  private String bridgeJobId;

  /** The printer's or the bridge's error text, when there was one. */
  private String error;

  private Instant createdAt;

  private String createdByUserId;

  private Instant completedAt;
}
