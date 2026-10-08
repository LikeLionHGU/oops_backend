package com.example.oops.domain;

import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor
public class OcrRegion {
    private Double boxX;
    private Double boxY;
    private Double boxWidth;
    private Double boxHeight;
    private String trackId;
    private Integer observations;
    private Integer slotTextChanges;

    public OcrRegion(Double x, Double y, Double width, Double height, String trackId,
                     Integer observations, Integer slotTextChanges) {
        this.boxX = x; this.boxY = y; this.boxWidth = width; this.boxHeight = height;
        this.trackId = trackId; this.observations = observations; this.slotTextChanges = slotTextChanges;
    }

    public boolean hasGeometry() {
        return boxX != null && boxY != null && boxWidth != null && boxHeight != null
                && Double.isFinite(boxX) && Double.isFinite(boxY) && Double.isFinite(boxWidth) && Double.isFinite(boxHeight)
                && boxX >= 0 && boxY >= 0 && boxWidth > 0 && boxHeight > 0
                && boxX + boxWidth <= 1.000001 && boxY + boxHeight <= 1.000001;
    }
}
