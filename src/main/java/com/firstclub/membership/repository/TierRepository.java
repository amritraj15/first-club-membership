package com.firstclub.membership.repository;

import com.firstclub.membership.domain.Tier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TierRepository extends JpaRepository<Tier, Long> {
    List<Tier> findAllByOrderByRankAsc();

    List<Tier> findAllByOrderByRankDesc();
}
