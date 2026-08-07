-- Covers were stored as the presigned URL returned at upload time, which expires after 24h, so
-- every book cover in the app went dead a day after it was uploaded. The fix is the same shape
-- as the download path already used: keep the object key, sign it when it is read.
--
-- The column is renamed rather than reused: a column called *_url holding an object key is how
-- this bug gets reintroduced.
ALTER TABLE socialapp.t_books
    RENAME COLUMN cover_image_url TO cover_image_key;

-- Recover the key from whatever is already stored. A presigned URL looks like
--   http://host:9000/book-covers/covers/<authorId>/<uuid>.<ext>?X-Amz-Algorithm=...
-- so the key is the path after the bucket segment, with the query string dropped. Rows that do
-- not match that shape (already a key, or something unexpected) are left untouched by the WHERE.
UPDATE socialapp.t_books
   SET cover_image_key = substring(split_part(cover_image_key, '?', 1) from '/book-covers/(.*)$')
 WHERE cover_image_key LIKE '%/book-covers/%';

-- Anything still carrying a signature did not match the expected layout. Such a value is already
-- expired and unrecoverable, so blank it rather than leave a dead URL that reads like a key: a
-- null cover renders as "no cover", a bogus key renders as a broken image.
UPDATE socialapp.t_books
   SET cover_image_key = NULL
 WHERE cover_image_key LIKE '%X-Amz-Signature%';
