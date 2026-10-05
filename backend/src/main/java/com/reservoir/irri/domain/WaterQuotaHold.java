package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用水申请额度预占流水。
 *
 * <p>APPROVED 时生成 HELD 流水；退回待审 / 已批驳回落 RELEASED；已供水转 CONSUMED。
 * 流水追加保存、不做物理删除，终态保留审批与释放审计信息。
 */
@Entity
@Table(name = "water_quota_hold")
@Getter
@Setter
public class WaterQuotaHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "water_request_id", nullable = false)
    private Long waterRequestId;

    @Column(name = "request_no", nullable = false, length = 32)
    private String requestNo;

    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    @Column(name = "allot_date", nullable = false)
    private LocalDate allotDate;

    @Column(name = "hold_volume", nullable = false, precision = 12, scale = 2)
    private BigDecimal holdVolume;

    /** HELD 已预占 / CONSUMED 已随供水入账 / RELEASED 已释放 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "held_by", length = 32)
    private String heldBy;

    @Column(name = "held_at")
    private LocalDateTime heldAt;

    @Column(name = "released_by", length = 32)
    private String releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** 释放原因：退回待审 / 已批驳回 / 改期释放等 */
    @Column(name = "release_reason", length = 256)
    private String releaseReason;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
