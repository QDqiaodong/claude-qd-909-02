package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 险情处置时间线事件。按 eventTime/id 排序后能回看：
 * 谁在什么状态下接管、观测、提闸门调整、刷新快照、撤回、转人工或复核解除。
 */
@Entity
@Table(name = "emergency_event")
@Getter
@Setter
public class EmergencyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emergency_order_id", nullable = false)
    private Long emergencyOrderId;

    /** CREATE/TAKE/OBSERVE/GATE_PROPOSE/GATE_WITHDRAW/REVIEW/REFRESH/MANUAL/RESOLVE */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "gate_id")
    private Long gateId;

    @Column(name = "gate_operation_id")
    private Long gateOperationId;
}
