package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
}
