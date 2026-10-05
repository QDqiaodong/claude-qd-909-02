package com.reservoir.irri.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 「渠段 + 供水日」额度锁桶。
 *
 * <p>记录本身不存剩余量，容量、已排配水和预占均实时汇总；它的作用是为同一渠段同一天的
 * 审批、退回、供水中和配水调整提供固定的数据库行级串行点。
 */
@Entity
@Table(name = "water_quota_bucket")
@Getter
@Setter
public class QuotaBucket {

    @EmbeddedId
    private QuotaBucketId id;
}
