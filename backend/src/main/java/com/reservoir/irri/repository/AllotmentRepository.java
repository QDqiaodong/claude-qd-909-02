package com.reservoir.irri.repository;

import com.reservoir.irri.domain.Allotment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllotmentRepository extends JpaRepository<Allotment, Long> {

    List<Allotment> findByPlanIdOrderByAllotDateAsc(Long planId);

    List<Allotment> findByCanalSegmentIdOrderByAllotDateAsc(Long canalSegmentId);

    @Query("select a from Allotment a where a.allotDate >= :from and a.allotDate <= :to "
            + "order by a.allotDate asc, a.id asc")
    List<Allotment> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 同一渠段同一配水日的流量合计（不含已取消）。
     * 没有任何记录时 sum 返回 null，由调用方按 0 处理。
     */
    @Query("select sum(a.flow) from Allotment a where a.canalSegmentId = :segmentId "
            + "and a.allotDate = :day and a.status <> 'CANCELED'")
    BigDecimal sumFlowBySegmentAndDate(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);

    @Query("select a from Allotment a where a.canalSegmentId = :segmentId and a.allotDate = :day "
            + "and a.status <> 'CANCELED' order by a.id asc")
    List<Allotment> findActiveBySegmentAndDate(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);

    /** 某渠段日期区间内已排期 / 配水中的配水条（停水窗口扫描用） */
    @Query("select a from Allotment a where a.canalSegmentId = :segmentId "
            + "and a.allotDate >= :from and a.allotDate <= :to and a.status in ('PLANNED','SUPPLYING') "
            + "order by a.allotDate asc, a.id asc")
    List<Allotment> findActiveBetween(@Param("segmentId") Long segmentId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    @Query("select count(a) from Allotment a where a.status = :status")
    long countByStatus(@Param("status") String status);

    Optional<Allotment> findTopByPlanIdOrderByAllotDateAsc(Long planId);
}
