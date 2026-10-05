package com.reservoir.irri.repository;

import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.dto.RequestStatusStatDTO;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaterRequestRepository extends JpaRepository<WaterRequest, Long> {

    boolean existsByRequestNo(String requestNo);

    Optional<WaterRequest> findByRequestNo(String requestNo);

    List<WaterRequest> findByWaterUserIdOrderByIdDesc(Long waterUserId);

    List<WaterRequest> findByCanalSegmentIdOrderByIdDesc(Long canalSegmentId);

    @Query("select r from WaterRequest r order by "
            + "case r.status when 'PENDING' then 0 when 'APPROVED' then 1 else 2 end asc, r.id desc")
    List<WaterRequest> findAllOrdered();

    @Query("select new com.reservoir.irri.dto.RequestStatusStatDTO(r.status, count(r)) "
            + "from WaterRequest r group by r.status")
    List<RequestStatusStatDTO> statByStatus();

    @Query("select count(r) from WaterRequest r where r.status = :status")
    long countByStatus(@Param("status") String status);

    /** 同一渠段同一用水日已批 / 已供水的水量合计 */
    @Query("select sum(r.requestVolume) from WaterRequest r where r.canalSegmentId = :segmentId "
            + "and r.allotDate = :day and r.status in ('APPROVED', 'SUPPLIED')")
    java.math.BigDecimal sumApprovedVolume(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);

    /** 某渠段某日可改期的用水申请（待审 / 已批；已供水、已驳回为终态不动） */
    @Query("select r from WaterRequest r where r.canalSegmentId = :segmentId "
            + "and r.allotDate = :day and r.status in ('PENDING','APPROVED')")
    List<WaterRequest> findMovableOn(@Param("segmentId") Long segmentId, @Param("day") LocalDate day);

    /** 某渠段日期区间内可改期的用水申请（停水避让同步供水日期用） */
    @Query("select r from WaterRequest r where r.canalSegmentId = :segmentId "
            + "and r.allotDate >= :from and r.allotDate <= :to and r.status in ('PENDING','APPROVED')")
    List<WaterRequest> findMovableBetween(@Param("segmentId") Long segmentId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);
}
