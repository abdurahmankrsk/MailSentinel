package com.mailsentinel.dto;

/**
 * Finding returned by lookalike / typosquatting detection checks.
 *
 * @param technique "edit_distance" | "char_substitution" | "homoglyph" | "tld_swap"
 * @param matchedBrand brand domain or target identifier
 * @param detail explanation of the match
 */
public record LookalikeFinding(
    String technique,
    String matchedBrand,
    String detail
) {}
