package com.reservoir.irri.repository;

import com.reservoir.irri.domain.IrrigationPlan;
import com.reservoir.irri.dto.PlanStatusStatDTO;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IrrigationPlanRepository extends JpaRepository<IrrigationPlan, Long> {

    boolean existsByPlanCode(String planCode);

    Optional<IrrigationPlan> findByPlanCode(String planCode);

    List<IrrigationPlan> findAllByOrderByStartDateDescIdDesc();

    List<IrrigationPlan> findByCanalSegmentIdOrderByStartDateDesc(Long canalSegmentId);

    /** 与指定周期有重叠的计划（同一渠段） */
    @Query("select p from IrrigationPlan p where p.canalSegmentId = :segmentId "
            + "and p.startDate <= :end and p.endDate >= :start")
    List<IrrigationPlan> findOverlap(@Param("segmentId") Long segmentId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);

    @Query("select new com.reservoir.irri.dto.PlanStatusStatDTO(p.status, count(p)) "
            + "from IrrigationPlan p group by p.status")
    List<PlanStatusStatDTO> statByStatus();

    @Query("select count(p) from IrrigationPlan p where p.status = :status")
    long countByStatus(@Param("status") String status);
}
