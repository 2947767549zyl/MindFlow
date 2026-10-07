package com.mindflow.module.usage.dto;

import java.time.LocalDate;

public record DailyUsageStat(
        LocalDate recordDate,
        Long totalAmount,
        Long totalRequestCount
) {
}