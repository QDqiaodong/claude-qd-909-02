package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 灌溉计划新增 / 修改入参 */
@Data
public class PlanRequest {

    private String planCode;
    private String planName;
    private Long canalSegmentId;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal planFlow;
    private BigDecimal planVolume;
    private String cropType;
    private BigDecimal irrigatedAreaMu;
    private String status;
    private String approver;
    private String note;
}
