package com.inventory.plan.rest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.plan.domain.model.CampaignState;
import com.inventory.plan.domain.model.CampaignTheme;
import com.inventory.plan.rest.dto.response.CampaignResponse;
import com.inventory.plan.service.CampaignService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Pins the JSON field names that platform/contracts CampaignResponse mirrors. */
@ExtendWith(MockitoExtension.class)
class CampaignControllerTest {

  @Mock
  private CampaignService campaignService;

  @InjectMocks
  private CampaignController controller;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
        .build();
  }

  @Test
  void activeContract() throws Exception {
    when(campaignService.findActive()).thenReturn(Optional.of(CampaignResponse.builder()
        .code("MONSOON_2026")
        .state(CampaignState.ENDING_SOON)
        .headline("Monsoon sale is live")
        .subtext("Annual plans from ₹4,999")
        .ctaLabel("See plans")
        .ctaPath("/plans")
        .theme(CampaignTheme.MONSOON)
        .startsAt(Instant.parse("2026-10-09T18:30:00Z"))
        .endsAt(Instant.parse("2026-10-19T18:30:00Z"))
        .dismissible(true)
        .serverNow(Instant.parse("2026-10-17T06:30:00Z"))
        .nextTransitionAt(Instant.parse("2026-10-19T18:30:00Z"))
        .build()));

    mvc.perform(get("/api/v1/campaigns/active"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.code").value("MONSOON_2026"))
        .andExpect(jsonPath("$.data.state").value("ENDING_SOON"))
        .andExpect(jsonPath("$.data.headline").value("Monsoon sale is live"))
        .andExpect(jsonPath("$.data.subtext").value("Annual plans from ₹4,999"))
        .andExpect(jsonPath("$.data.ctaLabel").value("See plans"))
        .andExpect(jsonPath("$.data.ctaPath").value("/plans"))
        .andExpect(jsonPath("$.data.theme").value("MONSOON"))
        .andExpect(jsonPath("$.data.startsAt").value("2026-10-09T18:30:00Z"))
        .andExpect(jsonPath("$.data.endsAt").value("2026-10-19T18:30:00Z"))
        .andExpect(jsonPath("$.data.dismissible").value(true))
        .andExpect(jsonPath("$.data.serverNow").value("2026-10-17T06:30:00Z"))
        .andExpect(jsonPath("$.data.nextTransitionAt").value("2026-10-19T18:30:00Z"));
  }

  @Test
  void nullDataWhenNoCampaign() throws Exception {
    when(campaignService.findActive()).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/campaigns/active"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").doesNotExist());
  }
}
