package com.reservoir.irri.domain.vo;

import java.math.BigDecimal;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.EmbeddableInstantiator;
import org.hibernate.metamodel.spi.ValueAccess;

/**
 * Hibernate 6.5 的 {@link EmbeddableInstantiator} 实现：读库时按 band_low_level /
 * band_high_level 两列构造 {@link LevelBand}，并派生出变幅 span 与安全等级 safetyGrade。
 *
 * <p>{@code valueAccess.getValues()} 的列值是按【属性名字母序】给的，本值对象排序后为
 * highLevel → lowLevel → safetyGrade → span，所以第 1 位是最高水位、第 2 位是最低水位；
 * 变幅与安全等级一律即时派生，不读第 3、4 列的快照。
 */
public class LevelBandInstantiator implements EmbeddableInstantiator {

    @Override
    public Object instantiate(ValueAccess valueAccess, SessionFactoryImplementor sessionFactory) {
        Object[] values = valueAccess.getValues();
        BigDecimal highLevel = values.length > 0 && values[0] instanceof BigDecimal
                ? (BigDecimal) values[0] : null;
        BigDecimal lowLevel = values.length > 1 && values[1] instanceof BigDecimal
                ? (BigDecimal) values[1] : null;
        // 变幅与安全等级一律即时派生，不读第 3、4 列的快照
        return LevelBand.of(lowLevel, highLevel);
    }

    @Override
    public boolean isInstance(Object object, SessionFactoryImplementor sessionFactory) {
        return object instanceof LevelBand;
    }

    @Override
    public boolean isSameClass(Object object, SessionFactoryImplementor sessionFactory) {
        return object != null && object.getClass() == LevelBand.class;
    }
}
