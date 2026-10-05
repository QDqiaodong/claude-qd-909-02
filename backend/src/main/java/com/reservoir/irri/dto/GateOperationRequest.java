package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 闸门开度调节入参 */
@Data
public class GateOperationRequest {

    private Long gateId;
    private String operationNo;
    /** 目标开度（%） */
    private BigDecimal afterOpening;
    private LocalDateTime operateTime;
    private String operator;
    private String reason;
    private String note;
    private Long emergencyOrderId;
}
