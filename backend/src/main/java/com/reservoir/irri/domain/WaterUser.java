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
 * 用水户：灌区登记在册的农户 / 合作社。灌溉面积必须 > 0，欠费停供（ARREAR）时不允许提交用水申请。
 */
@Entity
@Table(name = "water_user")
@Getter
@Setter
public class WaterUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 农户编号：全灌区唯一，如 WU-2026-018 */
    @Column(name = "user_code", nullable = false, unique = true, length = 32)
    private String userCode;

    /** 农户姓名 / 合作社名称 */
    @Column(name = "user_name", length = 32)
    private String userName;

    /** 所属村组 */
    @Column(name = "village", length = 64)
    private String village;

    /** 联系电话（业务逻辑不校验唯一，仅展示） */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 灌溉面积（亩），必须 > 0 */
    @Column(name = "irrigated_area_mu", precision = 10, scale = 2)
    private BigDecimal irrigatedAreaMu;

    /** 主要作物：RICE / WHEAT / MAIZE / COTTON / VEGETABLE */
    @Column(name = "crop_type", length = 24)
    private String cropType;

    /** 取水渠段 */
    @Column(name = "canal_segment_id")
    private Long canalSegmentId;

    /** 用户状态：ACTIVE 在册 / ARREAR 欠费停供 / DISABLED 注销 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isArrear() {
        return "ARREAR".equals(status);
    }
}
