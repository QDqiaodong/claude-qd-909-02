package com.reservoir.irri.domain.vo;

import com.reservoir.irri.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.EmbeddableInstantiator;

/**
 * 水位区间值对象（@Embeddable）：时段最低水位 + 时段最高水位 + 自动派生的变幅与安全等级。
 *
 * <p>读库时由 {@link LevelBandInstantiator} 按 band_low_level / band_high_level 两列构造，
 * 派生出变幅 span 与变幅安全等级 safetyGrade；两个派生属性都是
 * insertable=false / updatable=false，不落库。
 */
@Embeddable
@EmbeddableInstantiator(LevelBandInstantiator.class)
@Getter
@Setter
public class LevelBand implements Serializable {

    /** 时段最低水位（m） */
    @Column(name = "band_low_level", precision = 8, scale = 2)
    private BigDecimal lowLevel;

    /** 时段最高水位（m） */
    @Column(name = "band_high_level", precision = 8, scale = 2)
    private BigDecimal highLevel;

    /** 【派生】变幅 = 最高 - 最低（m），不落库 */
    @Column(name = "band_span", precision = 8, scale = 2, insertable = false, updatable = false)
    private BigDecimal span;

    /** 【派生】变幅安全等级 STEADY 平稳 / NORMAL 常规 / SURGE 骤变，不落库 */
    @Column(name = "band_safety_grade", length = 16, insertable = false, updatable = false)
    private String safetyGrade;

    public LevelBand() {
    }

    public LevelBand(BigDecimal lowLevel, BigDecimal highLevel) {
        this.lowLevel = lowLevel;
        this.highLevel = highLevel;
        this.span = deriveSpan(lowLevel, highLevel);
        this.safetyGrade = deriveSafetyGrade(this.span);
    }

    /** 唯一推荐入口：构造即派生 */
    public static LevelBand of(BigDecimal lowLevel, BigDecimal highLevel) {
        return new LevelBand(lowLevel, highLevel);
    }

    public static BigDecimal deriveSpan(BigDecimal lowLevel, BigDecimal highLevel) {
        if (lowLevel == null || highLevel == null) {
            return null;
        }
        return highLevel.subtract(lowLevel).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 变幅安全等级：&lt; 0.20m 平稳，&lt; 0.60m 常规，否则骤变；
     * 变幅为负（最低高于最高）时直接判骤变，由 service 二次校验拦下。
     */
    public static String deriveSafetyGrade(BigDecimal span) {
        if (span == null) {
            return null;
        }
        if (span.signum() < 0) {
            return "SURGE";
        }
        if (span.compareTo(Dict.BAND_SURGE_STEADY) < 0) {
            return "STEADY";
        }
        if (span.compareTo(Dict.BAND_SURGE_NORMAL) < 0) {
            return "NORMAL";
        }
        return "SURGE";
    }

    /** 重新派生（改了起止水位后调用） */
    public void refresh() {
        this.span = deriveSpan(this.lowLevel, this.highLevel);
        this.safetyGrade = deriveSafetyGrade(this.span);
    }

    @Override
    public String toString() {
        return lowLevel + " ~ " + highLevel + " m（变幅 " + span + "，" + Dict.safetyGradeName(safetyGrade) + "）";
    }
}
