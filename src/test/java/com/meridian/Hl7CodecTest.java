package com.meridian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meridian.interop.Hl7Codec;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hl7CodecTest {

    private final Hl7Codec codec = new Hl7Codec();

    private String message(UUID orderId) {
        return "MSH|^~\\&|ANALYZER|LAB|MERIDIAN|CLINIC|20261020120000||ORU^R01|MSG00001|P|2.5\r"
                + "PID|1||patient-1||Smoke^Sana\r"
                + "OBR|1||" + orderId + "|CBC^Complete blood count|||20261020115500\r"
                + "OBX|1|NM|HGB^Hemoglobin||13.2|g/dL|12.0-16.0|N|||F\r";
    }

    @Test
    void parsesAnOruResult() {
        UUID orderId = UUID.randomUUID();

        Hl7Codec.ParsedResult result = codec.parseResult(message(orderId));

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.valueText()).isEqualTo("13.2");
        assertThat(result.unit()).isEqualTo("g/dL");
        assertThat(result.referenceRange()).isEqualTo("12.0-16.0");
    }

    @Test
    void returnsAnAcceptedAck() {
        String ack = codec.successAck(message(UUID.randomUUID()));

        assertThat(ack).contains("MSA|AA|");
    }

    @Test
    void rejectsUnsupportedMessageTypes() {
        String raw = message(UUID.randomUUID()).replace("ORU^R01", "ADT^A01");

        assertThatThrownBy(() -> codec.parseResult(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORU^R01");
    }
}
