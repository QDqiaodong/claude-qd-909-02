package com.reservoir.irri.repository;

import com.reservoir.irri.domain.Canal;
import com.reservoir.irri.dto.CanalGradeStatDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanalRepository extends JpaRepository<Canal, Long> {

    boolean existsByCanalCode(String canalCode);

    Optional<Canal> findByCanalCode(String canalCode);

    List<Canal> findAllByOrderByCanalCodeAsc();

    List<Canal> findByStatusOrderByCanalCodeAsc(String status);

    /** 渠道等级分布：左连渠段统计渠段数 */
    @Query("select new com.reservoir.irri.dto.CanalGradeStatDTO(c.canalGrade, count(distinct c.id), count(s.id)) "
            + "from Canal c left join CanalSegment s on s.canalId = c.id "
            + "group by c.canalGrade")
    List<CanalGradeStatDTO> statByCanalGrade();

    @Query("select count(c) from Canal c where c.status = :status")
    long countByStatus(@Param("status") String status);
}
