-- Lets search compare Vietnamese text with/without diacritics as equivalent
-- (e.g. "lap trinh" matches "lập trình"), applied to both stored data and the search
-- input at query time via unaccent(...) in the search repository queries.
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;
