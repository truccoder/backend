package com.socialapp.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the seed migrations for real, against a throwaway PostgreSQL instance.
 *
 * <p>Nothing else does. {@code application.yml} defaults {@code spring.flyway.locations} to {@code
 * classpath:db/migration} alone, and the test profile does not override it — so every other
 * integration test runs on a schema-only database and would still pass with a seed file that does
 * not parse. The seed only executes when a developer sets {@code FLYWAY_LOCATIONS} by hand, or on
 * production, which are the two worst places to discover a syntax error.
 *
 * <p>Its own container, deliberately, rather than the shared one from {@code
 * AbstractIntegrationTest}: loading sixty users and two hundred posts into the database every
 * other test class shares would change what those tests see.
 *
 * <p>The assertions are not a re-count of the seed. They are the THRESHOLDS from
 * {@code docs/backend-plan.md} S1-S7 — the numbers that decide whether a screen's branch actually
 * runs. A fixture that drifts back under its threshold is still valid SQL and still loads
 * cleanly; it just silently stops testing anything, which is exactly the state the previous seed
 * was in.
 */
class SeedMigrationTest {

  private static PostgreSQLContainer<?> postgres;
  private static Connection connection;

  @BeforeAll
  static void migrate() throws Exception {
    postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("socialapp")
            .withUsername("postgres")
            .withPassword("postgres");
    postgres.start();

    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("socialapp")
        .defaultSchema("socialapp")
        .createSchemas(true)
        // The same three locations a fully seeded dev machine uses — see db/seed/README.md.
        .locations("classpath:db/migration", "classpath:db/seed", "classpath:db/seed-dev")
        .load()
        .migrate();

    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  private static long scalar(String sql) throws Exception {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
      return rs.getLong(1);
    }
  }

