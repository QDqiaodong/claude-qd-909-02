package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 停水窗口避让项：窗口生效 / 变更时扫描出的一个重叠对象（一条灌溉计划或一条配水条）。
 * 调度员处理三选一：整体顺延（POSTPONED）/ 拆分改期（SPLIT）/ 例外保留（KEPT，须填例外依据）；
 * 窗口撤销或变更后不再重叠时，仅未人工确认的 PENDING 项被恢复（RESTORED），已处理结果不动。
 */
@Entity
@Table(name = "outage_avoid_item")
@Getter
@Setter
public class OutageAvoidItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属停水窗口 */
    @Column(name = "window_id", nullable = false)
    private Long windowId;

    /** 避让对象类型：PLAN 灌溉计划 / ALLOT 配水条 */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /** 命中的灌溉计划（配水条项也记录所属计划，便于随计划一并了结） */
    @Column(name = "plan_id")
    private Long planId;

    /** 命中的配水条（计划项为 null） */
    @Column(name = "allotment_id")
    private Long allotmentId;

    /** 命中时的渠段（快照） */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 命中时周期开始日 / 配水日（快照） */
    @Column(name = "orig_start_date")
    private LocalDate origStartDate;

    /** 命中时周期结束日（计划项快照） */
    @Column(name = "orig_end_date")
    private LocalDate origEndDate;

    /** 状态：PENDING 待避让 / POSTPONED 已顺延 / SPLIT 已拆分 / KEPT 例外保留 / RESTORED 已恢复 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 最终去向：处理说明；例外保留时为例外依据 */
    @Column(name = "resolution_note", length = 256)
    private String resolutionNote;

    /** 处理后周期开始日 / 新配水日 */
    @Column(name = "new_start_date")
    private LocalDate newStartDate;

    /** 处理后周期结束日（计划项） */
    @Column(name = "new_end_date")
    private LocalDate newEndDate;

    /** 计划拆分产生的后段新计划 */
    @Column(name = "split_plan_id")
    private Long splitPlanId;

    /** 处理人（调度员） */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 处理时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
