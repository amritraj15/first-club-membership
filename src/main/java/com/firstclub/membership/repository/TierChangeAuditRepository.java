package com.firstclub.membership.repository;

import com.firstclub.membership.domain.TierChangeAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TierChangeAuditRepository extends JpaRepository<TierChangeAudit, Long> {

    List<TierChangeAudit> findByUserIdOrderByChangedAtAsc(Long userId);
}
