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
 * 处置单某个版本的水位/渠段快照。versionNo=0 是发起处置单时的快照，
 * 接管和主动刷新会增加新版本；提交意见前用最新版本和数据库现状逐项比对。
 */
@Entity
@Table(name = "emergency_level_context")
@Getter
@Setter
public class EmergencyLevelContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_order_id", nullable = false)
    private Long emergencyOrderId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "water_level_id", nullable = false)
    private Long waterLevelId;

    @Column(name = "measured_level", precision = 8, scale = 2)
    private BigDecimal measuredLevel;

    @Column(name = "abnormal")
    private Integer abnormal;

    @Column(name = "abnormal_reason", length = 128)
    private String abnormalReason;

    @Column(name = "segment_status", length = 16)
    private String segmentStatus;

    @Column(name = "segment_name", length = 64)
    private String segmentName;

    @Column(name = "design_low_level", precision = 8, scale = 2)
    private BigDecimal designLowLevel;

    @Column(name = "design_high_level", precision = 8, scale = 2)
    private BigDecimal designHighLevel;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
