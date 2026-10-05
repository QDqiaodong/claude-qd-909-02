package com.reservoir.irri.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 停水窗口登记 / 变更入参 */
@Data
public class OutageWindowRequest {

    private Long canalSegmentId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private String scopeType;
    private String scopeNote;
    private String createdBy;
    private String note;
}
