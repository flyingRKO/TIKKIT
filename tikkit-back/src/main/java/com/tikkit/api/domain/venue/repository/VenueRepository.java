package com.tikkit.api.domain.venue.repository;

import com.tikkit.api.domain.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
}
