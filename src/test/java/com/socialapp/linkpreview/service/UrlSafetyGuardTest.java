package com.socialapp.linkpreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.socialapp.common.exception.ValidationException;

/**
 * Component tests for {@link UrlSafetyGuard}, per ISTQB CTFL v4.0.1 Section 2.2.2.
 *
 * <p>These are security tests before they are anything else. The guard is the whole defence
 * standing between a URL somebody typed into a composer and this server making an HTTP request
 * with it, from inside a network where Redis, Postgres, MinIO and — on a cloud host — a metadata
 * service handing out credentials are all reachable and none of them are reachable from outside.
 *
 * <p>Every address here is a literal, so nothing in this class depends on DNS: {@code
 * InetAddress.getAllByName} parses a literal rather than resolving it, which keeps the run
 * hermetic and fast.
 */
class UrlSafetyGuardTest {

  private final UrlSafetyGuard guard = new UrlSafetyGuard();

  @Nested
  @DisplayName("requireFetchable — links that are allowed through")
  class AllowedTests {

    @Test
    @DisplayName("shouldAcceptAnOrdinaryPublicHttpsUrl_happyPath")
    void shouldAcceptPublicHttps() {
      // Given / When
      URI uri = guard.requireFetchable("https://93.184.216.34/article?id=7#top");

      // Then — path, query and fragment survive untouched; the guard judges the host, not the URL
      assertThat(uri.getHost()).isEqualTo("93.184.216.34");
      assertThat(uri.getPath()).isEqualTo("/article");
      assertThat(uri.getQuery()).isEqualTo("id=7");
    }

    @Test
    @DisplayName("shouldAcceptPlainHttpAndTheTwoStandardPorts")
    void shouldAcceptHttpAndStandardPorts() {
      // Given / When / Then — EP: http and https, default port or an explicit 80/443
      assertThatCode(() -> guard.requireFetchable("http://8.8.8.8/")).doesNotThrowAnyException();
      assertThatCode(() -> guard.requireFetchable("http://8.8.8.8:80/")).doesNotThrowAnyException();
      assertThatCode(() -> guard.requireFetchable("https://8.8.8.8:443/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldTrimSurroundingWhitespace_asPastedLinksCarryIt")
    void shouldTrimWhitespace() {
      // Given — a URL copied out of a chat client arrives with a trailing newline more often than
      // not, and rejecting that as malformed would be a mystery to the person who pasted it
      assertThatCode(() -> guard.requireFetchable("  https://8.8.8.8/x  \n"))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("requireFetchable — addresses inside the perimeter")
  class BlockedAddressTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "http://127.0.0.1/", // loopback — everything the app itself serves
          "http://127.0.0.53/", // loopback beyond .0.1, which isLoopbackAddress alone misses
          "http://169.254.169.254/latest/meta-data/", // cloud metadata: credentials on request
          "http://10.0.0.5/", // RFC 1918
          "http://172.16.0.5/",
          "http://192.168.1.10/",
          "http://0.0.0.0/", // "this host"
          "http://0.1.2.3/", // 0.0.0.0/8
          "http://100.64.0.1/", // carrier-grade NAT
          "http://192.0.0.1/", // IETF protocol assignments
          "http://198.18.0.1/", // benchmarking range
          "http://240.0.0.1/", // reserved
          "http://255.255.255.255/", // broadcast
          "http://[::1]/", // IPv6 loopback
          "http://[fc00::1]/", // IPv6 unique local
          "http://[fe80::1]/", // IPv6 link local
          "http://224.0.0.1/" // multicast
        })
    @DisplayName("shouldRefuseAnAddressThatIsNotPublic")
    void shouldRefusePrivateAddresses(String url) {
      // When / Then — the message is deliberately the same for all of them: telling the caller
      // which internal range they hit is telling them what is in there
      assertThatThrownBy(() -> guard.requireFetchable(url))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("private network");
    }

    @Test
    @DisplayName("shouldRefuseLocalhostByName_notOnlyByAddress")
    void shouldRefuseLocalhostByName() {
      // Given — a name check would be defeated by the address and an address check by the name,
      // which is why the guard resolves first and judges the result
      assertThatThrownBy(() -> guard.requireFetchable("http://localhost:80/"))
          .isInstanceOf(ValidationException.class);
    }
  }

  @Nested
  @DisplayName("requireFetchable — malformed and out-of-scope links")
  class RejectedShapeTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "file:///etc/passwd", // reads the server's own disk
          "ftp://8.8.8.8/x",
          "gopher://8.8.8.8/x", // the classic protocol-smuggling scheme
          "data:text/html,<h1>x</h1>",
          "javascript:alert(1)"
        })
    @DisplayName("shouldRefuseAnySchemeOtherThanHttpAndHttps")
    void shouldRefuseOtherSchemes(String url) {
      // When / Then — EP: an allow-list of two
      assertThatThrownBy(() -> guard.requireFetchable(url)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("shouldRefuseANonStandardPort_soInternalServicesCannotBeProbed")
    void shouldRefuseUnusualPorts() {
      // Given — 6379 is Redis, 5432 is Postgres, 9000 is MinIO. The address rules already cover
      // the ones inside this network; this is the cheap second line for anything they do not.
      assertThatThrownBy(() -> guard.requireFetchable("http://8.8.8.8:6379/"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("standard web ports");
    }

    @Test
    @DisplayName("shouldRefuseCredentialsEmbeddedInTheUrl")
    void shouldRefuseUserInfo() {
      // Given — this server would forward them on the caller's behalf, and the '@' is the oldest
      // way of confusing a host parser into reading the wrong half of an authority
      assertThatThrownBy(() -> guard.requireFetchable("http://user:secret@8.8.8.8/"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("shouldRefuseAUrlWithNoHost")
    void shouldRefuseHostlessUrl() {
      // When / Then
      assertThatThrownBy(() -> guard.requireFetchable("https:///just-a-path"))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("shouldReportAMalformedUrlAsTheCallersMistake_not500")
    void shouldRejectMalformedUrl() {
      // Given / When / Then — URI.create would throw IllegalArgumentException here, which the
      // global handler maps to 500; a typo in a pasted link is not a server fault
      assertThatThrownBy(() -> guard.requireFetchable("ht tp://not a url"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("valid URL");
    }

    @Test
    @DisplayName("shouldRefuseAHostThatDoesNotResolve")
    void shouldRefuseUnknownHost() {
      // Given — .invalid is reserved by RFC 2606 precisely so that it never resolves
      assertThatThrownBy(() -> guard.requireFetchable("https://nothing-here.invalid/"))
          .isInstanceOf(ValidationException.class);
    }
  }
}
