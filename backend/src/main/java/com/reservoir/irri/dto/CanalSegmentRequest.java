package com.reservoir.irri.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 渠段新增 / 修改入参（局部更新：为 null 的字段不改） */
@Data
public class CanalSegmentRequest {

    private Long canalId;
    private String segmentCode;
    private String segmentName;
    private Integer sortNo;
    private BigDecimal startMileage;
    private BigDecimal endMileage;
    private BigDecimal lengthKm;
    private BigDecimal designFlow;
    private BigDecimal designLowLevel;
    private BigDecimal designHighLevel;
    private String liningType;
    private Long upstreamId;
    private String status;
    private String note;
}
