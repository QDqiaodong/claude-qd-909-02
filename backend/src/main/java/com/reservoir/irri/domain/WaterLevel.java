package com.reservoir.irri.domain;

import com.reservoir.irri.domain.vo.LevelBand;
import com.reservoir.irri.domain.vo.MeasureWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 水位量测：一条记录里嵌了两个 @Embeddable 值对象——
 * {@link MeasureWindow}（量测时段，派生持续分钟数）与
 * {@link LevelBand}（时段水位区间，派生变幅与安全等级）。
 * 两个值对象都由自定义 EmbeddableInstantiator 在读库时按列值构造并派生。
 */
@Entity
@Table(name = "water_level")
@Getter
@Setter
public class WaterLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 测站编号，如 SW-TR-03 */
    @Column(name = "station_code", length = 32)
    private String stationCode;

    /** 量测时段（值对象：起止时刻 + 派生持续分钟数） */
    @Embedded
    private MeasureWindow window;

    /** 时段水位区间（值对象：最低/最高水位 + 派生变幅 + 派生安全等级） */
    @Embedded
    private LevelBand band;

    /** 时段平均水位（m）：越界判定用它 */
    @Column(name = "measured_level", precision = 8, scale = 2)
    private BigDecimal measuredLevel;

    /** 观测员 */
    @Column(name = "observer", length = 32)
    private String observer;

    /** 是否异常：1 越界 / 0 正常 */
    @Column(name = "abnormal")
    private Integer abnormal;

    /** 异常原因，如 低于设计最低水位 0.35m */
    @Column(name = "abnormal_reason", length = 128)
    private String abnormalReason;

    /** 登记时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "note", length = 128)
    private String note;

    // ---------------- 值对象派生值的快捷读取 ----------------

    /** 【派生】量测持续分钟数，来自 MeasureWindow */
    @Transient
    public Integer getDurationMinutes() {
        return window == null ? null : window.getDurationMinutes();
    }

    /** 【派生】时段起始时刻 */
    @Transient
    public LocalDateTime getStartAt() {
        return window == null ? null : window.getStartAt();
    }

    /** 【派生】时段结束时刻 */
    @Transient
    public LocalDateTime getEndAt() {
        return window == null ? null : window.getEndAt();
    }

    /** 【派生】水位变幅，来自 LevelBand */
    @Transient
    public BigDecimal getBandSpan() {
        return band == null ? null : band.getSpan();
    }

    /** 【派生】变幅安全等级，来自 LevelBand */
    @Transient
    public String getSafetyGrade() {
        return band == null ? null : band.getSafetyGrade();
    }

    @Transient
    public boolean isAbnormal() {
        return abnormal != null && abnormal == 1;
    }
}
