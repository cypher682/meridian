package com.meridian.api;

import java.util.UUID;

public record CreateLabOrderRequest(UUID encounterId, String testCode) {
}
