package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 异常水位险情处置单。
 *
 * <p>状态链：PENDING 待接管 → HANDLING 处置中 → REVIEW 待复核 → RESOLVED 已解除；
 * 任一未结束状态都可转 MANUAL 人工处置。version 为乐观锁，页面提交必须带回，
 * 另有人工接管行锁保证同一时刻只有一名值班员接管。
 */
@Entity
@Table(name = "emergency_order")
@Getter
@Setter
public class EmergencyOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "water_level_id", nullable = false)
    private Long waterLevelId;

    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    @Column(name = "station_code", length = 32)
    private String stationCode;

    @Column(name = "source_window_start_at")
    private LocalDateTime sourceWindowStartAt;

    @Column(name = "source_window_end_at")
    private LocalDateTime sourceWindowEndAt;

    @Column(name = "source_measured_level", precision = 8, scale = 2)
    private BigDecimal sourceMeasuredLevel;

    @Column(name = "source_low_level", precision = 8, scale = 2)
    private BigDecimal sourceLowLevel;

    @Column(name = "source_high_level", precision = 8, scale = 2)
    private BigDecimal sourceHighLevel;

    @Column(name = "source_abnormal_reason", length = 128)
    private String sourceAbnormalReason;

    @Column(name = "source_segment_status", length = 16)
    private String sourceSegmentStatus;

    @Column(name = "source_segment_name", length = 64)
    private String sourceSegmentName;

    /** PENDING / HANDLING / REVIEW / RESOLVED / MANUAL */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "handler", length = 32)
    private String handler;

    @Column(name = "taken_over_at")
    private LocalDateTime takenOverAt;

    @Column(name = "next_review_at")
    private LocalDateTime nextReviewAt;

    @Column(name = "latest_gate_operation_id")
    private Long latestGateOperationId;

    @Column(name = "latest_gate_operation_no", length = 32)
    private String latestGateOperationNo;

    /** OBSERVE 继续观测 / GATE 闸门调整 */
    @Column(name = "decision_mode", length = 16)
    private String decisionMode;

    @Column(name = "decision_comment", length = 256)
    private String decisionComment;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolver", length = 32)
    private String resolver;

    @Column(name = "resolution_note", length = 256)
    private String resolutionNote;

    @Column(name = "manual_reason", length = 256)
    private String manualReason;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
