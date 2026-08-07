ALTER TABLE t_posts
ADD COLUMN code_snippet_details JSONB,
ADD COLUMN article_details JSONB,
ADD COLUMN qna_details JSONB,
ADD COLUMN poll_details JSONB,
ADD COLUMN link_details JSONB;
