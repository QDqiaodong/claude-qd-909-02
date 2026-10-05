package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuotaBucketId implements Serializable {

    @Column(name = "canal_segment_id")
    private Long canalSegmentId;

    @Column(name = "allot_date")
    private LocalDate allotDate;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuotaBucketId that)) {
            return false;
        }
        return Objects.equals(canalSegmentId, that.canalSegmentId) && Objects.equals(allotDate, that.allotDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canalSegmentId, allotDate);
    }
}
