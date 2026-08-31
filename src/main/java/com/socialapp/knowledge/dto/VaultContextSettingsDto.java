package com.socialapp.knowledge.dto;

import java.util.List;

/**
 * Which vault notes may be used as AI context.
 *
 * <p>Both lists empty is the default and means no filtering — every synced note is eligible, which
 * is what the product did before these settings existed.
 *
 * <p>{@code excludeTags} is applied AFTER {@code includeTags} and always wins: a note tagged both
 * {@code #tech} and {@code #private} is excluded. That order is the safe one — the other way round
 * would let a broad include quietly override a deliberate exclusion, which on a privacy control is
 * the mistake that matters.
 */
public record VaultContextSettingsDto(List<String> includeTags, List<String> excludeTags) {}
