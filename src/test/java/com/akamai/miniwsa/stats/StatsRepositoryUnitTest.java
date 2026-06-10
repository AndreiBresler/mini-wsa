package com.akamai.miniwsa.stats;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.storage.EventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class StatsRepositoryUnitTest {

    private static final Instant FROM = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-21T00:00:00Z");

    private EntityManager em;
    private CriteriaBuilder cb;
    private Root<EventEntity> root;
    private StatsRepository repository;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        cb = mock(CriteriaBuilder.class);
        root = mock(Root.class);

        when(em.getCriteriaBuilder()).thenReturn(cb);

        Path anyPath = mock(Path.class);
        when(root.get(any(String.class))).thenReturn(anyPath);

        Predicate predicate = mock(Predicate.class);
        when(cb.equal(any(Expression.class), any())).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
        when(cb.isNotNull(any(Expression.class))).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        Expression countExpr = mock(Expression.class);
        Expression avgExpr = mock(Expression.class);
        when(cb.count(any(Expression.class))).thenReturn(countExpr);
        when(cb.avg(any(Expression.class))).thenReturn(avgExpr);

        Order order = mock(Order.class);
        when(cb.desc(any(Expression.class))).thenReturn(order);
        when(cb.asc(any(Expression.class))).thenReturn(order);

        repository = new StatsRepository(em);
    }

    @Test
    void countTotal_returns_value_from_query_and_applies_time_range_predicates() {
        CriteriaQuery<Long> longQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Long.class)).thenReturn(longQuery);
        when(longQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Long> typed = mock(TypedQuery.class);
        when(em.createQuery(longQuery)).thenReturn(typed);
        when(typed.getSingleResult()).thenReturn(42L);

        long result = repository.countTotal(14227, FROM, TO);

        assertThat(result).isEqualTo(42L);
        verify(cb).count(root);
        verify(cb).equal(any(Expression.class), eq(14227));
        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(FROM));
        verify(cb).lessThan(any(Expression.class), eq(TO));
    }

    @Test
    void countTotal_with_null_configId_skips_equal_predicate() {
        CriteriaQuery<Long> longQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Long.class)).thenReturn(longQuery);
        when(longQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Long> typed = mock(TypedQuery.class);
        when(em.createQuery(longQuery)).thenReturn(typed);
        when(typed.getSingleResult()).thenReturn(99L);

        repository.countTotal(null, FROM, TO);

        verify(cb).greaterThanOrEqualTo(any(Expression.class), eq(FROM));
        verify(cb).lessThan(any(Expression.class), eq(TO));
        verify(cb, never()).equal(any(Expression.class), any());
    }

    @Test
    void byCategory_maps_rows_to_enum_map_with_rounded_averages() {
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Object[]> typed = mock(TypedQuery.class);
        when(em.createQuery(objQuery)).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of(
                new Object[]{Category.INJECTION, 10L, 75.5},
                new Object[]{Category.XSS, 5L, 33.333333}
        ));

        Map<Category, CategoryStats> result = repository.byCategory(14227, FROM, TO);

        assertThat(result.get(Category.INJECTION)).isEqualTo(new CategoryStats(10L, 75.5));
        assertThat(result.get(Category.XSS).avgThreatScore()).isEqualTo(33.33);
        verify(cb).count(root);
        verify(cb).avg(any(Expression.class));
    }

    @Test
    void byCategory_null_average_becomes_zero() {
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Object[]> typed = mock(TypedQuery.class);
        when(em.createQuery(objQuery)).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{Category.BOT, 1L, null}
        ));

        Map<Category, CategoryStats> result = repository.byCategory(null, FROM, TO);

        assertThat(result.get(Category.BOT).avgThreatScore()).isEqualTo(0.0);
    }

    @Test
    void byAction_maps_rows_to_enum_map() {
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Object[]> typed = mock(TypedQuery.class);
        when(em.createQuery(objQuery)).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of(
                new Object[]{Action.DENY, 100L},
                new Object[]{Action.ALERT, 50L},
                new Object[]{Action.MONITOR, 25L}
        ));

        Map<Action, Long> result = repository.byAction(14227, FROM, TO);

        assertThat(result)
                .containsEntry(Action.DENY, 100L)
                .containsEntry(Action.ALERT, 50L)
                .containsEntry(Action.MONITOR, 25L);
    }

    @Test
    void topAttackers_applies_setMaxResults_and_maps_rows() {
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Object[]> typed = mock(TypedQuery.class);
        when(em.createQuery(objQuery)).thenReturn(typed);
        when(typed.setMaxResults(anyInt())).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of(
                new Object[]{"203.0.113.42", 30L, 80.0},
                new Object[]{"198.51.100.5", 15L, 45.5}
        ));

        List<TopAttacker> result = repository.topAttackers(14227, FROM, TO, 10);

        assertThat(result).containsExactly(
                new TopAttacker("203.0.113.42", 30L, 80.0),
                new TopAttacker("198.51.100.5", 15L, 45.5)
        );
        verify(typed).setMaxResults(10);
        verify(cb).desc(any(Expression.class));
        verify(cb).asc(any(Expression.class));
    }

    @Test
    void topPaths_adds_isNotNull_predicate_and_applies_limit() {
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Object[]> typed = mock(TypedQuery.class);
        when(em.createQuery(objQuery)).thenReturn(typed);
        when(typed.setMaxResults(anyInt())).thenReturn(typed);
        when(typed.getResultList()).thenReturn(List.of(
                new Object[]{"/api/v1/login", 100L},
                new Object[]{"/admin", 40L}
        ));

        List<TopPath> result = repository.topPaths(null, FROM, TO, 5);

        assertThat(result).containsExactly(
                new TopPath("/api/v1/login", 100L),
                new TopPath("/admin", 40L)
        );
        verify(cb).isNotNull(any(Expression.class));
        verify(typed).setMaxResults(5);
    }

    @Test
    void every_query_applies_the_time_range_predicates() {
        CriteriaQuery<Long> longQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        CriteriaQuery<Object[]> objQuery = mock(CriteriaQuery.class, Answers.RETURNS_SELF);
        when(cb.createQuery(Long.class)).thenReturn(longQuery);
        when(cb.createQuery(Object[].class)).thenReturn(objQuery);
        when(longQuery.from(EventEntity.class)).thenReturn(root);
        when(objQuery.from(EventEntity.class)).thenReturn(root);

        TypedQuery<Long> longTyped = mock(TypedQuery.class);
        TypedQuery<Object[]> objTyped = mock(TypedQuery.class);
        when(em.createQuery(longQuery)).thenReturn(longTyped);
        when(em.createQuery(objQuery)).thenReturn(objTyped);
        when(longTyped.getSingleResult()).thenReturn(0L);
        when(objTyped.setMaxResults(anyInt())).thenReturn(objTyped);
        when(objTyped.getResultList()).thenReturn((List) List.of());

        repository.countTotal(null, FROM, TO);
        repository.byCategory(null, FROM, TO);
        repository.byAction(null, FROM, TO);
        repository.topAttackers(null, FROM, TO, 10);
        repository.topPaths(null, FROM, TO, 10);

        verify(cb, times(5)).greaterThanOrEqualTo(any(Expression.class), eq(FROM));
        verify(cb, times(5)).lessThan(any(Expression.class), eq(TO));
    }
}
