package com.reservoir.irri.domain.vo;

import java.time.LocalDateTime;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.EmbeddableInstantiator;
import org.hibernate.metamodel.spi.ValueAccess;

/**
 * Hibernate 6.5 的 {@link EmbeddableInstantiator} 实现：读库时由它按列值构造 {@link MeasureWindow}，
 * 并顺带把派生属性（持续分钟数）算出来。
 *
 * <p>接口真实签名（hibernate-core 6.5.2）：
 * {@code Object instantiate(ValueAccess valueAccess, SessionFactoryImplementor sessionFactory)}
 * —— 不是旧版的 {@code instantiate(ValueAccess, SessionImplementor)}，别写错参数类型。
 *
 * <p>另一个坑：{@code valueAccess.getValues()} 给出的列值顺序是【属性名字母序】，不是声明顺序。
 * 本值对象的三个属性排完序是 durationMinutes → endAt → startAt，
 * 所以持续分钟数在第 1 位、结束时刻在第 2 位、起始时刻在第 3 位；按声明顺序取会把 Integer 强转炸掉。
 */
public class MeasureWindowInstantiator implements EmbeddableInstantiator {

    @Override
    public Object instantiate(ValueAccess valueAccess, SessionFactoryImplementor sessionFactory) {
        Object[] values = valueAccess.getValues();
        LocalDateTime endAt = values.length > 1 && values[1] instanceof LocalDateTime
                ? (LocalDateTime) values[1] : null;
        LocalDateTime startAt = values.length > 2 && values[2] instanceof LocalDateTime
                ? (LocalDateTime) values[2] : null;
        // 派生值不取自第 1 列，直接按起止时刻即时算，保证与库里的陈旧快照无关
        return MeasureWindow.of(startAt, endAt);
    }

    @Override
    public boolean isInstance(Object object, SessionFactoryImplementor sessionFactory) {
        return object instanceof MeasureWindow;
    }

    @Override
    public boolean isSameClass(Object object, SessionFactoryImplementor sessionFactory) {
        return object != null && object.getClass() == MeasureWindow.class;
    }
}
