package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 闸门：挂在渠段上，开度 0~100%。safeMinOpening ~ safeMaxOpening 是安全开度区，
 * 单次调节幅度超过 30 个百分点的操作必须走审批。
 */
@Entity
@Table(name = "gate")
@Getter
@Setter
public class Gate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 闸门编号：全灌区唯一，如 GT-TR-01 */
    @Column(name = "gate_code", nullable = false, unique = true, length = 32)
    private String gateCode;

    /** 闸门名称，如 东干渠首进水闸 */
    @Column(name = "gate_name", length = 64)
    private String gateName;

    /** 闸门型式：INTAKE 进水闸 / CONTROL 节制闸 / DIVERSION 分水闸 / DRAIN 退水闸 */
    @Column(name = "gate_type", nullable = false, length = 24)
    private String gateType;

    /** 当前开度（%），0~100 */
    @Column(name = "current_opening", precision = 5, scale = 2)
    private BigDecimal currentOpening;

    /** 安全开度下限（%）：低于此值易淤积 */
    @Column(name = "safe_min_opening", precision = 5, scale = 2)
    private BigDecimal safeMinOpening;

    /** 安全开度上限（%）：高于此值易冲刷 */
    @Column(name = "safe_max_opening", precision = 5, scale = 2)
    private BigDecimal safeMaxOpening;

    /** 全开过流能力（m³/s） */
    @Column(name = "design_flow", precision = 10, scale = 2)
    private BigDecimal designFlow;

    /** 闸门操作手 / 责任人 */
    @Column(name = "keeper", length = 32)
    private String keeper;

    /** 上一次操作时间：新操作记录的时间不得早于它 */
    @Column(name = "last_operate_time")
    private LocalDateTime lastOperateTime;

    /** 闸门状态：ACTIVE 运行 / MAINTAIN 检修 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }

    /** 开度是否落在安全区内 */
    @Transient
    public boolean isInSafeBand() {
        if (currentOpening == null || safeMinOpening == null || safeMaxOpening == null) {
            return false;
        }
        return currentOpening.compareTo(safeMinOpening) >= 0
                && currentOpening.compareTo(safeMaxOpening) <= 0;
    }
}
