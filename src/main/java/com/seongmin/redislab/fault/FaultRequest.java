package com.seongmin.redislab.fault;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** target 은 논리 이름(primary, primary-2, replica-1, sentinel-2) 또는 카탈로그의 노드 이름만. 임의 문자열은 거부된다. */
public record FaultRequest(@NotNull FaultScenario scenario, String target, @Min(0) @Max(5000) Integer delayMs, @Min(0) @Max(50) Integer lossPct, Boolean confirm) {}
