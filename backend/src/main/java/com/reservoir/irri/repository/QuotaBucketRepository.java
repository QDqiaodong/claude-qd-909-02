package com.reservoir.irri.repository;

import com.reservoir.irri.domain.QuotaBucket;
import com.reservoir.irri.domain.QuotaBucketId;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaBucketRepository extends JpaRepository<QuotaBucket, QuotaBucketId> {

    @Modifying
    @Query(value = "insert ignore into water_quota_bucket (canal_segment_id, allot_date) values (:segmentId, :day)",
            nativeQuery = true)
    int ensureBucket(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from QuotaBucket b where b.id.canalSegmentId = :segmentId and b.id.allotDate = :day")
    QuotaBucket findForUpdate(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);
}
