-- backend-plan B36: RoadmapRepository.search now also matches a node's name inside the
-- track (EXISTS over t_roadmap_nodes), because a reader searching "kafka" wants the track
-- that teaches it, not one whose name or description happens to mention it. Same pg_trgm +
-- f_unaccent wrapper as V48/V100 -- see V48 for why the wrapper has to be f_unaccent and not
-- plain unaccent, and why editing this file after it applies breaks validate-on-migrate.
--
-- Node description is not indexed here: RoadmapRepository.search only matches n.name, to keep
-- the branch to "does this track teach that topic" rather than pulling in every node whose
-- longer-form blurb happens to mention the term.
CREATE INDEX IF NOT EXISTS idx_roadmap_nodes_name_trgm
  ON socialapp.t_roadmap_nodes
  USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);
