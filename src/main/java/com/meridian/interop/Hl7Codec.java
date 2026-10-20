package com.meridian.interop;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.util.idgenerator.InMemoryIDGenerator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Hl7Codec {

    private static final DateTimeFormatter ACK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HapiContext context = new DefaultHapiContext();
    private final Parser parser;

    public Hl7Codec() {
        context.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
        context.getParserConfiguration().setIdGenerator(new InMemoryIDGenerator());
        parser = context.getPipeParser();
    }

    public ParsedResult parseResult(String raw) {
        try {
            Message message = parser.parse(raw);
            Terser terser = new Terser(message);
            String messageCode = text(terser.get("/MSH-9-1"));
            String trigger = text(terser.get("/MSH-9-2"));
            if (!"ORU".equals(messageCode) || !"R01".equals(trigger)) {
                throw new IllegalArgumentException("only ORU^R01 result messages are supported");
            }

            String orderPath = "/PATIENT_RESULT/ORDER_OBSERVATION";
            String orderId = firstText(
                    terser.get(orderPath + "/OBR-3-1"),
                    terser.get(orderPath + "/OBR-2-1"));
            if (orderId == null) {
                throw new IllegalArgumentException("OBR-3 or OBR-2 must contain the Meridian order id");
            }
            String observationPath = orderPath + "/OBSERVATION";
            String value = firstText(
                    terser.get(observationPath + "/OBX-5-1"),
                    terser.get(observationPath + "/OBX-5"));
            if (value == null) {
                throw new IllegalArgumentException("OBX-5 must contain the result value");
            }

            return new ParsedResult(
                    UUID.fromString(orderId),
                    value,
                    text(terser.get(observationPath + "/OBX-6-1")),
                    text(terser.get(observationPath + "/OBX-7-1")));
        } catch (HL7Exception | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid HL7 result message: " + exception.getMessage(), exception);
        }
    }

    public String successAck(String raw) {
        try {
            Message message = parser.parse(raw);
            return parser.encode(message.generateACK()).strip() + "\r";
        } catch (Exception exception) {
            return fallbackAck("AA", "accepted");
        }
    }

    public String errorAck(String raw, String detail) {
        try {
            Message message = parser.parse(raw);
            Message ack = message.generateACK(AcknowledgmentCode.AE, new HL7Exception(safe(detail)));
            return parser.encode(ack).strip() + "\r";
        } catch (Exception exception) {
            return fallbackAck("AE", detail);
        }
    }

    private String fallbackAck(String code, String detail) {
        String timestamp = ACK_TIME.format(LocalDateTime.now());
        String controlId = UUID.randomUUID().toString();
        return "MSH|^~\\&|MERIDIAN|MERIDIAN|UNKNOWN|UNKNOWN|" + timestamp
                + "||ACK^R01|" + controlId + "|P|2.5\r"
                + "MSA|" + code + "|" + controlId + "|" + safe(detail) + "\r";
    }

    private String firstText(String... values) {
        for (String value : values) {
            String cleaned = text(value);
            if (cleaned != null) {
                return cleaned;
            }
        }
        return null;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "request rejected";
        }
        return value.replace('|', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    public record ParsedResult(UUID orderId, String valueText, String unit, String referenceRange) {}
}
