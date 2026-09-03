-- backend-plan B33: GET /v1/api/search grew a projects branch and a roadmaps branch
-- (ProjectRepository.searchIds, RoadmapRepository.search). Both do the same unanchored
-- f_unaccent(lower(col)) LIKE '%...%' the people/posts/books branches do, and a leading '%'
-- makes a B-tree useless, so without these the new branches sequential-scan t_projects and
-- t_roadmaps on every search.
--
-- Same pg_trgm GIN + f_unaccent wrapper as V48 (see that migration for why the wrapper has to
-- be f_unaccent and not plain unaccent, and why editing this file after it applies breaks
-- validate-on-migrate). V94 already added a `search_vector` tsvector to both tables, but that
-- column is unused and 'simple' tsvector does not do partial-word matching anyway ('roadmap'
-- vs 'roadmaps') -- the trigram path is what carries substring search across the whole product.
--
-- The jsonb branches of the project query -- tags, and each position's required_skills -- are
-- not indexed here: jsonb_array_elements_text unnests before the LIKE, so no expression index
-- helps, and at this table's scale (hundreds of projects) the scan inside the EXISTS is cheap.

-- ProjectRepository.searchIds -- title / description
CREATE INDEX IF NOT EXISTS idx_projects_title_trgm
  ON socialapp.t_projects
  USING gin (public.f_unaccent(lower(title)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_projects_description_trgm
  ON socialapp.t_projects
  USING gin (public.f_unaccent(lower(description)) gin_trgm_ops);

-- RoadmapRepository.search -- name / description. The catalogue is ~12 rows (see V76), so the
-- planner will pick a seq scan and be right to; these exist so one query shape serves every
-- searchable table without an exception, matching the note V94 left on its own roadmap index.
CREATE INDEX IF NOT EXISTS idx_roadmaps_name_trgm
  ON socialapp.t_roadmaps
  USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_roadmaps_description_trgm
  ON socialapp.t_roadmaps
  USING gin (public.f_unaccent(lower(description)) gin_trgm_ops);
