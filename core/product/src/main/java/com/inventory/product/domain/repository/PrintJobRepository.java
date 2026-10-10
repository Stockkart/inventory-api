package com.inventory.product.domain.repository;

import com.inventory.product.domain.model.PrintJob;
import com.inventory.product.domain.model.enums.PrintDocumentSource;
import com.inventory.product.domain.model.enums.PrintJobStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PrintJobRepository extends MongoRepository<PrintJob, String> {

  Optional<PrintJob> findByIdAndShopId(String id, String shopId);

  Optional<PrintJob> findFirstByShopIdAndSourceAndDocumentIdAndStatusIn(
      String shopId,
      PrintDocumentSource source,
      String documentId,
      Collection<PrintJobStatus> statuses);
}
