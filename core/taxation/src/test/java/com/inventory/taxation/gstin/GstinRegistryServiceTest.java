package com.inventory.taxation.gstin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.GstinRegistration;
import com.inventory.metrics.MetricsWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GstinRegistryServiceTest {

  static final String GSTIN = "27AAPFU0939F1ZV";

  GstinRecordRepository repository;
  GstinLookupProvider provider;
  GstinRegistryService service;
  Map<String, GstinRecord> store = new HashMap<>();

  @BeforeEach
  void setUp() {
    repository = mock(GstinRecordRepository.class);
    when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
    when(repository.save(any(GstinRecord.class))).thenAnswer(i -> {
      GstinRecord r = i.getArgument(0);
      store.put(r.getGstin(), r);
      return r;
    });
    provider = mock(GstinLookupProvider.class);
    when(provider.name()).thenReturn("test");
    when(provider.isConfigured()).thenReturn(true);
    service = new GstinRegistryService(repository, provider, new MetricsWrapper(new SimpleMeterRegistry()));
  }

  private GstinRecord networkRecord(String status) {
    return GstinRecord.builder().gstin(GSTIN).legalName("UNITED PHOSPHORUS").status(status).stateCode("27")
        .provider("test").fetchedAt(Instant.now()).lastCheckedAt(Instant.now()).build();
  }

  @Test
  void firstLookupAsksTheNetworkOnceAndLaterOnesReadTheRegistry() {
    when(provider.lookup(any(Gstin.class))).thenReturn(Optional.of(networkRecord("Active")));

    Optional<GstinRegistration> first = service.lookup(GSTIN);
    Optional<GstinRegistration> second = service.lookup(" 27aapfu0939f1zv ");
    Optional<GstinRegistration> third = service.find(GSTIN);

    assertTrue(first.isPresent() && second.isPresent() && third.isPresent());
    assertEquals("UNITED PHOSPHORUS", second.get().legalName());
    assertTrue(second.get().isActive());
    verify(provider, times(1)).lookup(any(Gstin.class));
  }

  @Test
  void anInvalidGstinNeverReachesTheNetwork() {
    assertTrue(service.lookup("27AAPFU0939F1ZW").isEmpty());
    assertTrue(service.lookup("rubbish").isEmpty());
    verify(provider, never()).lookup(any());
  }

  @Test
  void networkFailureIsUnknownNotAnError() {
    when(provider.lookup(any(Gstin.class))).thenThrow(new GstinLookupException("down"));
    assertTrue(service.lookup(GSTIN).isEmpty());
    assertTrue(store.isEmpty());
  }

  @Test
  void networkFailureOnRefreshKeepsWhatWasOnRecord() {
    store.put(GSTIN, networkRecord("Active"));
    when(provider.lookup(any(Gstin.class))).thenThrow(new GstinLookupException("down"));
    Optional<GstinRegistration> kept = service.refresh(GSTIN);
    assertTrue(kept.isPresent());
    assertEquals("Active", kept.get().status());
  }

  @Test
  void refreshReplacesTheStatusButKeepsTheFirstFetchTime() {
    Instant longAgo = Instant.parse("2025-01-01T00:00:00Z");
    GstinRecord old = networkRecord("Active");
    old.setFetchedAt(longAgo);
    store.put(GSTIN, old);
    when(provider.lookup(any(Gstin.class))).thenReturn(Optional.of(networkRecord("Cancelled")));

    GstinRegistration refreshed = service.refresh(GSTIN).orElseThrow();

    assertEquals("Cancelled", refreshed.status());
    assertFalse(refreshed.isActive());
    assertEquals(longAgo, store.get(GSTIN).getFetchedAt());
  }

  @Test
  void unconfiguredProviderMeansOfflineOnly() {
    when(provider.isConfigured()).thenReturn(false);
    assertTrue(service.lookup(GSTIN).isEmpty());
    verify(provider, never()).lookup(any());
  }
}
