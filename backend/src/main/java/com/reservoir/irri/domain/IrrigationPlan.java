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
 * 灌溉计划：一轮配水的周期与总量。审批通过（APPROVED）之后不允许再改配水流量与周期。
 */
@Entity
@Table(name = "irrigation_plan")
@Getter
@Setter
public class IrrigationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 计划编号：全灌区唯一，如 IP-2026-S-03 */
    @Column(name = "plan_code", nullable = false, unique = true, length = 32)
    private String planCode;

    /** 计划名称，如 东干渠夏灌第二轮 */
    @Column(name = "plan_name", length = 64)
    private String planName;

    /** 主配水渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 周期开始日 */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 周期结束日：不得早于开始日 */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** 计划天数 */
    @Column(name = "plan_days")
    private Integer planDays;

    /** 计划配水流量（m³/s） */
    @Column(name = "plan_flow", precision = 10, scale = 2)
    private BigDecimal planFlow;

    /** 计划配水总量（万m³） */
    @Column(name = "plan_volume", precision = 12, scale = 2)
    private BigDecimal planVolume;

    /** 作物类型：RICE / WHEAT / MAIZE / COTTON / VEGETABLE */
    @Column(name = "crop_type", length = 24)
    private String cropType;

    /** 灌溉面积（亩） */
    @Column(name = "irrigated_area_mu", precision = 10, scale = 2)
    private BigDecimal irrigatedAreaMu;

    /** 计划状态：DRAFT 编制 / PENDING 待审批 / APPROVED 已批准 / RUNNING 配水中 / FINISHED 已结束 */
    @Column(name = "status", length = 16)
    private String status;

    /** 审批人 */
    @Column(name = "approver", length = 32)
    private String approver;

    /** 审批时间 */
    @Column(name = "approve_time")
    private LocalDateTime approveTime;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isLocked() {
        return com.reservoir.irri.common.Dict.isPlanLocked(status);
    }
}
