package com.socialapp.notifications.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialapp.notifications.dto.NotificationResponseDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-sent events for the notification bell, replacing the client's polling loop.
 *
 * <p>The bell used to ask {@code /unread-count} every few seconds forever, per open tab, whether or
 * not anything had happened — which is what "real time" meant in this product until now. One
 * long-lived response per open tab, written to only when there is something to say, is both cheaper
 * and actually immediate.
 *
 * <p><b>SSE rather than WebSocket</b> because the traffic is one-way. A WebSocket would add a
 * protocol upgrade, a message broker (or a hand-rolled session registry), and a second
 * authentication path, to carry events that only ever travel server → client. SSE is an ordinary
 * authenticated GET that never finishes, so it goes through the existing JWT filter and the
 * existing CORS configuration untouched.
 *
 * <p><b>The client must send {@code Authorization}.</b> The browser's native {@code EventSource}
 * cannot set headers, so it cannot call this endpoint; a fetch-based SSE client can, and that is
 * the intended client. The alternative — accepting the token as a query parameter — would write a
 * live bearer token into every access log, proxy log and {@code Referer} header on the way, which
 * is not a trade worth making to save the frontend a dependency.
 *
 * <p><b>Known limit: this registry is per-process.</b> Emitters live in a map in this JVM, so an
 * event only reaches subscribers connected to the instance that produced it. With the single
 * backend container this project deploys, that is every subscriber. Running more than one replica
 * means republishing through Redis (already a dependency) and having each instance forward to its
 * own emitters — the registry below is the piece that would stay.
 */
@Slf4j
@Service
public class NotificationStreamService {

  /**
   * How long a stream is held open before the server closes it and the client reconnects.
   *
   * <p>Not {@code 0} (never time out): a connection that the server will never give up on is a
   * connection leak the moment a client disappears without a FIN — a laptop lid closing, a phone
   * losing signal. Thirty minutes is long enough that reconnects are rare and short enough that a
   * dead peer is not held forever. SSE clients reconnect on their own when the stream ends.
   */
  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

  /** Event name for the first frame, so a client can tell "connected" from "you have mail". */
  private static final String EVENT_CONNECTED = "connected";

  private static final String EVENT_NOTIFICATION = "notification";
  private static final String EVENT_HEARTBEAT = "heartbeat";

  /**
   * One user can hold several streams — several tabs, phone and laptop — so the value is a set, not
   * a single emitter. {@code ConcurrentHashMap} plus a concurrent set because subscribe, publish,
   * completion callbacks and the heartbeat all run on different threads.
   */
  private final Map<Integer, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

  /** Opens a stream for one user and registers it to receive their notifications. */
  public SseEmitter subscribe(Integer userId) {
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

    emittersByUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(emitter);

    // All three fire on the container's thread, not ours, and all three mean the same thing here:
    // this emitter can no longer be written to. Forgetting any one of them leaks the entry.
    emitter.onCompletion(() -> remove(userId, emitter));
    emitter.onTimeout(() -> remove(userId, emitter));
    emitter.onError(error -> remove(userId, emitter));

    // Sent immediately so the response commits and the client's connection callback fires. Without
    // a first frame the browser sits on an open request with no headers flushed and cannot tell a
    // working stream from a stalled one.
    send(userId, emitter, EVENT_CONNECTED, "ok");

    return emitter;
  }

  /** Delivers one notification to every stream that user currently has open. */
  public void publish(Integer userId, NotificationResponseDto notification) {
    Set<SseEmitter> emitters = emittersByUser.get(userId);
    if (emitters == null || emitters.isEmpty()) {
      return;
    }
    emitters.forEach(emitter -> send(userId, emitter, EVENT_NOTIFICATION, notification));
  }

  /**
   * Writes a byte to every open stream on a fixed interval.
   *
   * <p>Not a keep-alive for its own sake: idle connections are dropped silently by proxies and
   * mobile networks after a minute or two, and a client cannot distinguish "nothing has happened"
   * from "the connection died twenty minutes ago". A periodic frame both keeps the path open and
   * surfaces a broken one — the write fails, the callback removes the emitter, and the client
   * reconnects.
   */
  @Scheduled(fixedRateString = "${notifications.sse.heartbeat-ms:25000}")
  public void heartbeat() {
    emittersByUser.forEach(
        (userId, emitters) ->
            emitters.forEach(emitter -> send(userId, emitter, EVENT_HEARTBEAT, "ping")));
  }

  /** Open streams, for tests and for anything that wants to assert the registry is being cleaned. */
  public int openStreamCount() {
    return emittersByUser.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * A failed write means the peer is gone — a closed tab, a dropped network. Logged at debug and
   * cleaned up, never rethrown: this runs from an {@code @Async} notification send and from the
   * heartbeat, and one dead subscriber must not fail the send for the others or blow up a
   * scheduled task.
   */
  private void send(Integer userId, SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException | IllegalStateException e) {
      log.debug("Dropping notification stream for user {}: {}", userId, e.toString());
      remove(userId, emitter);
      try {
        emitter.completeWithError(e);
      } catch (RuntimeException ignored) {
        // Already completed by the container — there is nothing left to close, and the entry is
        // gone from the registry either way.
      }
    }
  }

  /** Removes one emitter, and the user's entry once it holds none — the map must not grow forever. */
  private void remove(Integer userId, SseEmitter emitter) {
    emittersByUser.computeIfPresent(
        userId,
        (key, emitters) -> {
          emitters.remove(emitter);
          return emitters.isEmpty() ? null : emitters;
        });
  }
}
