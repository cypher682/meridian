package com.meridian;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meridian.api.PharmacyController;
import com.meridian.api.SecurityConfig;
import com.meridian.core.MeridianService;
import com.meridian.core.Models.PharmacyDispense;
import com.meridian.core.Models.PharmacyItem;
import java.time.Instant;
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

@WebMvcTest(PharmacyController.class)
@Import(SecurityConfig.class)
class PharmacyControllerTest {

    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final UUID ENCOUNTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000456");
    private static final UUID PATIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000789");
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void createsAnItemForPharmacist() throws Exception {
        given(service.createPharmacyItem(FACILITY_ID, "pharmacist", "AMOX500", "Amoxicillin", "capsule", 20, 5))
                .willReturn(item(20));

        mockMvc.perform(post("/api/pharmacy/items")
                        .with(pharmacistUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"AMOX500","name":"Amoxicillin","unit":"capsule","stockQuantity":20,"reorderLevel":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("AMOX500"))
                .andExpect(jsonPath("$.stockQuantity").value(20));
    }

    @Test
    void rejectsItemCreationByClinician() throws Exception {
        mockMvc.perform(post("/api/pharmacy/items")
                        .with(clinicianUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"AMOX500\",\"name\":\"Amoxicillin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dispensesForPharmacist() throws Exception {
        given(service.dispenseMedication(FACILITY_ID, "pharmacist", ITEM_ID, ENCOUNTER_ID, 2))
                .willReturn(new PharmacyDispense(
                        UUID.randomUUID(), FACILITY_ID, ITEM_ID, ENCOUNTER_ID, PATIENT_ID, 2,
                        "pharmacist", NOW));

        mockMvc.perform(post("/api/pharmacy/dispenses")
                        .with(pharmacistUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM_ID + "\",\"encounterId\":\"" + ENCOUNTER_ID
                                + "\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.patientId").value(PATIENT_ID.toString()));
    }

    @Test
    void listsItemsForAnyAuthenticatedUser() throws Exception {
        given(service.listPharmacyItems(FACILITY_ID, null)).willReturn(List.of(item(20)));

        mockMvc.perform(get("/api/pharmacy/items").with(clinicianUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ITEM_ID.toString()));
    }

    private static PharmacyItem item(int stock) {
        return new PharmacyItem(
                ITEM_ID, FACILITY_ID, "AMOX500", "Amoxicillin", "capsule", stock, 5, NOW, NOW);
    }

    private static JwtRequestPostProcessor pharmacistUser() {
        return jwt()
                .jwt(token -> token
                        .subject("pharmacist")
                        .claim("preferred_username", "pharmacist")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("pharmacist"))))
                .authorities(new SimpleGrantedAuthority("ROLE_PHARMACIST"));
    }

    private static JwtRequestPostProcessor clinicianUser() {
        return jwt()
                .jwt(token -> token
                        .subject("clinician")
                        .claim("preferred_username", "clinician")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("clinician"))))
                .authorities(new SimpleGrantedAuthority("ROLE_CLINICIAN"));
    }
}
