package com.meridian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meridian.api.Hl7Controller;
import com.meridian.api.SecurityConfig;
import com.meridian.core.MeridianService;
import com.meridian.interop.Hl7Codec;
import com.meridian.interop.Hl7Codec.ParsedResult;
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

@WebMvcTest(Hl7Controller.class)
@Import(SecurityConfig.class)
class Hl7ControllerTest {

    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @MockBean
    private Hl7Codec codec;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void acceptsAnOruResultFromTheLab() throws Exception {
        given(codec.parseResult(anyString()))
                .willReturn(new ParsedResult(ORDER_ID, "13.2", "g/dL", "12.0-16.0"));
        given(codec.successAck(anyString())).willReturn("MSA|AA|MSG00001\r");

        mockMvc.perform(post("/api/interfaces/hl7")
                        .with(labUser())
                        .contentType(MediaType.parseMediaType("application/hl7-v2"))
                        .content("ORU"))
                .andExpect(status().isOk())
                .andExpect(content().string("MSA|AA|MSG00001\r"));

        verify(service).ingestHl7Result(
                eq(FACILITY_ID), eq("hl7:lab-user"), eq(ORDER_ID), eq("13.2"), eq("g/dL"), eq("12.0-16.0"));
    }

    @Test
    void rejectsTheInterfaceForFrontDesk() throws Exception {
        mockMvc.perform(post("/api/interfaces/hl7")
                        .with(frontDeskUser())
                        .contentType(MediaType.parseMediaType("application/hl7-v2"))
                        .content("ORU"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAnInvalidMessageWithAnErrorAck() throws Exception {
        given(codec.parseResult(anyString())).willThrow(new IllegalArgumentException("unsupported"));
        given(codec.errorAck(anyString(), anyString())).willReturn("MSA|AE|MSG00001\r");

        mockMvc.perform(post("/api/interfaces/hl7")
                        .with(labUser())
                        .contentType(MediaType.parseMediaType("application/hl7-v2"))
                        .content("ADT"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("MSA|AE|MSG00001\r"));

        verify(codec).errorAck(anyString(), any());
    }

    private static JwtRequestPostProcessor labUser() {
        return jwt()
                .jwt(token -> token
                        .subject("lab-user")
                        .claim("preferred_username", "lab-user")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("lab"))))
                .authorities(new SimpleGrantedAuthority("ROLE_LAB"));
    }

    private static JwtRequestPostProcessor frontDeskUser() {
        return jwt()
                .jwt(token -> token
                        .subject("frontdesk")
                        .claim("preferred_username", "frontdesk")
                        .claim("facility_id", FACILITY_ID.toString())
                        .claim("realm_access", Map.of("roles", List.of("front_desk"))))
                .authorities(new SimpleGrantedAuthority("ROLE_FRONT_DESK"));
    }
}
