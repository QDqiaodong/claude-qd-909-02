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
import lombok.Getter;
import lombok.Setter;

/**
 * 配水记录：灌溉计划在某渠段某天的配水安排。
 * 同一渠段同一天各条配水的流量合计不得超过该渠段设计流量。
 */
@Entity
@Table(name = "allotment")
@Getter
@Setter
public class Allotment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属灌溉计划 */
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 配水渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 配水日期：同一渠段同一天的流量合计受设计流量约束 */
    @Column(name = "allot_date")
    private LocalDate allotDate;

    /** 配水流量（m³/s） */
    @Column(name = "flow", precision = 10, scale = 2)
    private BigDecimal flow;

    /** 配水时长（h） */
    @Column(name = "hours")
    private Integer hours;

    /** 配水量（万m³）= flow × hours × 0.36 */
    @Column(name = "volume_wan", precision = 12, scale = 2)
    private BigDecimal volumeWan;

    /** 配水状态：PLANNED 已排期 / SUPPLYING 配水中 / DONE 已完成 / CANCELED 已取消 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isCanceled() {
        return "CANCELED".equals(status);
    }
}
