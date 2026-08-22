package com.socialapp.posts.entity.enums;

/**
 * What a reader felt about a post.
 *
 * <p>The first five are the general-purpose set this product started with. {@code INSIGHT} and
 * {@code CLAP} were added beside them because the reactions are part of the argument for what this
 * network is: a place where the useful response to a post is "this taught me something" or "this
 * was well made", neither of which {@code LIKE} or {@code LOVE} can say. The clients label them in
 * that register — <i>Hữu ích · Sáng tỏ · Ghi nhận</i> — but the labels are a client concern and the
 * wire values stay in English like every other enum here.
 *
 * <p><b>Added, never renamed.</b> {@code t_post_reactions.reaction_type} is a plain {@code
 * VARCHAR} storing {@code name()} through {@code @Enumerated(EnumType.STRING)} — there is no check
 * constraint to migrate, so a new constant needs no migration, but renaming an existing one would
 * leave rows whose stored text no longer resolves and every read of them would throw. Widening
 * this enum widens {@code ReactionType} in the OpenAPI contract, so the generated client has to be
 * regenerated in step.
 *
 * <p>Nothing switches exhaustively on this type: reactions are counted by grouping on the column
 * ({@code PostReactionRepository.countByType}) and one row per (user, post) is
 * upserted whatever the value, so the new constants need no per-constant handling anywhere. The
 * reputation award for receiving a reaction is likewise per-reaction, not per-type.
 */
public enum ReactionType {
  LIKE,
  LOVE,
  HAHA,
  CRY,
  ANGRY,

  /** "This taught me something" — the reaction the general-purpose set has no word for. */
  INSIGHT,

  /** Recognition of craft: the post was well made, whether or not it was new to the reader. */
  CLAP
}
