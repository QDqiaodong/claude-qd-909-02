package com.reservoir.irri.repository;

import com.reservoir.irri.domain.CanalSegment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanalSegmentRepository extends JpaRepository<CanalSegment, Long> {

    boolean existsBySegmentCode(String segmentCode);

    Optional<CanalSegment> findBySegmentCode(String segmentCode);

    List<CanalSegment> findByCanalIdOrderBySortNoAsc(Long canalId);

    List<CanalSegment> findAllByOrderByCanalIdAscSortNoAsc();

    @Query("select s from CanalSegment s where s.status = :status order by s.canalId asc, s.sortNo asc")
    List<CanalSegment> findByStatus(@Param("status") String status);

    @Query("select count(s) from CanalSegment s where s.status = :status")
    long countByStatus(@Param("status") String status);

    /** 险情发起时锁渠段，串行化「同渠段同时段」唯一性校验 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CanalSegment s where s.id = :id")
    Optional<CanalSegment> findForUpdate(@Param("id") Long id);
}
