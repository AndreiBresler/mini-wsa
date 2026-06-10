package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.storage.EventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation queries over {@link EventEntity}, built entirely via the JPA
 * Criteria API — no JPQL strings, no SQL strings. The query graph is
 * constructed programmatically by {@link CriteriaBuilder}, the JPA provider
 * translates it to vendor SQL at runtime.
 *
 * <p><b>Why Criteria over @Query strings:</b> string-based queries fail
 * silently when a field is renamed, a typo creeps in, or a refactor moves
 * a column. The error surfaces only when that query path is exercised —
 * usually in production. Criteria expresses the query shape (projections,
 * predicates, group-by, order-by) in plain Java that the compiler validates
 * at the API level. Field references are still string literals
 * (e.g. {@code root.get("ruleCategory")}) because the JPA static metamodel
 * isn't generated in this build; adding {@code hibernate-jpamodelgen} would
 * make them compile-checked too — that's noted in the README as a planned
 * follow-up. Even without it, integration tests against real Postgres catch
 * any field-name drift before it ships.
 *
 * <p>Rounding (avg threat score → 2 decimals) lives in Java rather than the
 * query so the query stays portable across SQL dialects.
 */
@Repository
@Profile({"query", "consumer", "all"})
@Transactional(readOnly = true)
public class StatsRepository {

    private static final double ROUND_FACTOR = 100.0;

    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_CONFIG_ID = "configId";
    private static final String FIELD_RULE_CATEGORY = "ruleCategory";
    private static final String FIELD_ACTION = "action";
    private static final String FIELD_CLIENT_IP = "clientIp";
    private static final String FIELD_PATH = "path";
    private static final String FIELD_THREAT_SCORE = "threatScore";

    private final EntityManager em;

    public StatsRepository(EntityManager em) {
        this.em = em;
    }

    public long countTotal(Integer configId, Instant from, Instant to) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<EventEntity> root = q.from(EventEntity.class);
        q.select(cb.count(root))
                .where(timeAndConfig(cb, root, configId, from, to));
        return em.createQuery(q).getSingleResult();
    }

    public Map<Category, CategoryStats> byCategory(Integer configId, Instant from, Instant to) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<EventEntity> root = q.from(EventEntity.class);
        Path<Category> categoryPath = root.get(FIELD_RULE_CATEGORY);

        q.multiselect(categoryPath, cb.count(root), cb.avg(root.get(FIELD_THREAT_SCORE)))
                .where(timeAndConfig(cb, root, configId, from, to))
                .groupBy(categoryPath);

        List<Object[]> rows = em.createQuery(q).getResultList();
        Map<Category, CategoryStats> result = new EnumMap<>(Category.class);
        for (Object[] row : rows) {
            Category category = (Category) row[0];
            long count = (long) row[1];
            Double avg = (Double) row[2];
            result.put(category, new CategoryStats(count, round(avg)));
        }
        return result;
    }

    public Map<Action, Long> byAction(Integer configId, Instant from, Instant to) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<EventEntity> root = q.from(EventEntity.class);
        Path<Action> actionPath = root.get(FIELD_ACTION);

        q.multiselect(actionPath, cb.count(root))
                .where(timeAndConfig(cb, root, configId, from, to))
                .groupBy(actionPath);

        List<Object[]> rows = em.createQuery(q).getResultList();
        Map<Action, Long> result = new EnumMap<>(Action.class);
        for (Object[] row : rows) {
            result.put((Action) row[0], (long) row[1]);
        }
        return result;
    }

    public List<TopAttacker> topAttackers(Integer configId, Instant from, Instant to, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<EventEntity> root = q.from(EventEntity.class);
        Path<String> ipPath = root.get(FIELD_CLIENT_IP);

        q.multiselect(ipPath, cb.count(root), cb.avg(root.get(FIELD_THREAT_SCORE)))
                .where(timeAndConfig(cb, root, configId, from, to))
                .groupBy(ipPath)
                .orderBy(cb.desc(cb.count(root)), cb.asc(ipPath));

        List<Object[]> rows = em.createQuery(q).setMaxResults(limit).getResultList();
        List<TopAttacker> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String ip = (String) row[0];
            long count = (long) row[1];
            Double avg = (Double) row[2];
            result.add(new TopAttacker(ip, count, round(avg)));
        }
        return result;
    }

    public List<TopPath> topPaths(Integer configId, Instant from, Instant to, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<EventEntity> root = q.from(EventEntity.class);
        Path<String> pathPath = root.get(FIELD_PATH);

        q.multiselect(pathPath, cb.count(root))
                .where(cb.and(
                        cb.isNotNull(pathPath),
                        timeAndConfig(cb, root, configId, from, to)))
                .groupBy(pathPath)
                .orderBy(cb.desc(cb.count(root)), cb.asc(pathPath));

        List<Object[]> rows = em.createQuery(q).setMaxResults(limit).getResultList();
        List<TopPath> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new TopPath((String) row[0], (long) row[1]));
        }
        return result;
    }

    /**
     * Shared predicate: half-open time range (mandatory) plus optional
     * configId equality. Null configId means "across all configurations".
     */
    private Predicate timeAndConfig(CriteriaBuilder cb, Root<EventEntity> root,
                                    Integer configId, Instant from, Instant to) {
        List<Predicate> predicates = new ArrayList<>(3);
        predicates.add(cb.greaterThanOrEqualTo(root.get(FIELD_TIMESTAMP), from));
        predicates.add(cb.lessThan(root.get(FIELD_TIMESTAMP), to));
        if (configId != null) {
            predicates.add(cb.equal(root.get(FIELD_CONFIG_ID), configId));
        }
        return cb.and(predicates.toArray(new Predicate[0]));
    }

    private static double round(Double value) {
        if (value == null) {
            return 0.0;
        }
        return Math.round(value * ROUND_FACTOR) / ROUND_FACTOR;
    }
}
