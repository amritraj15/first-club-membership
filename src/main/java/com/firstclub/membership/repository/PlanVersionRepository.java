package com.firstclub.membership.repository;

import com.firstclub.membership.domain.PlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {
    Optional<PlanVersion> findTopByPlanIdOrderByVersionNumberDesc(Long planId);
}
