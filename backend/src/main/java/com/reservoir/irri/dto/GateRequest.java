package com.reservoir.irri.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 闸门新增 / 修改入参 */
@Data
public class GateRequest {

    private Long canalSegmentId;
    private String gateCode;
    private String gateName;
    private String gateType;
    private BigDecimal currentOpening;
    private BigDecimal safeMinOpening;
    private BigDecimal safeMaxOpening;
    private BigDecimal designFlow;
    private String keeper;
    private String status;
    private String note;
}
