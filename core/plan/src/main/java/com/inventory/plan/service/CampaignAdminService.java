package com.inventory.plan.service;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.SaleCampaign;
import com.inventory.plan.domain.repository.SaleCampaignRepository;
import com.inventory.plan.mapper.CampaignMapper;
import com.inventory.plan.rest.dto.request.CampaignActiveRequest;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import com.inventory.plan.rest.dto.response.AdminCampaignResponse;
import com.inventory.plan.validation.CampaignValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Platform-admin create, edit and on/off for sale campaigns. Every change writes an audit entry.
 * Edits are single findAndModify calls, so the audited "before" is exactly what was replaced.
 */
@Service
@Slf4j
public class CampaignAdminService {

  static final String TARGET_TYPE = "SALE_CAMPAIGN";

  @Autowired
  private SaleCampaignRepository saleCampaignRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private CampaignMapper campaignMapper;

  @Autowired
  private CampaignValidator campaignValidator;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  public List<AdminCampaignResponse> list() {
    Instant now = clock.instant();
    return saleCampaignRepository.findAll(Sort.by(Sort.Direction.DESC, "startsAt")).stream()
        .map(campaign -> toResponse(campaign, now))
        .toList();
  }

  public AdminCampaignResponse create(CampaignRequest request, String actorUserId) {
    campaignValidator.validateCreate(request);
    if (saleCampaignRepository.findByCode(request.getCode()).isPresent()) {
      throw new ValidationException("A campaign with code " + request.getCode() + " already exists");
    }
    Instant now = clock.instant();
    SaleCampaign campaign = campaignMapper.toEntity(request);
    campaign.setCreatedAt(now);
    campaign.setUpdatedAt(now);
    try {
      campaign = mongoTemplate.insert(campaign);
    } catch (DuplicateKeyException e) {
      throw new ValidationException("A campaign with code " + request.getCode() + " already exists");
    }
    audit(actorUserId, "CAMPAIGN_CREATED", campaign.getId(), null, snapshot(campaign), null);
    log.info("Campaign {} created by {}", campaign.getCode(), actorUserId);
    return toResponse(campaign, now);
  }

  public AdminCampaignResponse update(String id, CampaignRequest request, String actorUserId) {
    campaignValidator.validateUpdate(id, request);
    Instant now = clock.instant();
    Update update = new Update()
        .set("headline", request.getHeadline())
        .set("subtext", request.getSubtext())
        .set("upcomingHeadline", request.getUpcomingHeadline())
        .set("ctaLabel", request.getCtaLabel())
        .set("ctaPath", request.getCtaPath())
        .set("theme", request.getTheme())
        .set("startsAt", request.getStartsAt())
        .set("endsAt", request.getEndsAt())
        .set("announceFrom", request.getAnnounceFrom())
        .set("imminentThresholdDays", request.getImminentThresholdDays())
        .set("dismissible", request.isDismissible())
        .set("priority", request.getPriority())
        .set("updatedAt", now);
    SaleCampaign before = mongoTemplate.findAndModify(byId(id), update,
        FindAndModifyOptions.options().returnNew(false), SaleCampaign.class);
    if (before == null) {
      throw new ResourceNotFoundException("Campaign", "id", id);
    }
    SaleCampaign after = load(id);
    audit(actorUserId, "CAMPAIGN_UPDATED", id, snapshot(before), snapshot(after), null);
    return toResponse(after, now);
  }

  /** Switching to the current value is a no-op and writes no audit entry. */
  public AdminCampaignResponse setActive(String id, CampaignActiveRequest request, String actorUserId) {
    campaignValidator.validateActive(id, request);
    boolean active = request.getActive();
    Instant now = clock.instant();
    // A null active flag already means "on", so only an explicit false needs switching on.
    Criteria needsChange = active
        ? Criteria.where("active").is(false)
        : Criteria.where("active").ne(false);
    Query query = new Query(Criteria.where("_id").is(id).andOperator(needsChange));
    Update update = new Update().set("active", active).set("updatedAt", now);
    SaleCampaign before = mongoTemplate.findAndModify(query, update,
        FindAndModifyOptions.options().returnNew(false), SaleCampaign.class);
    SaleCampaign after = load(id);
    if (before != null) {
      audit(actorUserId, active ? "CAMPAIGN_ACTIVATED" : "CAMPAIGN_DEACTIVATED", id,
          snapshot(before), snapshot(after), request.getReason());
    }
    return toResponse(after, now);
  }

  private SaleCampaign load(String id) {
    return saleCampaignRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Campaign", "id", id));
  }

  private AdminCampaignResponse toResponse(SaleCampaign campaign, Instant now) {
    AdminCampaignResponse response = campaignMapper.toAdminResponse(campaign);
    boolean datesValid = campaign.getStartsAt() != null && campaign.getEndsAt() != null
        && campaign.getStartsAt().isBefore(campaign.getEndsAt());
    response.setState(datesValid ? CampaignService.stateOf(campaign, now) : null);
    return response;
  }

  private void audit(String actorUserId, String action, String targetId,
      Map<String, Object> before, Map<String, Object> after, String reason) {
    auditService.record(AuditEntry.builder()
        .actorUserId(actorUserId)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(targetId)
        .before(before)
        .after(after)
        .reason(reason)
        .source(AuditSource.ADMIN_UI)
        .build());
  }

  private static Query byId(String id) {
    return new Query(Criteria.where("_id").is(id));
  }

  private static Map<String, Object> snapshot(SaleCampaign campaign) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("code", campaign.getCode());
    map.put("headline", campaign.getHeadline());
    map.put("subtext", campaign.getSubtext());
    map.put("upcomingHeadline", campaign.getUpcomingHeadline());
    map.put("ctaLabel", campaign.getCtaLabel());
    map.put("ctaPath", campaign.getCtaPath());
    map.put("theme", campaign.getTheme() == null ? null : campaign.getTheme().name());
    map.put("startsAt", campaign.getStartsAt());
    map.put("endsAt", campaign.getEndsAt());
    map.put("announceFrom", campaign.getAnnounceFrom());
    map.put("imminentThresholdDays", campaign.getImminentThresholdDays());
    map.put("dismissible", campaign.isDismissible());
    map.put("priority", campaign.getPriority());
    map.put("active", !Boolean.FALSE.equals(campaign.getActive()));
    return map;
  }
}
