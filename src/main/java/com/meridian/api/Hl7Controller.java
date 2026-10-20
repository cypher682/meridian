package com.meridian.api;

import com.meridian.core.MeridianService;
import com.meridian.interop.Hl7Codec;
import com.meridian.interop.Hl7Codec.ParsedResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Hl7Controller {

    private static final MediaType HL7 = MediaType.parseMediaType("application/hl7-v2");

    private final MeridianService service;
    private final Hl7Codec codec;

    public Hl7Controller(MeridianService service, Hl7Codec codec) {
        this.service = service;
        this.codec = codec;
    }

    @PostMapping(
            path = "/api/interfaces/hl7",
            consumes = {"application/hl7-v2", "text/plain"})
    @PreAuthorize("hasAnyRole('LAB', 'ADMIN')")
    public ResponseEntity<String> receive(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody String body) {
        ParsedResult parsed;
        try {
            parsed = codec.parseResult(body);
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().contentType(HL7).body(codec.errorAck(body, exception.getMessage()));
        }

        try {
            ApiScope.RequestScope scope = ApiScope.from(jwt);
            service.ingestHl7Result(
                    scope.facilityId(),
                    "hl7:" + scope.actorId(),
                    parsed.orderId(),
                    parsed.valueText(),
                    parsed.unit(),
                    parsed.referenceRange());
            return ResponseEntity.ok().contentType(HL7).body(codec.successAck(body));
        } catch (RuntimeException exception) {
            return ResponseEntity.ok().contentType(HL7).body(codec.errorAck(body, exception.getMessage()));
        }
    }
}
