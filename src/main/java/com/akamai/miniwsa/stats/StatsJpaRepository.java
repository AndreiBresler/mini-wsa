package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.storage.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Marker repository for {@link EventEntity}. All analytics queries are built
 * via the JPA Criteria API in {@link StatsRepository} — no JPQL strings, no
 * SQL strings, anywhere in the codebase. This interface exists only to give
 * Spring Data a managed bean and to expose basic CRUD if ever needed.
 */
public interface StatsJpaRepository extends JpaRepository<EventEntity, Long> {
}
