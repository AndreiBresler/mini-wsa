package com.akamai.miniwsa.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventJpaRepository
        extends JpaRepository<EventEntity, Long>, JpaSpecificationExecutor<EventEntity> {
}