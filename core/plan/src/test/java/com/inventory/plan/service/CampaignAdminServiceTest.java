package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.CampaignTheme;
import com.inventory.plan.domain.model.SaleCampaign;
import com.inventory.plan.domain.repository.SaleCampaignRepository;
import com.inventory.plan.mapper.CampaignMapper;
import com.inventory.plan.mapper.CampaignMapperImpl;
import com.inventory.plan.rest.dto.request.CampaignActiveRequest;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import com.inventory.plan.rest.dto.response.AdminCampaignResponse;
import com.inventory.plan.validation.CampaignValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class CampaignAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");

  @Mock
  private SaleCampaignRepository saleCampaignRepository;
  @Mock
  private MongoTemplate mongoTemplate;
  @Mock
  private AuditService auditService;
  @Spy
  private CampaignMapper campaignMapper = new CampaignMapperImpl();
  @Spy
  private CampaignValidator campaignValidator = new CampaignValidator();

  @InjectMocks
  private CampaignAdminService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static CampaignRequest.CampaignRequestBuilder request() {
    return CampaignRequest.builder()
        .code("MONSOON_2026")
        .headline("Monsoon sale")
        .theme(CampaignTheme.MONSOON)
        .startsAt(Instant.parse("2026-10-09T18:30:00Z"))
        .endsAt(Instant.parse("2026-10-19T18:30:00Z"))
        .dismissible(true);
  }

  private static SaleCampaign stored(Boolean active, String headline) {
    return SaleCampaign.builder()
        .id("c1").code("MONSOON_2026").headline(headline).theme(CampaignTheme.MONSOON)
        .startsAt(Instant.parse("2026-10-09T18:30:00Z"))
        .endsAt(Instant.parse("2026-10-19T18:30:00Z"))
        .active(active).build();
  }

  private AuditEntry capturedAudit() {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(captor.capture());
    return captor.getValue();
  }

  @Test
  void createInsertsActiveAndAudits() {
    when(saleCampaignRepository.findByCode("MONSOON_2026")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(SaleCampaign.class))).thenAnswer(inv -> {
      SaleCampaign c = inv.getArgument(0);
      c.setId("c1");
      return c;
    });

    AdminCampaignResponse response = service.create(request().build(), "admin1");

    assertThat(response.isActive()).isTrue();
    assertThat(response.getState()).isEqualTo(CampaignState.LIVE);
    assertThat(response.getCreatedAt()).isEqualTo(NOW);
    AuditEntry audit = capturedAudit();
    assertThat(audit.getAction()).isEqualTo("CAMPAIGN_CREATED");
    assertThat(audit.getActorUserId()).isEqualTo("admin1");
    assertThat(audit.getTargetType()).isEqualTo("SALE_CAMPAIGN");
    assertThat(audit.getTargetId()).isEqualTo("c1");
    assertThat(audit.getSource()).isEqualTo(AuditSource.ADMIN_UI);
    assertThat(audit.getBefore()).isNull();
    assertThat(audit.getAfter()).containsEntry("theme", "MONSOON").containsEntry("active", true);
  }

  @Test
  void createRejectsDuplicateCodeIncludingTheRace() {
    when(saleCampaignRepository.findByCode("MONSOON_2026")).thenReturn(Optional.of(stored(true, "x")));
    assertThatThrownBy(() -> service.create(request().build(), "admin1"))
        .isInstanceOf(ValidationException.class);

    when(saleCampaignRepository.findByCode("MONSOON_2026")).thenReturn(Optional.empty());
    when(mongoTemplate.insert(any(SaleCampaign.class))).thenThrow(new DuplicateKeyException("dup"));
    assertThatThrownBy(() -> service.create(request().build(), "admin1"))
        .isInstanceOf(ValidationException.class);
    verify(auditService, never()).record(any());
  }

  @Test
  void updateAuditsTheReplacedDocument() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(SaleCampaign.class))).thenReturn(stored(true, "Old"));
    when(saleCampaignRepository.findById("c1")).thenReturn(Optional.of(stored(true, "New")));

    service.update("c1", request().headline("New").build(), "admin1");

    AuditEntry audit = capturedAudit();
    assertThat(audit.getAction()).isEqualTo("CAMPAIGN_UPDATED");
    assertThat(audit.getBefore()).containsEntry("headline", "Old");
    assertThat(audit.getAfter()).containsEntry("headline", "New");
  }

  @Test
  void updateMissingCampaignIs404() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(SaleCampaign.class))).thenReturn(null);

    assertThatThrownBy(() -> service.update("nope", request().build(), "admin1"))
        .isInstanceOf(ResourceNotFoundException.class);
    verify(auditService, never()).record(any());
  }

  @Test
  void deactivateAuditsWithReason() {
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    when(mongoTemplate.findAndModify(query.capture(), any(Update.class),
        any(FindAndModifyOptions.class), eq(SaleCampaign.class))).thenReturn(stored(null, "h"));
    when(saleCampaignRepository.findById("c1")).thenReturn(Optional.of(stored(false, "h")));

    AdminCampaignResponse response = service.setActive("c1",
        new CampaignActiveRequest(false, "Pricing typo"), "admin1");

    assertThat(response.isActive()).isFalse();
    assertThat(response.getState()).isNull();
    assertThat(query.getValue().getQueryObject().toJson()).contains("\"$ne\": false");
    AuditEntry audit = capturedAudit();
    assertThat(audit.getAction()).isEqualTo("CAMPAIGN_DEACTIVATED");
    assertThat(audit.getReason()).isEqualTo("Pricing typo");
    assertThat(audit.getBefore()).containsEntry("active", true);
    assertThat(audit.getAfter()).containsEntry("active", false);
  }

  @Test
  void activatingAnAlreadyActiveCampaignWritesNoAudit() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(SaleCampaign.class))).thenReturn(null);
    when(saleCampaignRepository.findById("c1")).thenReturn(Optional.of(stored(null, "h")));

    AdminCampaignResponse response = service.setActive("c1", new CampaignActiveRequest(true, null), "admin1");

    assertThat(response.isActive()).isTrue();
    verify(auditService, never()).record(any());
  }

  @Test
  void toggleMissingCampaignIs404() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(SaleCampaign.class))).thenReturn(null);
    when(saleCampaignRepository.findById("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.setActive("nope", new CampaignActiveRequest(true, null), "admin1"))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
