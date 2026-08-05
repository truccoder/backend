package com.socialapp.search.dto;

/**
 * One row in the type-ahead dropdown.
 *
 * <p>Deliberately one flat shape for every kind of hit rather than a per-type object: the dropdown
 * renders a single list, and a client that has to switch on four different payload shapes to draw
 * four identical rows ends up re-implementing this flattening anyway.
 *
 * @param type {@code USER} or {@code BOOK} — what {@code id} refers to, so the client knows where
 *     to navigate on click. Not an enum on the wire beyond its name; see {@link SuggestionType}.
 * @param id the id of the user or book, for building the target link
 * @param label the primary line — a person's display name, a book's title
 * @param sublabel the secondary line — a handle, an author name. May be null.
 * @param imageUrl avatar or cover. May be null.
 */
public record SuggestionDto(
    SuggestionType type, Integer id, String label, String sublabel, String imageUrl) {}
