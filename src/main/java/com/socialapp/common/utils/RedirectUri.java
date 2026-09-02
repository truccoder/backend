package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

/**
 * Normalises an OAuth {@code redirect_uri} read from configuration.
 *
 * <p>Infra builds these values as {@code ${PUBLIC_URL}/oauth/...}. When {@code PUBLIC_URL} already
 * ends in {@code /} the result is {@code https://host//oauth/...} with a doubled slash. Google and
 * GitHub compare {@code redirect_uri} against the registered URI byte-for-byte and reject any
 * difference — the browser sees {@code Error 400: redirect_uri_mismatch}. Collapsing repeated
 * slashes and dropping a trailing slash here means a stray slash in the environment can no longer
 * break the sign-in flow.
 *
 * <p>The same normalised value must be used both when building the authorisation URL and when
 * redeeming the code, because the provider validates that the two match.
 */
@UtilityClass
public class RedirectUri {

  public String normalize(String uri) {
    if (uri == null || uri.isBlank()) {
      return uri;
    }
    String trimmed = uri.strip();
    int schemeEnd = trimmed.indexOf("://");
    if (schemeEnd < 0) {
      return trimmed;
    }
    String scheme = trimmed.substring(0, schemeEnd + 3);
    String rest = trimmed.substring(schemeEnd + 3);

    int queryStart = rest.indexOf('?');
    String hostAndPath = queryStart < 0 ? rest : rest.substring(0, queryStart);
    String query = queryStart < 0 ? "" : rest.substring(queryStart);

    hostAndPath = hostAndPath.replaceAll("/{2,}", "/");
    if (hostAndPath.length() > 1 && hostAndPath.endsWith("/")) {
      hostAndPath = hostAndPath.substring(0, hostAndPath.length() - 1);
    }
    return scheme + hostAndPath + query;
  }
}
