package com.meridian;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import com.meridian.api.LabController;
import com.meridian.api.SecurityConfig;
import com.meridian.core.MeridianService;

@WebMvcTest(LabController.class)
@Import(SecurityConfig.class)
class LabControllerTest {

    private static final UUID DEMO_FACILITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void listsOrdersForTheFacilityInTheToken() throws Exception {
        given(service.listLabOrders(DEMO_FACILITY_ID, "ordered", null)).willReturn(List.of());

        mockMvc.perform(get("/api/lab-orders")
                        .param("status", "ordered")
                        .with(labUser()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/lab-orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsReleaseByFrontDesk() throws Exception {
        mockMvc.perform(post("/api/lab-orders/{id}/release", UUID.randomUUID())
                        .with(frontDeskUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releasedBy\":\"front desk\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAmendmentByLabUser() throws Exception {
        mockMvc.perform(post("/api/lab-orders/{id}/amendments", UUID.randomUUID())
                        .with(labUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valueText":"13.5","unit":"g/dL","referenceRange":"12.0-16.0","reason":"correction"}
                                """))
                .andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor labUser() {
        return jwt()
                .jwt(token -> token
                        .subject("lab-user")
                        .claim("preferred_username", "lab-user")
                        .claim("facility_id", DEMO_FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("lab"))))
                .authorities(new SimpleGrantedAuthority("ROLE_LAB"));
    }

    private static JwtRequestPostProcessor frontDeskUser() {
        return jwt()
                .jwt(token -> token
                        .subject("frontdesk")
                        .claim("preferred_username", "frontdesk")
                        .claim("facility_id", DEMO_FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("front_desk"))))
                .authorities(new SimpleGrantedAuthority("ROLE_FRONT_DESK"));
    }
}
