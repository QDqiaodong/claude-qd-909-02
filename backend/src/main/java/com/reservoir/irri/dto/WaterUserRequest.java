package com.reservoir.irri.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 用水户新增 / 修改入参 */
@Data
public class WaterUserRequest {

    private String userCode;
    private String userName;
    private String village;
    private String phone;
    private BigDecimal irrigatedAreaMu;
    private String cropType;
    private Long canalSegmentId;
    private String status;
    private String note;
}
