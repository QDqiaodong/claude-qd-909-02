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
 *
 * <p>额度预占：批准即按「渠段 + 用水日」形成可追溯的额度预占（quota_reserved = 1，记 reserved_at），
 * 退回待审 / 驳回已批时释放（quota_reserved = 0，记 released_at）；已供水终态保留预占与审计信息。
 * 历史数据没有预占标记（quota_reserved = 0）时，按已批 / 已供水状态计入额度。
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

    /** 额度预占标记：已批 / 已供水占 1；待审 / 已驳回为 0。历史数据为 0 时按状态计入额度 */
    @Column(name = "quota_reserved", nullable = false)
    private Boolean quotaReserved = Boolean.FALSE;

    /** 额度预占时间（批准时刻）；历史数据可能为空 */
    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    /** 最近一次释放预占时间（退回待审 / 驳回已批）；已供水终态保留不清 */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isTerminal() {
        return com.reservoir.irri.common.Dict.isTerminalRequestStatus(status);
    }

    /** 当前是否占用该渠段该日额度：预占标记为 1，或无标记的历史已批 / 已供水申请 */
    @Transient
    public boolean isQuotaOccupying() {
        return Boolean.TRUE.equals(quotaReserved) || "APPROVED".equals(status) || "SUPPLIED".equals(status);
    }
}
