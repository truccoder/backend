-- Turns username from a decorative column into an identifier the API can route on.
--
-- The public profile URL is /u/{username}, chosen over /u/{id} so a stranger cannot walk the user
-- list by counting ids upwards. That only works if every user has a username and no two users share
-- one — neither of which was true. Measured 2026-08-04, before this migration:
--
--     33 users · 32 with a username · 1 NULL · 32 distinct
--
-- The 32 are seed data. The single NULL (id 9026) is the only account that registered through the
-- real API, because RegisterRequestDto never accepted a username and AuthService never set one —
-- so that NULL is not an anomaly, it is what every future real signup would have looked like.

-- 1. Backfill, before any constraint exists to trip over.
--
-- Written for "however many rows are missing", not for the one row that happens to be missing
-- today: this migration also has to be correct on a database restored from an older dump.
--
-- The slug is unaccent(lower(full_name)) with everything that is not a letter or digit collapsed
-- into a single hyphen — "Trần Phú Thịnh" becomes "tran-phu-thinh". unaccent() is what the search
-- queries in this codebase already use for the same job (see V31), so the transliteration rules
-- stay in one place.
WITH slugged AS (
    SELECT
        id,
        NULLIF(
            trim(BOTH '-' FROM regexp_replace(lower(unaccent(coalesce(full_name, ''))),
                                              '[^a-z0-9]+', '-', 'g')),
            ''
        ) AS base
    FROM socialapp.t_users
    WHERE username IS NULL
),
-- A name can slug to something another user already holds, and two users can slug to the same
-- thing as each other. Both cases are resolved by suffixing the user's id.
numbered AS (
    SELECT
        s.id,
        -- Fall back to the id when the name gives nothing usable (empty, or punctuation only).
        coalesce(s.base, 'user-' || s.id) AS base,
        row_number() OVER (PARTITION BY coalesce(s.base, 'user-' || s.id) ORDER BY s.id) AS n
    FROM slugged s
)
UPDATE socialapp.t_users u
SET username = CASE
                   WHEN n.n = 1 AND NOT EXISTS (
                       SELECT 1 FROM socialapp.t_users x
                       WHERE lower(x.username) = n.base
                   ) THEN n.base
                   -- Suffixed with the id, not with a counter: the id is already unique, so the
                   -- result is unique among everything this statement writes, in one pass and
                   -- without a PL/pgSQL retry loop. A collision remains theoretically possible if
                   -- some existing user literally holds "<slug>-<id>" — in that case the unique
                   -- index below fails the migration loudly, which is the right outcome; it must
                   -- never resolve itself by writing a duplicate.
                   ELSE n.base || '-' || n.id
               END
FROM numbered n
WHERE u.id = n.id;

-- 2. Now the column can carry the guarantee the routing depends on.
ALTER TABLE socialapp.t_users ALTER COLUMN username SET NOT NULL;

-- Unique on lower(username), not on the raw column. "Ada" and "ada" are the same handle to anyone
-- reading a URL, but Postgres compares text case-sensitively, so a plain UNIQUE would happily let
-- both exist and leave /u/ada ambiguous. The expression index is also what makes
-- findByUsernameIgnoreCase an index lookup rather than a sequential scan.
CREATE UNIQUE INDEX uq_users_username_lower ON socialapp.t_users (lower(username));
