package com.socialapp.posts.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;

/**
 * Finds the {@code @handle} mentions in a piece of user-written text.
 *
 * <p>Comments carry mentions as plain text and nothing else: {@code CreateCommentRequestDto} has
 * two fields, {@code content} and {@code parentId}, and the clients write the handle into the body
 * when someone taps Reply. So the tag a reader sees is real, links to the right profile, and told
 * nobody — the notification half of a mention was missing because there was no structured field to
 * hang it on.
 *
 * <p><b>Scanning the text rather than adding a {@code mentionedUserIds} field</b> is what the
 * frontend already produces, so it needs no client change and works on the comments already in the
 * database. The structured field is the better contract and only becomes worth having once there is
 * an autocomplete behind the {@code @} — until then it would be a second source of truth that can
 * disagree with the text beside it.
 */
@UtilityClass
public class MentionScanner {

  /**
   * A mention: an {@code @} at the start of the text or after whitespace, followed by a handle.
   *
   * <p>Both halves matter and both come from what the clients already highlight.
   *
   * <ul>
   *   <li>{@code (?<![^\s])} — nothing but whitespace may precede the {@code @}. Without it, {@code
   *       ai@example.com} contains "@example" and every comment quoting an email address would
   *       notify a stranger who happens to hold that handle. This is a fixed-width negative
   *       lookbehind, so it also matches at the very start of the string, where there is nothing
   *       to look behind at.
   *   <li>The handle body is 3 to 30 characters of letters, digits, hyphens and underscores, never
   *       starting with a separator. A period is not in that set, so {@code @ada.} yields {@code
   *       ada} and the sentence keeps its full stop.
   * </ul>
   *
   * <p><b>Deliberately one character wider than {@code UsernameSlugger.USERNAME_PATTERN}</b>,
   * which allows hyphens but not underscores. That pattern governs what a user may <em>register</em>;
   * this one has to match what the {@code username} column actually holds, and the two are not the
   * same set — every seeded account carries an underscore ({@code backend_truc_anh}), and {@code
   * /u/{username}} resolves them happily because routing is a plain lookup with no format rule
   * attached. A scanner held to the stricter pattern would silently fail to find any of them, and
   * the failure would look like "mentions do not work" rather than like a mismatched charset.
   *
   * <p>Being wider costs nothing: a handle nobody holds resolves to nobody and is dropped. Being
   * narrower loses real mentions, which is why the error is taken in this direction.
   *
   * <p>Matched case-insensitively even though a stored handle is always lower-case: people type
   * {@code @Ada} for a person called Ada, and the lookup this feeds is case-insensitive too. The
   * result is lower-cased here so the caller compares like with like.
   */
  private static final String HANDLE = "[a-zA-Z0-9][a-zA-Z0-9_-]{2,29}";

  private static final Pattern MENTION = Pattern.compile("(?<![^\\s])@(" + HANDLE + ")");

  /**
   * The same handle body, anchored — for asking about one handle rather than scanning a sentence.
   *
   * <p>Built from the same {@link #HANDLE} string as {@link #MENTION} on purpose: the two
   * questions "would this text notify anybody" and "is this handle worth offering in the
   * @-dropdown" have to be answered by one rule. Two copies that drift apart put a name in the
   * suggestion list that, once tapped, notifies nobody — the silent half-failure this scanner
   * exists to close.
   */
  private static final Pattern HANDLE_ONLY = Pattern.compile(HANDLE);

  /**
   * How many distinct handles one piece of text may notify.
   *
   * <p>A ceiling on the blast radius rather than a formatting rule. Nothing limits how many handles
   * fit in a comment body, and without this one comment could ring fifty people's bells — a
   * broadcast channel built out of a reply box. Comfortably above what a real conversation needs:
   * a thread where six people are addressed at once is already unusual.
   *
   * <p>Applied to the first {@link #MAX_MENTIONS} <em>distinct</em> handles in reading order, so a
   * comment over the limit still notifies the people named at the top of it.
   */
  public static final int MAX_MENTIONS = 10;

  /**
   * Whether {@code handle} is one this scanner would find if somebody typed {@code @handle}.
   *
   * <p>For the mention dropdown, which must not offer a name that cannot be tagged. Every
   * username is written by {@code UsernameSlugger} or seeded, and both produce handles this
   * pattern accepts — except at the short end: a person called "Ly" slugs to {@code ly}, two
   * characters, below the three this pattern requires. Suggesting them would produce a tag the
   * client renders as a link and that notifies nobody, which is exactly the failure {@code
   * CommentService#notifyMentionedUsers} was added to end.
   *
   * <p>Null is answered {@code false} rather than thrown on: the column is {@code NOT NULL}
   * since {@code V47}, but a caller filtering a candidate list should not have to know that.
   */
  public static boolean isMentionable(String handle) {
    return handle != null && HANDLE_ONLY.matcher(handle).matches();
  }

  /**
   * The handles mentioned in {@code text}, lower-cased, de-duplicated, in the order they appear,
   * and capped at {@link #MAX_MENTIONS}.
   *
   * <p>These are candidate handles, not users: the pattern says what looks like a mention, and only
   * a lookup can say whether anybody holds it. Callers must resolve them against the user table and
   * silently drop the ones that match nobody — a comment saying {@code @nobody} is a comment, not
   * an error.
   */
  public static Set<String> scan(String text) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }

    // LinkedHashSet: de-duplication and reading order in one pass. Order is what makes the cap
    // predictable — the same comment always notifies the same ten people, rather than whichever
    // ten a hash bucket happened to yield.
    Set<String> handles = new LinkedHashSet<>();
    Matcher matcher = MENTION.matcher(text);

    while (matcher.find() && handles.size() < MAX_MENTIONS) {
      handles.add(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    return handles;
  }
}
