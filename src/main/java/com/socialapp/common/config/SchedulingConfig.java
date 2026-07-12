package com.socialapp.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(
    prefix = "scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {}
