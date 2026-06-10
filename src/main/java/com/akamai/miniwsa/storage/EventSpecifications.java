package com.akamai.miniwsa.storage;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Specification builders for filtered queries against {@link EventEntity}.
 *
 * <p>Each filter is optional — null means "do not filter on this column".
 * Composed by callers and passed to {@code JpaSpecificationExecutor.findAll}.
 * Unlike a JPQL {@code (:p IS NULL OR e.col = :p)} pattern, null filters
 * are simply skipped, so Postgres never sees an untyped null parameter.
 */
final class EventSpecifications {

    private EventSpecifications() {
    }

    static Specification<EventEntity> samples(Integer configId, Instant from, Instant to,
                                              Category category, Action action) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            if (configId != null) {
                predicates.add(cb.equal(root.get("configId"), configId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("timestamp"), to));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("ruleCategory"), category));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}