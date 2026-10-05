package com.reservoir.irri.domain.vo;

import com.reservoir.irri.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.EmbeddableInstantiator;

/**
 * 量测时段值对象（@Embeddable）：起始时刻 + 结束时刻 + 自动派生的持续分钟数。
 *
 * <p>列值 → 对象的构造过程由 {@link MeasureWindowInstantiator} 完成：Hibernate 读库时把
 * window_start_at / window_end_at 两列的值交给 instantiator，由它 new 出本对象并算出派生值。
 * 派生属性 durationMinutes 标注 insertable=false / updatable=false，Hibernate 永不写库，
 * 表里即便有值也只是初始化脚本留下的快照，读出的一定是即时算出来的。
 */
@Embeddable
@EmbeddableInstantiator(MeasureWindowInstantiator.class)
@Getter
@Setter
public class MeasureWindow implements Serializable {

    /** 时段起始时刻 */
    @Column(name = "window_start_at")
    private LocalDateTime startAt;

    /** 时段结束时刻 */
    @Column(name = "window_end_at")
    private LocalDateTime endAt;

    /** 【派生】持续分钟数，不落库 */
    @Column(name = "window_duration_minutes", insertable = false, updatable = false)
    private Integer durationMinutes;

    public MeasureWindow() {
    }

    public MeasureWindow(LocalDateTime startAt, LocalDateTime endAt) {
        this.startAt = startAt;
        this.endAt = endAt;
        this.durationMinutes = deriveDurationMinutes(startAt, endAt);
    }

    /** 唯一推荐入口：构造即派生，保证内存里的对象和 instantiator 读出来的完全一致 */
    public static MeasureWindow of(LocalDateTime startAt, LocalDateTime endAt) {
        return new MeasureWindow(startAt, endAt);
    }

    /**
     * 派生逻辑：结束时刻早于开始时刻时返回负数，由 service 二次校验拦下；
     * 起止任一为空时返回 null。
     */
    public static Integer deriveDurationMinutes(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null) {
            return null;
        }
        return (int) Duration.between(startAt, endAt).toMinutes();
    }

    /** 重新派生（改了起止时刻后调用） */
    public void refresh() {
        this.durationMinutes = deriveDurationMinutes(this.startAt, this.endAt);
    }

    /** 【派生】时段是否跨越了单日上限 */
    public boolean isOverLong() {
        return durationMinutes != null && durationMinutes > Dict.MAX_WINDOW_MINUTES;
    }

    @Override
    public String toString() {
        return startAt + " ~ " + endAt + " (" + durationMinutes + "分钟)";
    }
}
