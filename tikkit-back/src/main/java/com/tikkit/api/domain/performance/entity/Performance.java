package com.tikkit.api.domain.performance.entity;

import com.tikkit.api.common.entity.BaseTimeEntity;
import com.tikkit.api.domain.venue.entity.Venue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Entity
@Table(name = "performances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Performance extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PerformanceCategory category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 500)
    private String posterUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private Integer runningMinutes;

    @Column(nullable = false, length = 20)
    private String ageRating;

    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PerformanceStatus status;

    @Builder
    private Performance(String title, PerformanceCategory category, String description, String posterUrl,
                         Venue venue, Integer runningMinutes, String ageRating, LocalDate startDate,
                         LocalDate endDate, PerformanceStatus status) {
        this.title = title;
        this.category = category;
        this.description = description;
        this.posterUrl = posterUrl;
        this.venue = venue;
        this.runningMinutes = runningMinutes;
        this.ageRating = ageRating;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
    }
}
