package com.reservoir.irri.repository;

import com.reservoir.irri.domain.GateOperation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GateOperationRepository extends JpaRepository<GateOperation, Long> {

    boolean existsByOperationNo(String operationNo);

    Optional<GateOperation> findByOperationNo(String operationNo);

    List<GateOperation> findByGateIdOrderByOperateTimeDesc(Long gateId);

    /** 该闸门最近一次操作（按时间倒序第一条） */
    @Query("select o from GateOperation o where o.gateId = :gateId "
            + "order by o.operateTime desc, o.id desc")
    List<GateOperation> findLatestByGate(@Param("gateId") Long gateId,
                                         org.springframework.data.domain.Pageable pageable);

    @Query("select count(o) from GateOperation o where o.status = :status")
    long countByStatus(@Param("status") String status);

    /** 同闸门是否存在晚于指定时刻的操作记录 */
    @Query("select count(o) from GateOperation o where o.gateId = :gateId and o.operateTime > :time")
    long countLaterThan(@Param("gateId") Long gateId, @Param("time") LocalDateTime time);

    /** 同闸门是否存在调节幅度超过阈值的记录（统计用） */
    @Query("select count(o) from GateOperation o where o.adjustRange > :range")
    long countOverRange(@Param("range") BigDecimal range);

    List<GateOperation> findByEmergencyOrderIdOrderByOperateTimeDescIdDesc(Long emergencyOrderId);

    long countByGateIdAndStatus(Long gateId, String status);

    long countByGateIdAndStatusAndEmergencyOrderIdIsNotNull(Long gateId, String status);
}
