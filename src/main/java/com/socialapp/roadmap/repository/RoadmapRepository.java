package com.socialapp.roadmap.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.roadmap.entity.RoadmapEntity;

@Repository
public interface RoadmapRepository extends JpaRepository<RoadmapEntity, Integer> {

  /**
   * Roadmaps whose name, description, or a node inside the track matches a free-text query —
   * backend-plan B33, extended by B36.
   *
   * <p>{@code /v1/api/search} did not cover roadmaps, so the search page's "Lộ trình" tab filtered
   * the whole catalogue client-side. That worked (the catalogue is ~12 rows and unpaginated) but
   * kept a second copy of the matching rule on the frontend; this is the one branch that removes
   * it. Same diacritics-insensitive substring shape as the other search branches — {@code query}
   * arrives pre-escaped for {@code LIKE}. Native only for {@code f_unaccent} + {@code ESCAPE};
   * there is no jsonb here. At this row count the planner will seq-scan and be right to.
   *
   * <p><b>B36:</b> the track's real content lives in its nodes, not the two columns on the
   * roadmap row — a reader searching "kafka" wants the track that teaches it, not one whose name
   * or blurb happens to say "kafka". The node branch is an {@code EXISTS}, not a {@code JOIN}: a
   * join would return one row per matching node and turn a track with several matches into
   * duplicate roadmaps in the result list. {@code V101} adds the matching trigram index on
   * {@code t_roadmap_nodes.name}. Node-level match reasoning ("matched via node X") is
   * deliberately not surfaced here — see the B36 note in backend-plan.md for why.
   */
  @Query(
      value =
          """
          SELECT r.* FROM socialapp.t_roadmaps r
          WHERE f_unaccent(lower(r.name))
                  LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\'
             OR f_unaccent(lower(coalesce(r.description, '')))
                  LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\'
             OR EXISTS (
                  SELECT 1 FROM socialapp.t_roadmap_nodes n
                  WHERE n.roadmap_id = r.id
                    AND f_unaccent(lower(n.name))
                          LIKE f_unaccent(lower(concat('%', :query, '%'))) ESCAPE '\\')
          ORDER BY r.name ASC
          LIMIT :size
          """,
      nativeQuery = true)
  List<RoadmapEntity> search(@Param("query") String query, @Param("size") int size);
}
