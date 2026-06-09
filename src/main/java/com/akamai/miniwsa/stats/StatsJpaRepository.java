package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.storage.EventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StatsJpaRepository extends JpaRepository<EventEntity, Long> {

    @Query("""
            SELECT COUNT(e) FROM EventEntity e
            WHERE e.timestamp >= :from AND e.timestamp < :to
              AND (:configId IS NULL OR e.configId = :configId)
            """)
    long countTotal(@Param("configId") Integer configId,
                    @Param("from") Instant from,
                    @Param("to") Instant to);

    @Query("""
            SELECT new com.akamai.miniwsa.stats.CategoryAggregation(
                e.ruleCategory, COUNT(e), AVG(e.threatScore)
            )
            FROM EventEntity e
            WHERE e.timestamp >= :from AND e.timestamp < :to
              AND (:configId IS NULL OR e.configId = :configId)
            GROUP BY e.ruleCategory
            """)
    List<CategoryAggregation> aggregateByCategory(@Param("configId") Integer configId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to);

    @Query("""
            SELECT new com.akamai.miniwsa.stats.ActionAggregation(
                e.action, COUNT(e)
            )
            FROM EventEntity e
            WHERE e.timestamp >= :from AND e.timestamp < :to
              AND (:configId IS NULL OR e.configId = :configId)
            GROUP BY e.action
            """)
    List<ActionAggregation> aggregateByAction(@Param("configId") Integer configId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    @Query("""
            SELECT new com.akamai.miniwsa.stats.AttackerAggregation(
                e.clientIp, COUNT(e), AVG(e.threatScore)
            )
            FROM EventEntity e
            WHERE e.timestamp >= :from AND e.timestamp < :to
              AND (:configId IS NULL OR e.configId = :configId)
            GROUP BY e.clientIp
            ORDER BY COUNT(e) DESC, e.clientIp ASC
            """)
    List<AttackerAggregation> topAttackers(@Param("configId") Integer configId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Pageable pageable);

    @Query("""
            SELECT new com.akamai.miniwsa.stats.PathAggregation(
                e.path, COUNT(e)
            )
            FROM EventEntity e
            WHERE e.path IS NOT NULL
              AND e.timestamp >= :from AND e.timestamp < :to
              AND (:configId IS NULL OR e.configId = :configId)
            GROUP BY e.path
            ORDER BY COUNT(e) DESC, e.path ASC
            """)
    List<PathAggregation> topPaths(@Param("configId") Integer configId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);
}
