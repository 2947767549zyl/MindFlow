package com.mindflow.module.usage.dto;

import java.time.LocalDate;

public record DailyReqCountStat(
        LocalDate recordDate,
        Long totalRequestCount
) {
}