package com.socialapp.search.dto;

/**
 * One row in the {@code @}-mention dropdown of a composer.
 *
 * <p>Not {@link SuggestionDto}, though the two look alike. That one feeds the search box, where a
 * row is something to <em>navigate</em> to and the handle is decoration under a display name — it
 * carries the handle inside a free-form {@code sublabel}, already prefixed with {@code @} and
 * allowed to be null. Here the handle is the payload: the client inserts it into the text being
 * typed, and what gets inserted has to be the bare handle, never null, or the tag it writes
 * notifies nobody. A dropdown row and a text insertion are different jobs, and one flat shape
 * serving both would leave the composer parsing a label back apart.
 *
 * @param id the user's id, for the avatar and for a client that wants to remember the choice
 * @param username the handle to insert after the {@code @} — bare, no prefix, never null, and
 *     always one {@code MentionScanner} will find again in the finished comment
 * @param fullName the display name to show on the row
 * @param profilePictureUrl avatar, may be null for an account that never set one
 * @param isFriend whether this person is a friend of the caller. The dropdown is friends-first, so
 *     this is what lets the client draw the divider between "your friends" and everyone else
 *     rather than inferring it from position.
 */
public record MentionSuggestionDto(
    Integer id, String username, String fullName, String profilePictureUrl, boolean isFriend) {}
