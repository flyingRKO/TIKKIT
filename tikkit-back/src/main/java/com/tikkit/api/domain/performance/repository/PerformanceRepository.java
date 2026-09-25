package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.Performance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {
}
