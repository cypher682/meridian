package com.meridian;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.meridian.api.LabController;
import com.meridian.core.MeridianService;

@WebMvcTest(LabController.class)
class LabControllerTest {

    private static final UUID DEMO_FACILITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MeridianService service;

    @Test
    void listsOrdersForTheDefaultFacility() throws Exception {
        given(service.listLabOrders(DEMO_FACILITY_ID, "ordered", null)).willReturn(List.of());

        mockMvc.perform(get("/api/lab-orders").param("status", "ordered"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
