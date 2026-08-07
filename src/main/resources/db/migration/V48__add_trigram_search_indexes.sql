-- Search does substring matching -- unaccent(lower(col)) LIKE '%...%' -- on three tables.
-- A leading '%' makes a B-tree index useless, so every keystroke in the search box was a
-- sequential scan of t_posts, t_books and t_users. pg_trgm's GIN operator class is the one
-- index type that can answer an unanchored LIKE.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- Why a wrapper instead of indexing unaccent(...) directly:
--
-- unaccent() is declared STABLE, not IMMUTABLE, because it reads its rules from a dictionary
-- that a superuser can reload at runtime. An expression index requires an IMMUTABLE function,
-- so "CREATE INDEX ... (unaccent(lower(col)))" is rejected outright.
--
-- The wrapper pins the dictionary by name -- unaccent('public.unaccent', $1) -- so the result
-- no longer depends on the session's search_path, and asserts IMMUTABLE. The assertion is a
-- promise, not a proof: if anyone ever edits unaccent.rules on disk, every index below has to
-- be REINDEXed or it will return wrong answers.
--
-- IMPORTANT for anyone touching the search queries: the indexes below are built on
-- f_unaccent(lower(col)). Postgres matches an index to a query by comparing the parsed
-- expression, so a query that still calls plain unaccent(...) will simply not use these
-- indexes -- silently, with no error and no warning, just the old sequential scan. The
-- queries in PostRepository, BookRepository and UserRepository were changed in the same
-- commit for exactly this reason.
CREATE OR REPLACE FUNCTION public.f_unaccent(text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$ SELECT public.unaccent('public.unaccent', $1) $$;

-- PostRepository.searchByContentOrEventName
CREATE INDEX IF NOT EXISTS idx_posts_content_trgm
  ON socialapp.t_posts
  USING gin (public.f_unaccent(lower(content)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_posts_event_title_trgm
  ON socialapp.t_posts
  USING gin (public.f_unaccent(lower(event_details ->> 'eventTitle')) gin_trgm_ops);

-- BookRepository.search
CREATE INDEX IF NOT EXISTS idx_books_title_trgm
  ON socialapp.t_books
  USING gin (public.f_unaccent(lower(title)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_books_description_trgm
  ON socialapp.t_books
  USING gin (public.f_unaccent(lower(description)) gin_trgm_ops);

-- UserRepository.search
CREATE INDEX IF NOT EXISTS idx_users_full_name_trgm
  ON socialapp.t_users
  USING gin (public.f_unaccent(lower(full_name)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_users_username_trgm
  ON socialapp.t_users
  USING gin (public.f_unaccent(lower(username)) gin_trgm_ops);
