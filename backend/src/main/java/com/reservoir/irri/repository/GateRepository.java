package com.reservoir.irri.repository;

import com.reservoir.irri.domain.Gate;
import com.reservoir.irri.dto.GateTypeStatDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GateRepository extends JpaRepository<Gate, Long> {

    boolean existsByGateCode(String gateCode);

    Optional<Gate> findByGateCode(String gateCode);

    List<Gate> findByCanalSegmentIdOrderByGateCodeAsc(Long canalSegmentId);

    List<Gate> findAllByOrderByGateCodeAsc();

    /** 闸门型式分布：型式 → 闸门数、停用闸门数 */
    @Query("select new com.reservoir.irri.dto.GateTypeStatDTO(g.gateType, count(g), "
            + "count(case when g.status = 'DISABLED' then 1 else null end)) "
            + "from Gate g group by g.gateType")
    List<GateTypeStatDTO> statByGateType();

    @Query("select count(g) from Gate g where g.status = :status")
    long countByStatus(@Param("status") String status);
}
