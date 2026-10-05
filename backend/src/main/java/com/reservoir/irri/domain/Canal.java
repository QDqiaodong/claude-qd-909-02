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
 * 渠道：灌区渠系的骨架，分干渠 / 支渠 / 斗渠三级，纵向水系拓扑图的三条主干。
 * 停用（DISABLED）的渠道其下渠段一律不允许配水。
 */
@Entity
@Table(name = "canal")
@Getter
@Setter
public class Canal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 渠道编号：全灌区唯一，如 GQ-TR-01 */
    @Column(name = "canal_code", nullable = false, unique = true, length = 32)
    private String canalCode;

    /** 渠道名称，如 东干渠 */
    @Column(name = "canal_name", length = 64)
    private String canalName;

    /** 渠道等级：TRUNK 干渠 / BRANCH 支渠 / LATERAL 斗渠 */
    @Column(name = "canal_grade", nullable = false, length = 24)
    private String canalGrade;

    /** 取水水源，如 青龙水库主坝放水洞 */
    @Column(name = "source_name", length = 64)
    private String sourceName;

    /** 渠道全长（km），必须 > 0 */
    @Column(name = "length_km", precision = 10, scale = 3)
    private BigDecimal lengthKm;

    /** 设计流量（m³/s），必须 > 0 */
    @Column(name = "design_flow", precision = 10, scale = 2)
    private BigDecimal designFlow;

    /** 渠段数量（由渠段增删维护） */
    @Column(name = "segment_count")
    private Integer segmentCount;

    /** 渠道管理员 */
    @Column(name = "manager", length = 32)
    private String manager;

    /** 建成年份 */
    @Column(name = "built_year")
    private Integer builtYear;

    /** 渠道状态：ACTIVE 通水 / MAINTAIN 岁修 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }
}
