package com.socialapp.knowledge.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** What the reader asks for when they press "explain this post for me". */
@Data
public class ExplainRequestDto {

  /**
   * Free-text complaint about a previous explanation ("the last part was still too abstract"),
   * pasted into the prompt so the model can take a different run at the same post.
   */
  private String feedbackNote;

  /**
   * The language the answer must come back in, as a BCP-47 tag — {@code "vi"}, {@code "en"},
   * {@code "en-GB"}.
   *
   * <p>Without it the prompt's only instruction was "respond in the same language as the original
   * post", which produced a genuinely mixed card: a Vietnamese post explained in English, under
   * Vietnamese section labels that come from the client's own string table. This is the one place
   * in the product where what the reader sees ignored their VI/EN choice, and the client had no
   * way to say otherwise — the endpoint does not read {@code Accept-Language} either.
   *
   * <p>Optional. Absent means the old behaviour, which is the right default for a caller that has
   * no locale to declare rather than a wrong guess made on its behalf.
   *
   * <p><b>Validated, and that is not a formality.</b> This value is concatenated into a prompt, so
   * an unconstrained string is an instruction channel into the model — "ignore the rules above and
   * …" is a valid {@code String} and would have been passed straight through. The pattern admits
   * language tags and nothing else, which leaves no room for a sentence. {@code
   * ExplanationService} narrows it further by resolving the tag through {@link java.util.Locale}
   * and sending the resolved display name, so what reaches the prompt is a word from the JDK's
   * table rather than anything the caller typed.
   */
  @Pattern(
      regexp = "^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$",
      message = "language must be a BCP-47 tag such as 'vi', 'en' or 'en-GB'")
  private String language;

  /**
   * Whether the reader's vault notes may be used as context for <em>this one</em> explanation.
   *
   * <p><b>THE SWITCH ALREADY EXISTED; IT JUST HAD NO DIAL.</b> {@code
   * ExplanationService#loadVaultContext} returns null unless the user holds a {@code BIDIRECTIONAL}
   * token, so whether the model sees a reader's note titles has been decided all along by a
   * permission they picked once, in a token dialog, possibly months earlier. That is the wrong
   * grain: the answer is not the same for every post. Somebody reading up on a topic they have
   * notes about wants those notes brought in; somebody reading outside their field wants a plain
   * explanation rather than one bent toward what they already wrote down.
   *
   * <p>{@code null} means "yes", so a client that does not send the field behaves exactly as
   * before. Only an explicit {@code false} turns the context off — an absent field is not a
   * decision and must not read as one.
   */
  private Boolean useVaultContext;
}
