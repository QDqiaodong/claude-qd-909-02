package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 闸门开度操作记录：调节幅度超过 30 个百分点时状态置 PENDING（待审批），
 * 审批通过后闸门当前开度才真正落值。
 */
@Entity
@Table(name = "gate_operation")
@Getter
@Setter
public class GateOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属闸门 */
    @Column(name = "gate_id", nullable = false)
    private Long gateId;

    /** 操作单号：全灌区唯一，如 OP-2026-0007 */
    @Column(name = "operation_no", nullable = false, unique = true, length = 32)
    private String operationNo;

    /** 调节前开度（%） */
    @Column(name = "before_opening", precision = 5, scale = 2)
    private BigDecimal beforeOpening;

    /** 调节后开度（%） */
    @Column(name = "after_opening", precision = 5, scale = 2)
    private BigDecimal afterOpening;

    /** 调节幅度（个百分点）= |after - before| */
    @Column(name = "adjust_range", precision = 5, scale = 2)
    private BigDecimal adjustRange;

    /** 操作时间：不得早于该闸门上一次操作时间 */
    @Column(name = "operate_time")
    private LocalDateTime operateTime;

    /** 操作人 */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 操作状态：PENDING 待审批 / APPROVED 已批准 / REJECTED 已驳回 / EXECUTED 已执行 */
    @Column(name = "status", length = 16)
    private String status;

    /** 调节事由 */
    @Column(name = "reason", length = 128)
    private String reason;

    /** 审批人 */
    @Column(name = "approver", length = 32)
    private String approver;

    /** 审批时间 */
    @Column(name = "approve_time")
    private LocalDateTime approveTime;

    @Column(name = "note", length = 128)
    private String note;

    /** 由险情处置单提出的调整：保留关联，便于回看闸门动作如何影响险情 */
    @Column(name = "emergency_order_id")
    private Long emergencyOrderId;
}
