-- Production 2026-08-31: the MINIO_PUBLIC_URL secret carried a trailing slash
-- (https://files.elitenexus.id.vn/), and docker-compose.prod.yml feeds it straight into
-- MINIO_URL. The seed builds media URLs as ${minioUrl}/<bucket>/<key>, so every seeded row
-- landed as "https://files.elitenexus.id.vn//<bucket>/<key>". MinIO answers the doubled-slash
-- path with HTTP 400 and the browser sends it verbatim, so every seeded avatar, cover photo and
-- post image rendered broken even though the objects themselves were uploaded fine.
--
-- MinIOConfig.setUrl now strips the trailing slash so new uploads are clean; this collapses the
-- doubled slash in the rows already written. The regex only touches the separator between the
-- host and the first path segment (scheme://host// -> scheme://host/), never a slash inside a
-- key.
--
-- Idempotent and safe everywhere: on a database seeded with a correct URL, or never seeded, the
-- WHERE predicates match nothing.

UPDATE socialapp.t_users
   SET profile_picture_url = regexp_replace(profile_picture_url, '^(https?://[^/]+)//', '\1/')
 WHERE profile_picture_url ~ '^https?://[^/]+//';

UPDATE socialapp.t_users
   SET cover_image_url = regexp_replace(cover_image_url, '^(https?://[^/]+)//', '\1/')
 WHERE cover_image_url ~ '^https?://[^/]+//';

UPDATE socialapp.t_posts
   SET images = regexp_replace(images::text, '(https?://[^/"]+)//', '\1/', 'g')::jsonb
 WHERE images::text ~ 'https?://[^/"]+//';
