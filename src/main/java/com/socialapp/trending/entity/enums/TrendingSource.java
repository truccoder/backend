package com.socialapp.trending.entity.enums;

/**
 * Where a trending item came from — one constant per live crawler, nothing more.
 *
 * <p>{@code REDDIT}, {@code MEDIUM} and {@code HBR} were removed along with their crawlers, after a
 * measured round showed none of the three could ever produce a row: Reddit answers 403 to the
 * listing JSON with or without a User-Agent (it wants OAuth2 credentials this project does not
 * hold); Medium fell back to {@code api.rss2json.com}, which answers 422, while its direct path
 * never parsed anything because {@code parseRssFeed} was a stub returning an empty list; HBR used
 * feed URLs that answer 404, through that same broken bridge, with the failure logged at DEBUG so
 * an error looked like an empty crawl.
 *
 * <p>Dropping the constants narrows {@code TrendingItemDto.source} in the OpenAPI contract, so the
 * generated client has to be regenerated in step. It is safe on the data side: {@code
 * t_trending_items.source} is a plain {@code varchar(50)} with no check constraint, and no row has
 * ever carried one of the three values. Adding a crawler back means adding its constant back here.
 */
public enum TrendingSource {
  HACKER_NEWS,
  DEV_TO,
  GITHUB
}