  private static String text(String sql) throws Exception {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
      return rs.getString(1);
    }
  }

  @Test
  @DisplayName("the whole seed applies, and every table it targets ends up populated")
  void shouldApplyEverySeedFile() throws Exception {
    // Given the migration in @BeforeAll — When / Then
    assertThat(scalar("SELECT count(*) FROM socialapp.t_users")).isGreaterThanOrEqualTo(61);
    assertThat(scalar("SELECT count(*) FROM socialapp.t_posts")).isGreaterThan(150);
    assertThat(scalar("SELECT count(*) FROM socialapp.t_comments")).isGreaterThan(700);
    assertThat(scalar("SELECT count(*) FROM socialapp.t_books")).isEqualTo(21);
  }

  @Test
  @DisplayName("sequences are pushed past the hand-assigned ids, so the first API insert fits")
  void shouldAdvanceSequencesPastSeededIds() throws Exception {
    // Given — ids are assigned explicitly in the seed, so the sequences would otherwise still sit
    // at 1 and the first post created through the API would collide on the primary key. That is a
    // failure that only appears when somebody presses a button, never while loading the seed.
    assertThat(scalar("SELECT last_value FROM socialapp.q_posts_id"))
        .isEqualTo(scalar("SELECT max(id) FROM socialapp.t_posts"));
    assertThat(scalar("SELECT last_value FROM socialapp.q_comments_id"))
        .isEqualTo(scalar("SELECT max(id) FROM socialapp.t_comments"));
    assertThat(scalar("SELECT last_value FROM socialapp.q_users_id"))
        .isEqualTo(scalar("SELECT max(id) FROM socialapp.t_users"));
    assertThat(scalar("SELECT last_value FROM socialapp.q_books_id"))
        .isEqualTo(scalar("SELECT max(id) FROM socialapp.t_books"));
  }

  @Nested
  @DisplayName("S1 · long post content — the 280px clamp")
  class LongContentTests {

    @Test
    @DisplayName("should hold a post far above the clamp and one clearly below it")
    void shouldCoverBothSidesOfTheClamp() throws Exception {
      // Given — the clamp is 280px, roughly 750 characters at 15px/1.6 on a 672px column. One
      // long post alone proves nothing: without a short one, a component that clamped EVERY post
      // would pass too.
      assertThat(scalar("SELECT max(length(content)) FROM socialapp.t_posts"))
          .isGreaterThanOrEqualTo(1200);
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_posts"
                      + " WHERE length(content) BETWEEN 550 AND 750"))
          .isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("S2/S3 · code snippets")
  class SnippetTests {

    @Test
    @DisplayName("should hold a snippet well past the 15-line clamp")
    void shouldHoldALongSnippet() throws Exception {
      // Given — the 320px snippet clamp is about 15 lines at 13px/1.6
      assertThat(
              scalar(
                  "SELECT max(length(code_snippet_details->>'code')"
                      + " - length(replace(code_snippet_details->>'code', chr(10), '')))"
                      + " FROM socialapp.t_posts WHERE code_snippet_details IS NOT NULL"))
          .isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("should hold a snippet line too wide to wrap, for the inner horizontal scroll")
    void shouldHoldAVeryWideLine() throws Exception {
      // Given — wrapping and scrolling are different branches; only an unwrappable line reaches
      // the second one
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_posts, "
                      + " unnest(string_to_array(code_snippet_details->>'code', chr(10))) AS line"
                      + " WHERE code_snippet_details IS NOT NULL AND length(line) > 120"))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("should cover every highlighted language plus the two uncoloured edge cases")
    void shouldCoverEveryLanguageAndBothEdgeCases() throws Exception {
      // Given — the palette follows highlight.js grammars, so each named language is its own
      // branch. plaintext must render as plain black text, and a language outside the list must
      // degrade to plain text rather than break: `language` is a free String on the backend, so
      // that is a case a real user can produce.
      for (String language :
          new String[] {
            "java", "typescript", "python", "sql", "shell", "json", "css", "plaintext", "zig"
          }) {
        assertThat(
                scalar(
                    "SELECT count(*) FROM socialapp.t_posts"
                        + " WHERE code_snippet_details->>'language' = '"
                        + language
                        + "'"))
            .as("no seeded snippet in %s", language)
            .isGreaterThan(0);
      }
    }
  }

  @Nested
  @DisplayName("S4 · comment preview")
  class CommentPreviewTests {

    private long rootComments(int postId) throws Exception {
      return scalar(
          "SELECT count(*) FROM socialapp.t_comments WHERE post_id = "
              + postId
              + " AND parent_id IS NULL");
    }

    @Test
    @DisplayName("should hold posts with exactly 0, 1, 2 and 5+ root comments")
    void shouldCoverEveryCommentCountCase() throws Exception {
      // Given — 2 is its own case and not a smaller version of 5: a post with exactly two
      // comments must NOT show "see all" as though more were hidden
      assertThat(rootComments(5310)).isZero();
      assertThat(rootComments(5311)).isEqualTo(1);
      assertThat(rootComments(5312)).isEqualTo(2);
      assertThat(rootComments(5313)).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("should hold a comment long enough to be clamped to two lines")
    void shouldHoldALongComment() throws Exception {
      assertThat(scalar("SELECT max(length(content)) FROM socialapp.t_comments"))
          .isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("should make the first two visible comments replies, for the demo account")
    void shouldStartWithRepliesOnceTheBlockedRootIsFiltered() throws Exception {
      // Given — the preview may only take ROOT comments. The case is built out of the block
      // filter rather than out of impossible timestamps: a reply always comes after its parent,
      // so a thread can only start with a reply when the parent is filtered away.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_user_blocks"
                      + " WHERE blocker_id = 9001 AND blocked_id = 9057"))
          .isEqualTo(1);

      // Then — with 9057's root comment hidden, the two oldest remaining rows both have a parent
      assertThat(
              scalar(
                  "SELECT count(*) FROM ("
                      + "  SELECT parent_id FROM socialapp.t_comments"
                      + "   WHERE post_id = 5314 AND author_id <> 9057"
                      + "   ORDER BY created_at ASC LIMIT 2) AS visible"
                      + " WHERE parent_id IS NOT NULL"))
          .isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("S5 · reactions")
  class ReactionTests {

    @Test
    @DisplayName("should give the demo account a reaction that is not LIKE")
    void shouldGiveTheDemoAccountANonLikeReaction() throws Exception {
      // Given — the button renders the caller's OWN choice, so a seed where everything is LIKE
      // cannot tell "shows the right label" apart from "always shows LIKE"
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_post_reactions"
                      + " WHERE user_id = 9001 AND reaction_type <> 'LIKE'"))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("should use the two newly added reaction types somewhere")
    void shouldUseInsightAndClap() throws Exception {
      // Given — INSIGHT and CLAP were added to the enum but no seeded row used them, so their
      // display branch had never run
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_post_reactions"
                      + " WHERE reaction_type IN ('INSIGHT', 'CLAP')"))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("should leave one post with no reactions at all")
    void shouldLeaveOnePostWithNoReactions() throws Exception {
      // Given — the count has a non-clickable zero state
      assertThat(scalar("SELECT count(*) FROM socialapp.t_post_reactions WHERE post_id = 5310"))
          .isZero();
    }

    @Test
    @DisplayName("should give comments unequal reaction counts, so ranking has something to rank")
    void shouldGiveCommentsUnequalReactionCounts() throws Exception {
      // Given — likeCount exists to order the two most-reacted comments. If every comment had the
      // same total, the ordering would be arbitrary and the screen untestable.
      assertThat(scalar("SELECT count(*) FROM socialapp.t_comment_reactions")).isGreaterThan(0);
      assertThat(
              scalar(
                  "SELECT count(DISTINCT n) FROM ("
                      + "  SELECT count(*) AS n FROM socialapp.t_comment_reactions"
                      + "   GROUP BY comment_id) AS totals"))
          .isGreaterThan(1);
    }

    @Test
    @DisplayName("should hold nothing but LIKE on a comment, while a post keeps all seven")
    void shouldLeaveOnlyLikesOnComments() throws Exception {
      // Given - a comment may only be liked. V65 wrote 7 rows of INSIGHT/CLAP/LOVE before that
      // rule existed and V73 migrates them; without it likeCount and reactionSummary would keep
      // telling a seven-type story under an interface that offers one.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_comment_reactions"
                      + " WHERE reaction_type <> 'LIKE' OR reaction_type IS NULL"))
          .isZero();

      // Then - migrated, not deleted. The test above ranks comments by an unequal spread of
      // reaction counts, and deleting the 7 rows would flatten it to one-each and take that case
      // away. 11 rows in, 11 rows out.
      assertThat(scalar("SELECT count(*) FROM socialapp.t_comment_reactions")).isEqualTo(11);

      // Then - and the rule is the comment's alone: a post still carries the knowledge-shaped
      // reactions the design calls for.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_post_reactions"
                      + " WHERE reaction_type IN ('INSIGHT', 'CLAP')"))
          .isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("S6/S7 · avatars, long names, and a broken object key")
  class MiscFixtureTests {

    @Test
    @DisplayName("should hold accounts both with and without a profile picture")
    void shouldCoverBothAvatarBranches() throws Exception {
      // Given — the initials fallback and the image are two branches, and before db/seed-dev's
      // avatar file every single row was NULL, so only one of them ever ran
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_users WHERE profile_picture_url IS NOT NULL"))
          .isGreaterThanOrEqualTo(3);
      assertThat(scalar("SELECT count(*) FROM socialapp.t_users WHERE profile_picture_url IS NULL"))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("should hold a full name long enough to need truncating")
    void shouldHoldAVeryLongFullName() throws Exception {
      assertThat(scalar("SELECT max(length(full_name)) FROM socialapp.t_users"))
          .isGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("should hold one book whose file is deliberately absent from the object store")
    void shouldHoldABookWithAMissingObject() throws Exception {
      // Given — the storage-failure branch became unreachable once the MinIO bucket was created
      // and every seeded book got a real file. This row is the only way back to it.
      assertThat(text("SELECT file_key FROM socialapp.t_books WHERE id = 3021"))
          .contains("khong-ton-tai");
    }
  }

  @Nested
  @DisplayName("S10 · images, on the three paths that carry them")
  class PostImageTests {

    private long imageCount(int postId) throws Exception {
      return scalar(
          "SELECT jsonb_array_length(images) FROM socialapp.t_posts WHERE id = " + postId);
    }

    @Test
    @DisplayName("should cover the one, two and many image layouts")
    void shouldCoverEveryGridBranch() throws Exception {
      // Given — one, two and four-or-more are three different layouts on the client, not one
      // layout repeated. Before this seed the measured answer across 80 posts was zero images on
      // every single path, so none of the three had ever rendered.
      assertThat(imageCount(5301)).isEqualTo(1);
      assertThat(imageCount(5302)).isEqualTo(2);
      assertThat(imageCount(5313)).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("should hold one post whose image is deliberately absent from the object store")
    void shouldHoldABrokenImage() throws Exception {
      // Given — image URLs point outward and do eventually die, so the fallback has to be seen
      // once here rather than for the first time on a user's screen. Same device as the book with
      // a missing file in V65.
      assertThat(text("SELECT images->>0 FROM socialapp.t_posts WHERE id = 5310"))
          .contains("khong-ton-tai");
    }

    @Test
    @DisplayName("should fill the article cover and the link thumbnail, and leave siblings null")
    void shouldCoverTheOtherTwoImagePaths() throws Exception {
      // Given — both fields were explicitly left null across all of V53 with the note "nowhere to
      // put a real image". Both branches were therefore dead.
      assertThat(
              text("SELECT article_details->>'coverImage' FROM socialapp.t_posts WHERE id = 5031"))
          .isNotNull();
      assertThat(
              text("SELECT link_details->>'thumbnailUrl' FROM socialapp.t_posts WHERE id = 5081"))
          .isNotNull();

      // Then — and the un-unfurled case survives: a LINK post with no thumbnail is what pasting a
      // link looks like before anybody asks for a preview, and it must still render.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_posts"
                      + " WHERE post_type = 'LINK' AND link_details->>'thumbnailUrl' IS NULL"))
          .isGreaterThan(0);
    }

    @Test
    @DisplayName("should hold accounts both with and without a profile cover")
    void shouldCoverBothCoverBranches() throws Exception {
      // Given — the column allows NULL and the clients draw a plain token-coloured band for it,
      // so "no cover" is the default branch and needs data just as much as the image one does
      assertThat(scalar("SELECT count(*) FROM socialapp.t_users WHERE cover_image_url IS NOT NULL"))
          .isGreaterThan(0);
      assertThat(scalar("SELECT count(*) FROM socialapp.t_users WHERE cover_image_url IS NULL"))
          .isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("B20/B21 · mentions and the role line")
  class MentionAndRoleLineTests {

    @Test
    @DisplayName("should hold comments that name a handle somebody actually holds")
    void shouldHoldRealMentions() throws Exception {
      // Given - there was not one '@' character anywhere in V54, so the whole mention path had no
      // data to run on. A mention of a handle nobody holds would have been worse than none: the
      // fixture would look right in the SQL and still notify nobody.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_comments c"
                      + " JOIN socialapp.t_users u"
                      + "   ON c.content LIKE '%@' || u.username || '%'"
                      + " WHERE c.id BETWEEN 8014 AND 8016"))
          .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("should hold one comment whose @ is an email address, not a mention")
    void shouldHoldTheNegativeCase() throws Exception {
      // Given - the rule that the '@' must follow whitespace exists so that a quoted email
      // address does not notify whoever holds that handle. Without a row like this, nothing in
      // the data proves the rule.
      assertThat(text("SELECT content FROM socialapp.t_comments WHERE id = 8016"))
          .contains("@socialapp.com");
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE reference_id = 8016 AND type = 'USER_MENTIONED'"))
          .isZero();
    }

    @Test
    @DisplayName("should notify every mentioned user, pointing at the comment not the post")
    void shouldNotifyMentionedUsers() throws Exception {
      // Given - one comment names one person, the next names two
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE type = 'USER_MENTIONED'"))
          .isGreaterThanOrEqualTo(3);

      // Then - a thread runs to hundreds of replies, so the notification has to open at the one
      // that named you
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE type = 'USER_MENTIONED' AND reference_type <> 'COMMENT'"))
          .isZero();
    }

    @Test
    @DisplayName("should give every COMMENT notification a post to open, including seeded ones")
    void shouldBackfillPostIdOnCommentNotifications() throws Exception {
      // Given - reference_id being the comment id is right, and on its own it addresses nothing:
      // no client route is keyed by a comment id. V72 adds post_id and backfills it from
      // t_comments, which is the only reason the rows V70 seeded are tappable rather than just
      // the ones written after the deploy. This assertion is what proves the backfill ran at all.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE reference_type = 'COMMENT'"))
          .isGreaterThan(0);
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE reference_type = 'COMMENT' AND post_id IS NULL"))
          .isZero();

      // Then - and it points at the post that actually holds the comment, not just any post
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications n"
                      + "  JOIN socialapp.t_comments c ON c.id = n.reference_id"
                      + " WHERE n.reference_type = 'COMMENT' AND n.post_id <> c.post_id"))
          .isZero();
    }

    @Test
    @DisplayName("should leave post_id null on notifications that are not about a comment")
    void shouldLeavePostIdNullOnOtherNotifications() throws Exception {
      // Given - the backfill is scoped to COMMENT rows. A friend request has no post, and NULL is
      // the correct state there rather than missing data; a WHERE clause that drifted wider would
      // quietly give every notification a post to open.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_notifications"
                      + " WHERE reference_type <> 'COMMENT' AND post_id IS NOT NULL"))
          .isZero();
    }

    @Test
    @DisplayName("should hold accounts both with and without a professional profile")
    void shouldCoverBothRoleLineBranches() throws Exception {
      // Given - the public profile now carries a role line, and "never filled the form in" is the
      // ordinary state. Both branches need data or one of them never renders.
      assertThat(scalar("SELECT count(*) FROM socialapp.t_user_professional_profiles"))
          .isGreaterThan(0);
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_users u"
                      + " WHERE NOT EXISTS (SELECT 1 FROM socialapp.t_user_professional_profiles p"
                      + "                    WHERE p.user_id = u.id)"))
          .isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("S8 · a stored explanation that actually contains Markdown")
  class MarkdownExplanationTests {

    private String markdownExplanation() throws Exception {
      return text(
          "SELECT explanation_content FROM socialapp.t_explanations"
              + " WHERE post_id = 5302 AND user_id = 9001 AND version = 1");
    }

    @Test
    @DisplayName("should map every element type the explanation card renders")
    void shouldCoverEveryMarkdownElement() throws Exception {
      // Given — the card switched to react-markdown + remark-gfm, and all eight explanations in
      // V56 are flat prose. One row per element type is not the point: one row exercising ALL of
      // them is, since a missing branch is invisible rather than broken.
      String markdown = markdownExplanation();

      // Then — heading, bold, bullet list, numbered list, inline code, fenced block, GFM table,
      // and a link. Asserted as separate lines so a failure names the element that went missing.
      assertThat(markdown).contains("## ");
      assertThat(markdown).contains("**");
      assertThat(markdown).contains("* `");
      assertThat(markdown).contains("1. ");
      assertThat(markdown).contains("```java");
      assertThat(markdown).contains("| --- |");
      assertThat(markdown).contains("](https://");
    }

    @Test
    @DisplayName("should keep the plain-prose explanations alongside it")
    void shouldKeepThePlainTextCase() throws Exception {
      // Given — Gemini does not always answer in Markdown, so text with no syntax in it has to
      // render as text. Rewriting V56's rows into Markdown would have traded that working case
      // away for this missing one.
      assertThat(
              scalar(
                  "SELECT count(*) FROM socialapp.t_explanations"
                      + " WHERE explanation_content NOT LIKE '%**%'"
                      + "   AND explanation_content NOT LIKE '%## %'"))
          .isGreaterThan(0);
    }
  }
}
