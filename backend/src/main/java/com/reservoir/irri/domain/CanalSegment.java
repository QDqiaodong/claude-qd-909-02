package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠段：渠道上按里程切分的管理单元，配水、闸门、量测、用水申请都挂到渠段上。
 * 停用（DISABLED）的渠段不允许配水，也不允许再登记新的闸门操作与用水申请。
 */
@Entity
@Table(name = "canal_segment")
@Getter
@Setter
public class CanalSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属渠道 */
    @Column(name = "canal_id", nullable = false)
    private Long canalId;

    /** 渠段编号：全灌区唯一，如 GQ-TR-01-S2 */
    @Column(name = "segment_code", nullable = false, unique = true, length = 32)
    private String segmentCode;

    /** 渠段名称，如 东干渠桩号 3+200~7+800 */
    @Column(name = "segment_name", length = 64)
    private String segmentName;

    /** 在本渠道内的顺序号，从 1 起；上下游衔接校验按它排序 */
    @Column(name = "sort_no")
    private Integer sortNo;

    /** 起点里程（km） */
    @Column(name = "start_mileage", precision = 10, scale = 3)
    private BigDecimal startMileage;

    /** 终点里程（km），必须大于起点里程 */
    @Column(name = "end_mileage", precision = 10, scale = 3)
    private BigDecimal endMileage;

    /** 渠段长度（km），必须 > 0 */
    @Column(name = "length_km", precision = 10, scale = 3)
    private BigDecimal lengthKm;

    /** 设计流量（m³/s），必须 > 0；同一时段配水流量合计不得突破它 */
    @Column(name = "design_flow", precision = 10, scale = 2)
    private BigDecimal designFlow;

    /** 设计最低水位（m）：水位量测越界判定的下界 */
    @Column(name = "design_low_level", precision = 8, scale = 2)
    private BigDecimal designLowLevel;

    /** 设计最高水位（m）：水位量测越界判定的上界 */
    @Column(name = "design_high_level", precision = 8, scale = 2)
    private BigDecimal designHighLevel;

    /** 衬砌型式：CONCRETE / MASONRY / EARTH / FILM */
    @Column(name = "lining_type", length = 24)
    private String liningType;

    /** 紧邻的上游渠段；为空表示本渠段是该渠道的首段 */
    @Column(name = "upstream_id")
    private Long upstreamId;

    /** 渠段状态：ACTIVE 通水 / MAINTAIN 岁修 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }
}
