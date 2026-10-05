package com.reservoir.irri.repository;

import com.reservoir.irri.domain.WaterQuotaHold;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaterQuotaHoldRepository extends JpaRepository<WaterQuotaHold, Long> {

    Optional<WaterQuotaHold> findFirstByWaterRequestIdAndStatusOrderByIdDesc(Long requestId, String status);

    List<WaterQuotaHold> findByWaterRequestIdInAndStatus(List<Long> requestIds, String status);

    List<WaterQuotaHold> findByWaterRequestIdInOrderByIdDesc(List<Long> requestIds);

    List<WaterQuotaHold> findByWaterRequestIdOrderByIdDesc(Long requestId);
}
