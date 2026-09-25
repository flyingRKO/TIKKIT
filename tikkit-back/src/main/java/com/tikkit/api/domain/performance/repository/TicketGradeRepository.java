package com.tikkit.api.domain.performance.repository;

import com.tikkit.api.domain.performance.entity.TicketGrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketGradeRepository extends JpaRepository<TicketGrade, Long> {
}
