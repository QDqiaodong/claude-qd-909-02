package com.reservoir.irri.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 渠道新增 / 修改入参（局部更新：为 null 的字段不改） */
@Data
public class CanalRequest {

    private String canalCode;
    private String canalName;
    private String canalGrade;
    private String sourceName;
    private BigDecimal lengthKm;
    private BigDecimal designFlow;
    private String manager;
    private Integer builtYear;
    private String status;
    private String note;
}
