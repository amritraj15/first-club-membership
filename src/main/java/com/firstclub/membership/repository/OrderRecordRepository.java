package com.firstclub.membership.repository;

import com.firstclub.membership.domain.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OrderRecordRepository extends JpaRepository<OrderRecord, Long> {

    List<OrderRecord> findByUserIdAndCancelledFalseAndPlacedAtAfter(Long userId, Instant since);
}
