package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 水位量测入参：起止时刻与时段最低/最高水位分别对应
 * {@link com.reservoir.irri.domain.vo.MeasureWindow} 与
 * {@link com.reservoir.irri.domain.vo.LevelBand} 两个值对象；
 * 持续分钟数、变幅、安全等级都是派生值，不入参。
 */
@Data
public class WaterLevelRequest {

    private Long canalSegmentId;
    private String stationCode;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BigDecimal lowLevel;
    private BigDecimal highLevel;
    private BigDecimal measuredLevel;
    private String observer;
    private String note;
}
