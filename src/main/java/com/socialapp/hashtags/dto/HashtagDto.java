package com.socialapp.hashtags.dto;

/**
 * One hashtag in a suggest or trending list.
 *
 * @param tag the folded name, without the leading {@code #} — the same string {@code
 *     FeedPostDataDto.hashtags} carries, so a client can match it against a post's own tags and can
 *     pass it straight back to {@code GET /v1/api/posts/public?hashtag=}.
 * @param postCount how many posts the row is counted against. For {@code /hashtags/suggest} this is
 *     {@code t_hashtags.usage_count} — the running total the composer maintains. For {@code
 *     /hashtags/trending} it is the number of matching posts <i>inside the requested window</i>, so
 *     the two endpoints can report different numbers for the same tag and both are right.
 */
public record HashtagDto(String tag, long postCount) {}
