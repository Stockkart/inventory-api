package com.inventory.taxation.gstin;

import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.GstinDirectory;
import com.inventory.common.gst.GstinRegistration;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.taxation.utils.constants.TaxationMetricsConstants;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The GSTIN registry: what the GST network has said about each GSTIN, kept so it is asked once.
 *
 * <p>Order of a lookup: offline format and check-character test (a typo never costs a call) →
 * the registry → the configured {@link GstinLookupProvider}, whose answer is saved for everyone.
 * A provider failure is reported as "unknown for now", never as an error to the caller.
 */
@Slf4j
@Service
public class GstinRegistryService implements GstinDirectory {

  private final GstinRecordRepository repository;
  private final GstinLookupProvider provider;
  private final MetricsWrapper metrics;

  public GstinRegistryService(GstinRecordRepository repository, GstinLookupProvider provider, MetricsWrapper metrics) {
    this.repository = repository;
    this.provider = provider;
    this.metrics = metrics;
  }

  /** The provider in use, so the lookup endpoint can say whether online verification is on. */
  public GstinLookupProvider provider() {
    return provider;
  }

  // ---- GstinDirectory (the view other modules get) ---------------------------------------------

  @Override
  public Optional<GstinRegistration> find(String raw) {
    return record(raw).map(GstinRecord::toRegistration);
  }

  @Override
  public Optional<GstinRegistration> lookup(String raw) {
    return lookupRecord(raw).map(GstinRecord::toRegistration);
  }

  @Override
  public Optional<GstinRegistration> refresh(String raw) {
    return refreshRecord(raw).map(GstinRecord::toRegistration);
  }

  // ---- full records (the lookup endpoint) ------------------------------------------------------

  /** What is on record, without asking the network. */
  public Optional<GstinRecord> record(String raw) {
    return Gstin.parse(raw).flatMap(g -> repository.findById(g.value()));
  }

  /** On record → that; otherwise ask the network once and keep the answer. */
  public Optional<GstinRecord> lookupRecord(String raw) {
    Optional<Gstin> parsed = Gstin.parse(raw);
    if (parsed.isEmpty()) {
      count("invalid");
      return Optional.empty();
    }
    Gstin gstin = parsed.get();
    Optional<GstinRecord> onRecord = repository.findById(gstin.value());
    if (onRecord.isPresent()) {
      count("registry_hit");
      return onRecord;
    }
    return fetchAndSave(gstin, null);
  }

  /** Ask the network again; on failure what was on record stays. */
  public Optional<GstinRecord> refreshRecord(String raw) {
    Optional<Gstin> parsed = Gstin.parse(raw);
    if (parsed.isEmpty()) {
      count("invalid");
      return Optional.empty();
    }
    Gstin gstin = parsed.get();
    return fetchAndSave(gstin, repository.findById(gstin.value()).orElse(null));
  }

  private Optional<GstinRecord> fetchAndSave(Gstin gstin, GstinRecord existing) {
    if (!provider.isConfigured()) {
      count("provider_unconfigured");
      return Optional.ofNullable(existing);
    }
    try {
      Optional<GstinRecord> fetched = provider.lookup(gstin);
      if (fetched.isEmpty()) {
        count("network_unknown");
        return Optional.empty();
      }
      GstinRecord record = fetched.get();
      if (existing != null && existing.getFetchedAt() != null) {
        record.setFetchedAt(existing.getFetchedAt()); // first fetch time survives a re-verify
      }
      record.setLastCheckedAt(Instant.now());
      repository.save(record);
      count("network_found");
      return Optional.of(record);
    } catch (GstinLookupException e) {
      count("network_error");
      log.warn("[gstin] lookup of {} failed via {}: {}", gstin, provider.name(), e.getMessage());
      return Optional.ofNullable(existing);
    } catch (RuntimeException e) {
      count("network_error");
      log.error("[gstin] unexpected failure looking up {} via {}", gstin, provider.name(), e);
      return Optional.ofNullable(existing);
    }
  }

  private void count(String outcome) {
    metrics.increment(
        TaxationMetricsConstants.GSTIN_LOOKUPS_TOTAL, 1,
        "module", TaxationMetricsConstants.MODULE,
        "provider", provider.name(),
        "outcome", outcome);
  }
}
