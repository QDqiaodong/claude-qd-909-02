package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠段检修停水窗口：运维登记的渠段停水起止时间、原因与范围。
 * 生效（ACTIVE）期间与灌溉计划 / 配水条的重叠由 OutageAvoidItem 跟踪；
 * 撤销（CANCELED）后只恢复尚未人工确认的待避让项，已处理结果保留为历史。
 */
@Entity
@Table(name = "outage_window")
@Getter
@Setter
public class OutageWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 窗口编号：全灌区唯一，如 MW-20261005143000 */
    @Column(name = "window_no", nullable = false, unique = true, length = 32)
    private String windowNo;

    /** 停水渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 停水开始时间（可跨日） */
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /** 停水结束时间：必须晚于开始时间 */
    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    /** 停水原因（检修内容） */
    @Column(name = "reason", length = 128)
    private String reason;

    /** 停水范围：FULL 全段停水 / PARTIAL 局部停水 */
    @Column(name = "scope_type", length = 16)
    private String scopeType;

    /** 范围说明：如 桩号 0+800~1+600 全断面 */
    @Column(name = "scope_note", length = 128)
    private String scopeNote;

    /** 窗口状态：ACTIVE 生效中 / CANCELED 已撤销 */
    @Column(name = "status", length = 16)
    private String status;

    /** 登记人（运维） */
    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 撤销时间 */
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    /** 撤销说明 */
    @Column(name = "cancel_note", length = 128)
    private String cancelNote;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
