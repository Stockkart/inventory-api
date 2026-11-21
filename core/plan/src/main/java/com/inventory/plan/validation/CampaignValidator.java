package com.inventory.plan.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.rest.dto.request.CampaignActiveRequest;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CampaignValidator {

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{3,40}$");
  /** App-relative only: blocks absolute URLs and protocol-relative //host links. */
  private static final Pattern CTA_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/_\\-]*$");
  private static final int MAX_HEADLINE = 120;
  private static final int MAX_SUBTEXT = 200;
  private static final int MAX_CTA_LABEL = 30;
  private static final int MAX_THRESHOLD_DAYS = 30;
  private static final int MAX_PRIORITY = 100;

  public void validateCreate(CampaignRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getCode() == null || !CODE.matcher(request.getCode()).matches()) {
      throw new ValidationException("Code must be 3-40 characters of A-Z, 0-9 or _");
    }
    validateContent(request);
  }

  public void validateUpdate(String id, CampaignRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Campaign ID is required");
    }
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    validateContent(request);
  }

  public void validateActive(String id, CampaignActiveRequest request) {
    if (!StringUtils.hasText(id)) {
      throw new ValidationException("Campaign ID is required");
    }
    if (request == null || request.getActive() == null) {
      throw new ValidationException("active is required");
    }
  }

  private void validateContent(CampaignRequest request) {
    requireText(request.getHeadline(), "Headline", MAX_HEADLINE);
    optionalText(request.getUpcomingHeadline(), "Upcoming headline", MAX_HEADLINE);
    optionalText(request.getSubtext(), "Subtext", MAX_SUBTEXT);
    optionalText(request.getCtaLabel(), "CTA label", MAX_CTA_LABEL);
    if (StringUtils.hasText(request.getCtaLabel()) != StringUtils.hasText(request.getCtaPath())) {
      throw new ValidationException("CTA label and CTA path go together");
    }
    if (StringUtils.hasText(request.getCtaPath()) && !CTA_PATH.matcher(request.getCtaPath()).matches()) {
      throw new ValidationException("CTA path must be an app path such as /plans");
    }
    if (request.getTheme() == null) {
      throw new ValidationException("Theme is required");
    }
    if (request.getStartsAt() == null || request.getEndsAt() == null) {
      throw new ValidationException("Start and end are required");
    }
    if (!request.getStartsAt().isBefore(request.getEndsAt())) {
      throw new ValidationException("Start must be before end");
    }
    if (request.getAnnounceFrom() != null && request.getAnnounceFrom().isAfter(request.getStartsAt())) {
      throw new ValidationException("Announce date cannot be after the start");
    }
    Integer threshold = request.getImminentThresholdDays();
    if (threshold != null && (threshold < 0 || threshold > MAX_THRESHOLD_DAYS)) {
      throw new ValidationException("Imminent threshold must be 0-" + MAX_THRESHOLD_DAYS + " days");
    }
    if (request.getPriority() < 0 || request.getPriority() > MAX_PRIORITY) {
      throw new ValidationException("Priority must be 0-" + MAX_PRIORITY);
    }
  }

  private static void requireText(String value, String field, int max) {
    if (!StringUtils.hasText(value)) {
      throw new ValidationException(field + " is required");
    }
    optionalText(value, field, max);
  }

  private static void optionalText(String value, String field, int max) {
    if (value != null && value.length() > max) {
      throw new ValidationException(field + " must be at most " + max + " characters");
    }
  }
}
