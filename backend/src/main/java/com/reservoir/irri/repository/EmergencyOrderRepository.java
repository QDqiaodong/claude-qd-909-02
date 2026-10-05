package com.reservoir.irri.repository;

import com.reservoir.irri.domain.EmergencyOrder;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmergencyOrderRepository extends JpaRepository<EmergencyOrder, Long> {

    List<EmergencyOrder> findAllByOrderByCreatedAtDescIdDesc();

    List<EmergencyOrder> findByCanalSegmentIdOrderByCreatedAtDescIdDesc(Long canalSegmentId);

    List<EmergencyOrder> findByStatusIn(List<String> statuses);

    List<EmergencyOrder> findByWaterLevelIdAndStatusIn(Long waterLevelId, List<String> statuses);

    boolean existsByOrderNo(String orderNo);

    /**
     * 同渠段、量测时段重叠且尚未结束（不含 RESOLVED / MANUAL）的处置单。
     * 创建前在渠段行锁内调用，保证两个值班员同时发起也只会成功一张。
     */
    @Query("select o from EmergencyOrder o where o.canalSegmentId = :segmentId "
            + "and o.status in ('PENDING','HANDLING','REVIEW') "
            + "and o.sourceWindowStartAt < :end and o.sourceWindowEndAt > :start "
            + "order by o.id desc")
    List<EmergencyOrder> findOpenOverlap(@Param("segmentId") Long segmentId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EmergencyOrder o where o.id = :id")
    Optional<EmergencyOrder> findForUpdate(@Param("id") Long id);
}
