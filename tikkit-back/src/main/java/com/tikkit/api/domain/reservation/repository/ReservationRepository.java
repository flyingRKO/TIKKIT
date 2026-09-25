package com.tikkit.api.domain.reservation.repository;

import com.tikkit.api.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
