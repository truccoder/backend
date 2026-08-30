package com.socialapp.linkpreview.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialapp.common.exception.ValidationException;

import lombok.extern.slf4j.Slf4j;

/**
 * Decides whether this server is willing to fetch a URL somebody else chose.
 *
 * <p>{@code LinkPreviewService} is a request that makes the server issue an HTTP call to an address
 * the caller supplies, which is the definition of server-side request forgery. From inside the
 * cluster that call reaches things no outside client can: Redis, Postgres, MinIO's admin API, the
 * container network, and — on a cloud host — the instance metadata service on {@code 169.254.169.254},
 * which hands out credentials to anything able to ask. The response body of a preview is returned
 * to the caller, so a successful fetch of any of those is also an exfiltration channel.
 *
 * <p>Separated from the fetching code and given its own tests because it is the whole of the
 * defence, and because it has to be consulted on <em>every</em> hop: checking only the URL the user
 * typed is defeated by a public host that answers {@code 302 Location: http://169.254.169.254/}.
 *
 * <p><b>What it does not close.</b> The address is resolved here and connected to by name a moment
 * later, so a DNS entry with a very short TTL can answer differently the second time (a "DNS
 * rebinding" attack). Closing that properly means pinning the resolved IP into the connection,
 * which the JDK's HTTP client gives no hook for. The remaining exposure is deliberate and bounded:
 * an attacker who controls a domain's DNS and wins a race gets one GET, capped in size and time,
 * whose body is parsed for meta tags before anything is returned.
 */
@Slf4j
@Component
public class UrlSafetyGuard {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  /**
   * Ports this will connect to.
   *
   * <p>The IP checks below are the real defence and this is the cheap one stacked on top: almost
   * everything worth reaching inside a cluster listens somewhere other than 80 or 443 (6379,
   * 5432, 9000, 8080…), while a public article — the only thing this endpoint exists to read —
   * effectively never does. A legitimate link on an unusual port is refused, and that is the
   * trade accepted here.
   */
  private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

  /**
   * Parses {@code rawUrl} and rejects anything this server should not be asked to fetch.
   *
   * @throws ValidationException with a message safe to show the person who pasted the link
   */
  public URI requireFetchable(String rawUrl) {
    URI uri = parse(rawUrl);
    requireFetchableShape(uri);
    requirePublicHost(uri.getHost());
    return uri;
  }

  /** The checks that need only the URL itself — scheme, credentials, host presence, port. */
  private void requireFetchableShape(URI uri) {
    String scheme = uri.getScheme();
    if (Objects.isNull(scheme) || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new ValidationException("Only http and https links can be previewed");
    }

    // Credentials in a URL ("http://user:pass@host/") are worth refusing on their own account:
    // they would be sent onwards by this server on the caller's behalf, and they are also the
    // classic way of confusing a naive host parser into reading the wrong side of the '@'.
    if (Objects.nonNull(uri.getUserInfo())) {
      throw new ValidationException("Links with embedded credentials cannot be previewed");
    }

    String host = uri.getHost();
    if (Objects.isNull(host) || host.isBlank()) {
      throw new ValidationException("That link has no host to fetch");
    }

    int port = uri.getPort();
    if (port != -1 && !ALLOWED_PORTS.contains(port)) {
      throw new ValidationException("Only links on the standard web ports can be previewed");
    }
  }

  private URI parse(String rawUrl) {
    try {
      // Deliberately not URI.create: it throws IllegalArgumentException, which the global handler
      // maps to a 500. A malformed link is the caller's typo, not this server's failure.
      return new URI(rawUrl.trim());
    } catch (URISyntaxException e) {
      throw new ValidationException("That is not a valid URL");
    }
  }

  /**
   * Resolves {@code host} and refuses it unless <em>every</em> address it answers with is public.
   *
   * <p>Every, not any: a name is free to publish several A records, and a client picks among them
   * however it likes. Accepting a host because one of its addresses is public would let an
   * attacker publish {@code 93.184.216.34} beside {@code 127.0.0.1} and rely on the connection
   * choosing the second.
   */
  private void requirePublicHost(String host) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new ValidationException("That host could not be found");
    }

    for (InetAddress address : addresses) {
      if (isPrivate(address)) {
        log.warn("Refused link preview for host {} resolving to {}", host, address);
        throw new ValidationException("That link points inside a private network");
      }
    }
  }

  /**
   * Whether an address belongs to a range that must never be reached from here.
   *
   * <p>{@link InetAddress}'s own predicates cover loopback, link-local (which is where cloud
   * metadata lives), site-local (RFC 1918) and multicast. The rest are ranges it has no predicate
   * for and that still resolve to something inside the perimeter.
   */
  private boolean isPrivate(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }

    byte[] bytes = address.getAddress();

    if (address instanceof Inet4Address) {
      int first = bytes[0] & 0xFF;
      int second = bytes[1] & 0xFF;
      return first == 0 // 0.0.0.0/8 — "this network"
          || first == 127 // belt and braces; isLoopbackAddress only recognises 127.0.0.1
          || (first == 100 && second >= 64 && second <= 127) // 100.64/10 carrier-grade NAT
          || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) // 192.0.0/24 IETF protocol
          || (first == 198 && (second == 18 || second == 19)) // 198.18/15 benchmarking
          || first >= 240; // 240/4 reserved, and 255.255.255.255 with it
    }

    if (address instanceof Inet6Address) {
      // fc00::/7 unique local, and fe80::/10 already handled by isLinkLocalAddress above.
      return (bytes[0] & 0xFE) == 0xFC;
    }

    return false;
  }
}
