package com.meridian.api;

public record AmendResultRequest(
        String valueText,
        String unit,
        String referenceRange,
        String reason) {
}
