-- Gemini has always returned externalLinks with every explanation and the column to keep them
-- never existed, so the "Read more" list the user saw before saving vanished the moment they
-- saved. Same jsonb + '[]' default as concepts/prerequisites next to it, so existing rows read
-- back as an empty list rather than null.
ALTER TABLE socialapp.t_explanations
    ADD COLUMN external_links JSONB DEFAULT '[]'::jsonb;
