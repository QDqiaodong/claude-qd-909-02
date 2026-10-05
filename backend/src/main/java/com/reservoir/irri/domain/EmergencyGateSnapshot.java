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
 * 处置单某版本下同渠段关联闸门的状态快照。
 * 除闸门开度、运行状态、安全开度区间外，还固化当时最后一条操作记录，
 * 防止创建处置单时闸门可用、提交调整前却已检修/停用或出现新操作。
 */
@Entity
@Table(name = "emergency_gate_snapshot")
@Getter
@Setter
public class EmergencyGateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_order_id", nullable = false)
    private Long emergencyOrderId;

    @Column(name = "gate_id", nullable = false)
    private Long gateId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "gate_code", length = 32)
    private String gateCode;

    @Column(name = "gate_name", length = 64)
    private String gateName;

    @Column(name = "gate_type", length = 24)
    private String gateType;

    @Column(name = "current_opening", precision = 5, scale = 2)
    private BigDecimal currentOpening;

    @Column(name = "safe_min_opening", precision = 5, scale = 2)
    private BigDecimal safeMinOpening;

    @Column(name = "safe_max_opening", precision = 5, scale = 2)
    private BigDecimal safeMaxOpening;

    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "keeper", length = 32)
    private String keeper;

    @Column(name = "last_operate_time")
    private LocalDateTime lastOperateTime;

    @Column(name = "latest_operation_id")
    private Long latestOperationId;

    @Column(name = "latest_operation_no", length = 32)
    private String latestOperationNo;

    @Column(name = "latest_operation_status", length = 16)
    private String latestOperationStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
