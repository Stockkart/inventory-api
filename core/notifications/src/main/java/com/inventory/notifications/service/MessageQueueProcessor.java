package com.inventory.notifications.service;

import com.inventory.notifications.api.port.AttachmentPort;
import com.inventory.notifications.api.AttachmentRef;
import com.inventory.notifications.api.Attachment;
import com.inventory.notifications.adapter.ChannelAdapter;
import com.inventory.notifications.adapter.SendResult;
import com.inventory.notifications.constants.MessagingConstants;
import com.inventory.notifications.domain.model.MessageChannel;
import com.inventory.notifications.domain.model.MessageStatus;
import com.inventory.notifications.domain.model.OutboundMessage;
import com.inventory.notifications.domain.repository.OutboundMessageRepository;
import com.inventory.notifications.utils.MessagingUtils;
import com.inventory.notifications.utils.constants.NotificationMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class MessageQueueProcessor {

  @Autowired
  private OutboundMessageRepository outboundMessageRepository;

  @Autowired(required = false)
  private List<ChannelAdapter> adapters;

  /** Renders documents referenced by {@code OutboundMessage.attachmentRef}; none = HR absent. */
  @Autowired(required = false)
  private List<AttachmentPort> attachmentResolvers;

  @Autowired
  private MetricsWrapper metrics;

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
    // plain messages (as before: up to a batch) and, apart, the few whose attachment is rendered
    // this tick, so a queue of payslips never holds other emails back
    List<OutboundMessage> pending = new java.util.ArrayList<>();
    List<OutboundMessage> plain = outboundMessageRepository.findPendingPlainForDispatch(
        MessageStatus.PENDING,
        MessagingConstants.MAX_RETRIES,
        now,
        PageRequest.of(0, MessagingConstants.DISPATCH_BATCH_SIZE)
    );
    if (plain != null) {
      pending.addAll(plain);
    }
    List<OutboundMessage> withAttachment =
        outboundMessageRepository.findPendingWithAttachmentRefForDispatch(
            MessageStatus.PENDING,
            MessagingConstants.MAX_RETRIES,
            now,
            PageRequest.of(0, MessagingConstants.MAX_RENDERS_PER_TICK));
    if (withAttachment != null) {
      pending.addAll(withAttachment);
    }

    if (pending.isEmpty()) {
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

    // documents rendered at send time (payslips) cost ~0.1 s of CPU each on the single scheduler
    // thread, so a tick renders at most MAX_RENDERS_PER_TICK; the rest stay PENDING untouched
    // (no retry counted) and go out on a later tick (HR decision 1)
    int renders = 0;
    for (OutboundMessage msg : pending) {
      boolean rendered = false;
      try {
        ChannelAdapter adapter = adapterMap.get(msg.getChannel());
        if (adapter == null) {
          log.warn("No adapter for channel {} for message {}", msg.getChannel(), msg.getId());
          continue;
        }

        if (needsRender(msg)) {
          if (renders >= MessagingConstants.MAX_RENDERS_PER_TICK) {
            continue;
          }
          renders++;
          rendered = true;
          String renderError = renderAttachment(msg);
          if (renderError != null) {
            handleFailure(msg, renderError, now);
            recordSent(msg.getChannel(), "error");
            continue;
          }
        }

        SendResult result = adapter.send(msg);

        if (result.isSuccess()) {
          msg.setStatus(MessageStatus.SENT);
          msg.setExternalId(result.getExternalId());
          msg.setSentAt(now);
          msg.setErrorMessage(null);
          // the bytes were only needed for the send
          msg.setAttachments(null);
          outboundMessageRepository.save(msg);
          log.info("Message {} sent successfully", msg.getId());
          recordSent(msg.getChannel(), "success");
        } else {
          dropRendered(msg, rendered);
          handleFailure(msg, result.getErrorMessage(), now);
          recordSent(msg.getChannel(), "error");
        }
      } catch (Exception e) {
        log.error("Error processing message {}: {}", msg.getId(), e.getMessage(), e);
        dropRendered(msg, rendered);
        handleFailure(msg, e.getMessage(), now);
        recordSent(msg.getChannel(), "error");
      }
    }
  }

  /** A message with an attachment reference whose file has not been rendered for this send. */
  private static boolean needsRender(OutboundMessage msg) {
    return msg.getAttachmentRef() != null
        && (msg.getAttachments() == null || msg.getAttachments().isEmpty());
  }

  /**
   * Renders the referenced document into the message (in memory only).
   *
   * @return null when rendered, else the error to record
   */
  private String renderAttachment(OutboundMessage msg) {
    String kind = msg.getAttachmentRef().getKind();
    AttachmentPort resolver = attachmentResolvers == null ? null
        : attachmentResolvers.stream().filter(r -> r.supports(kind)).findFirst().orElse(null);
    if (resolver == null) {
      return "No attachment resolver for " + kind;
    }
    OutboundMessage.AttachmentRef ref = msg.getAttachmentRef();
    Optional<Attachment> file = resolver.resolve(
        new AttachmentRef(ref.getKind(), ref.getRunId(), ref.getLineId(), ref.getShopId()));
    if (file.isEmpty()) {
      return "Attachment " + kind + " is not available";
    }
    Attachment f = file.get();
    msg.setAttachments(List.of(new OutboundMessage.Attachment(f.fileName(), f.contentType(), f.base64())));
    return null;
  }

  /** Rendered bytes are never stored: a failed send renders again on its retry. */
  private static void dropRendered(OutboundMessage msg, boolean rendered) {
    if (rendered) {
      msg.setAttachments(null);
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

  private void recordSent(MessageChannel channel, String outcome) {
    String channelName = channel != null ? channel.name().toLowerCase() : "unknown";
    metrics.record(
        NotificationMetricsConstants.SENT_TOTAL,
        1,
        "module",
        NotificationMetricsConstants.MODULE,
        "channel",
        channelName,
        "outcome",
        outcome);
  }
}
