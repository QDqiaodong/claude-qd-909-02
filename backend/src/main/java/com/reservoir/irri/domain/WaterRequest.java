package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用水申请：农户按渠段按日期申请配水量，走「待审 → 已批 → 已供水 / 已驳回」三列审批流。
 * 驳回必须填理由；已供水 / 已驳回为终态。
 */
@Entity
@Table(name = "water_request")
@Getter
@Setter
public class WaterRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申请单号：全灌区唯一，如 WR-2026-0113 */
    @Column(name = "request_no", nullable = false, unique = true, length = 32)
    private String requestNo;

    /** 申请人 */
    @Column(name = "water_user_id", nullable = false)
    private Long waterUserId;

    /** 取水渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 申请用水日期：剩余可配水量按「渠段 + 日期」核算 */
    @Column(name = "allot_date")
    private LocalDate allotDate;

    /** 申请水量（万m³），必须 > 0 且不得超过该时段该渠段剩余可配水量 */
    @Column(name = "request_volume", precision = 12, scale = 2)
    private BigDecimal requestVolume;

    /** 作物类型 */
    @Column(name = "crop_type", length = 24)
    private String cropType;

    /** 申请亩数 */
    @Column(name = "apply_area_mu", precision = 10, scale = 2)
    private BigDecimal applyAreaMu;

    /** 紧急度：NORMAL 常规 / URGENT 抗旱应急 */
    @Column(name = "urgency", length = 16)
    private String urgency;

    /** 申请状态：PENDING 待审 / APPROVED 已批 / SUPPLIED 已供水 / REJECTED 已驳回 */
    @Column(name = "status", length = 16)
    private String status;

    /** 驳回理由：驳回时必须填 */
    @Column(name = "reject_reason", length = 128)
    private String rejectReason;

    /** 审批人 */
    @Column(name = "approver", length = 32)
    private String approver;

    /** 申请时间 */
    @Column(name = "apply_time")
    private LocalDateTime applyTime;

    /** 审批时间 */
    @Column(name = "approve_time")
    private LocalDateTime approveTime;

    /** 供水时间 */
    @Column(name = "supply_time")
    private LocalDateTime supplyTime;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isTerminal() {
        return com.reservoir.irri.common.Dict.isTerminalRequestStatus(status);
    }
}
