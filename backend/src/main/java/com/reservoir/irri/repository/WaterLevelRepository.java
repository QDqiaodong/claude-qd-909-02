package com.reservoir.irri.repository;

import com.reservoir.irri.domain.WaterLevel;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaterLevelRepository extends JpaRepository<WaterLevel, Long> {

    /**
     * 按测站取时序记录。值对象里的列用 w.window.startAt 直接写 JPQL，
     * 避免在方法名里做属性下钻（Spring Data 对 @Embeddable 下钻的解析不够稳定）。
     */
    @Query("select w from WaterLevel w where w.stationCode = :stationCode "
            + "order by w.window.startAt asc, w.id asc")
    List<WaterLevel> findByStationAsc(@Param("stationCode") String stationCode);

    @Query("select w from WaterLevel w where w.canalSegmentId = :segmentId "
            + "order by w.window.startAt desc, w.id desc")
    List<WaterLevel> findBySegmentDesc(@Param("segmentId") Long segmentId);

    @Query("select w from WaterLevel w order by w.window.startAt desc, w.id desc")
    List<WaterLevel> findAllDesc();

    /** 同测站与指定时段有交集的记录数（排除自己） */
    @Query("select count(w) from WaterLevel w where w.stationCode = :stationCode and w.id <> :selfId "
            + "and w.window.startAt < :end and w.window.endAt > :start")
    long countOverlap(@Param("stationCode") String stationCode,
                      @Param("selfId") Long selfId,
                      @Param("start") LocalDateTime start,
                      @Param("end") LocalDateTime end);

    @Query("select count(w) from WaterLevel w where w.abnormal = 1")
    long countAbnormal();

    /** 近 N 天（按登记时间）的量测条数 */
    @Query("select count(w) from WaterLevel w where w.createdAt >= :since")
    long countSince(@Param("since") LocalDateTime since);
}
