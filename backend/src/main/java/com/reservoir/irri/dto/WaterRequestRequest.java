package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 用水申请新增 / 修改入参 */
@Data
public class WaterRequestRequest {

    private String requestNo;
    private Long waterUserId;
    private Long canalSegmentId;
    private LocalDate allotDate;
    private BigDecimal requestVolume;
    private String cropType;
    private BigDecimal applyAreaMu;
    private String urgency;
    private String note;
}
