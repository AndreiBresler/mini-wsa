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

    private static final String FIELD_CONFIG_ID    = "configId";
    private static final String FIELD_TIMESTAMP    = "timestamp";
    private static final String FIELD_RULE_CATEGORY = "ruleCategory";
    private static final String FIELD_ACTION       = "action";

    private EventSpecifications() {
    }

    static Specification<EventEntity> samples(Integer configId, Instant from, Instant to,
                                              Category category, Action action) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(5);
            if (configId != null) {
                predicates.add(cb.equal(root.get(FIELD_CONFIG_ID), configId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get(FIELD_TIMESTAMP), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get(FIELD_TIMESTAMP), to));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get(FIELD_RULE_CATEGORY), category));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get(FIELD_ACTION), action));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}