package com.inventory.reminders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.reminders.domain.repository.EventRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notification streams: dead ones are dropped, a shop cannot pile up streams, reconnects stay cheap. */
class EventServiceStreamsTest {

  private EventService service;
  private EventRepository events;

  @BeforeEach
  void setUp() {
    service = new EventService();
    events = mock(EventRepository.class);
    when(events.findByShopIdAndDeliveredFalseAndReminderIdNotNullOrderByTriggeredAtAsc(anyString())).thenReturn(List.of());
    ReflectionTestUtils.setField(service, "eventRepository", events);
    ReflectionTestUtils.setField(service, "sseEmitterTimeoutMs", 1_800_000L);
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<SseEmitter>> streams() {
    return (Map<String, List<SseEmitter>>) ReflectionTestUtils.getField(service, "emittersByShop");
  }

  /** A stream whose client has gone: every write fails, as Tomcat's does once the socket is closed. */
  static final class Gone extends SseEmitter {
    @Override
    public void send(SseEventBuilder builder) throws IOException {
      throw new IOException("Broken pipe");
    }
  }

  @Test
  void keepAliveDropsStreamsWhoseClientIsGoneAndKeepsTheRest() {
    SseEmitter live = service.subscribe("shop-1");
    streams().get("shop-1").add(new Gone());
    streams().get("shop-1").add(new Gone());
    assertThat(service.openEmitters()).isEqualTo(3);

    service.heartbeat();

    assertThat(service.openEmitters()).isEqualTo(1);
    assertThat(streams().get("shop-1")).containsExactly(live);
  }

  @Test
  void aShopWithNoLiveStreamsLeavesTheMap() {
    streams().put("shop-2", new java.util.concurrent.CopyOnWriteArrayList<>(List.of(new Gone())));

    service.heartbeat();

    assertThat(streams()).doesNotContainKey("shop-2");
  }

  @Test
  void aShopKeepsAtMostItsNewestStreams() {
    SseEmitter first = service.subscribe("shop-1");
    for (int i = 1; i < EventService.MAX_EMITTERS_PER_SHOP + 5; i++) {
      service.subscribe("shop-1");
    }

    assertThat(streams().get("shop-1")).hasSize(EventService.MAX_EMITTERS_PER_SHOP);
    assertThat(streams().get("shop-1")).doesNotContain(first);
  }

  @Test
  void reconnectingReadsOnlyReminderEvents() {
    service.subscribe("shop-1");

    verify(events).findByShopIdAndDeliveredFalseAndReminderIdNotNullOrderByTriggeredAtAsc("shop-1");
    verify(events, never()).findAll();
  }
}
