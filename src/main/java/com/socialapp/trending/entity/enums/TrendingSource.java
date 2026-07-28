package com.socialapp.trending.entity.enums;

/**
 * Where a trending item came from.
 *
 * <p>{@code REDDIT}, {@code MEDIUM} and {@code HBR} have no crawler any more — the three classes
 * were deleted after a measured round showed none of them could ever produce a row:
 *
 * <ul>
 *   <li><b>Reddit</b> answers 403 to the listing JSON whether or not a User-Agent is sent; the
 *       public endpoint now wants OAuth2 credentials this project does not hold.
 *   <li><b>Medium</b> fell back to {@code api.rss2json.com}, which answers 422, and its direct path
 *       never parsed anything — {@code parseRssFeed} was a stub returning an empty list.
 *   <li><b>HBR</b> used feed URLs that answer 404, through that same broken bridge, with the
 *       failure logged at DEBUG so an error looked like an empty crawl.
 * </ul>
 *
 * <p>The three constants stay: removing them would narrow {@code TrendingItemDto.source} in the
 * OpenAPI contract and break the generated client, and a future crawler for any of these should
 * write the same value rather than invent a new one. Nothing produces them today.
 */
public enum TrendingSource {
  HACKER_NEWS,
  DEV_TO,
  GITHUB,
  REDDIT,
  MEDIUM,
  HBR
}
