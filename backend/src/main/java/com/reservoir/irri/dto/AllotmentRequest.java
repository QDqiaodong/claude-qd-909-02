package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 配水记录新增 / 修改入参 */
@Data
public class AllotmentRequest {

    private Long planId;
    private Long canalSegmentId;
    private LocalDate allotDate;
    private BigDecimal flow;
    private Integer hours;
    private String status;
    private String note;
}
