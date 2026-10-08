package com.tikkit.api.domain.venue.repository;

import com.tikkit.api.domain.venue.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
}
