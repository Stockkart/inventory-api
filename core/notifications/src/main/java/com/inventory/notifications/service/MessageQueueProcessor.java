package com.inventory.notifications.service;

import com.inventory.notifications.adapter.ChannelAdapter;
import com.inventory.notifications.adapter.SendResult;
import com.inventory.notifications.constants.MessagingConstants;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import com.inventory.notifications.utils.MessagingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MessageQueueProcessor {

  @Autowired
  private OutboundMessageRepository outboundMessageRepository;

  @Autowired(required = false)
  private List<ChannelAdapter> adapters;

  private static final long DEFAULT_DISPATCH_INTERVAL_MS = 15000L;

  @org.springframework.beans.factory.annotation.Value("${messaging.dispatch-interval-ms:15000}")
  private String dispatchIntervalMsStr;

  @PostConstruct
  void logStartup() {
    long interval = parseDispatchIntervalMs();
    int adapterCount = adapters != null ? adapters.size() : 0;
    log.info("Message queue processor started. Interval: {} ms. Adapters: {}. " +
        "Enable email with messaging.email.enabled=true to process messages.",
        interval, adapterCount);
  }

  private long parseDispatchIntervalMs() {
    if (dispatchIntervalMsStr == null || dispatchIntervalMsStr.isBlank()) {
      return DEFAULT_DISPATCH_INTERVAL_MS;
    }
    try {
      return Long.parseLong(dispatchIntervalMsStr.trim());
    } catch (NumberFormatException e) {
      log.warn("Invalid messaging.dispatch-interval-ms '{}', using {}", dispatchIntervalMsStr, DEFAULT_DISPATCH_INTERVAL_MS);
      return DEFAULT_DISPATCH_INTERVAL_MS;
    }
  }

  @Scheduled(fixedDelayString = "${messaging.dispatch-interval-ms:15000}")
  public void processQueue() {
    Instant now = Instant.now();
    List<OutboundMessage> pending = outboundMessageRepository.findPendingForDispatch(
        MessageStatus.PENDING,
        MessagingConstants.MAX_RETRIES,
        now,
        PageRequest.of(0, MessagingConstants.DISPATCH_BATCH_SIZE)
    );

    if (pending == null || pending.isEmpty()) {
      return;
    }

    Map<MessageChannel, ChannelAdapter> adapterMap = adapters == null || adapters.isEmpty()
        ? Map.of()
        : adapters.stream().collect(java.util.stream.Collectors.toMap(ChannelAdapter::getChannel, a -> a));

    if (adapterMap.isEmpty()) {
      log.warn("{} pending message(s) in queue but no adapters enabled. " +
          "Set messaging.email.enabled=true and RESEND_API_KEY to send emails.", pending.size());
      return;
    }

    log.info("Message queue processor: processing {} pending messages", pending.size());

    for (OutboundMessage msg : pending) {
      try {
        ChannelAdapter adapter = adapterMap.get(msg.getChannel());
        if (adapter == null) {
          log.warn("No adapter for channel {} for message {}", msg.getChannel(), msg.getId());
          continue;
        }

        SendResult result = adapter.send(msg);

        if (result.isSuccess()) {
          msg.setStatus(MessageStatus.SENT);
          msg.setExternalId(result.getExternalId());
          msg.setSentAt(now);
          msg.setErrorMessage(null);
          outboundMessageRepository.save(msg);
          log.info("Message {} sent successfully", msg.getId());
        } else {
          handleFailure(msg, result.getErrorMessage(), now);
        }
      } catch (Exception e) {
        log.error("Error processing message {}: {}", msg.getId(), e.getMessage(), e);
        handleFailure(msg, e.getMessage(), now);
      }
    }
  }

  private void handleFailure(OutboundMessage msg, String errorMessage, Instant now) {
    int nextRetryCount = msg.getRetryCount() + 1;
    long backoffSeconds = MessagingUtils.computeBackoffSeconds(nextRetryCount);

    msg.setRetryCount(nextRetryCount);
    msg.setLastAttemptAt(now);
    msg.setNextRetryAt(now.plusSeconds(backoffSeconds));
    msg.setErrorMessage(errorMessage);

    if (nextRetryCount >= MessagingConstants.MAX_RETRIES) {
      msg.setStatus(MessageStatus.EXHAUSTED);
      log.warn("Message {} exhausted retries ({})", msg.getId(), nextRetryCount);
    }

    outboundMessageRepository.save(msg);
  }
}
