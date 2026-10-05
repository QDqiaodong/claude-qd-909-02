package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 险情处置台入参集合 */
@Data
public class EmergencyRequest {

    /** 发起处置单：异常水位记录 ID */
    private Long waterLevelId;

    private String creator;

    /** 接管 / 提交动作的值班员 */
    private String operator;

    /** 页面当前处置单版本；所有写操作都必须带回 */
    private Integer version;

    /** 已确认使用最新快照。false 时只做新旧核对，不执行写动作 */
    private Boolean fresh;

    /** 继续观测的复查时限 */
    private LocalDateTime nextReviewAt;

    /** OBSERVE / GATE */
    private String decisionMode;

    private String comment;

    /** 闸门调整 */
    private Long gateId;
    private String operationNo;
    private BigDecimal afterOpening;
    private LocalDateTime operateTime;
    private String gateReason;

    /** 转人工 / 解除 */
    private String reason;
    private String resolutionNote;
}
