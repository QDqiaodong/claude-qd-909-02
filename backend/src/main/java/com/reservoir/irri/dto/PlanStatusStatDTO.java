package com.reservoir.irri.dto;

import com.reservoir.irri.common.Dict;
import lombok.Data;

/** JPQL `select new ...` 专用：灌溉计划状态分布。构造器显式声明，参数个数与 JPQL 一致。 */
@Data
public class PlanStatusStatDTO {

    private String status;
    private String statusName;
    private String color;
    private Long planCount;

    public PlanStatusStatDTO() {
    }

    public PlanStatusStatDTO(String status, Long planCount) {
        this.status = status;
        this.planCount = planCount == null ? 0L : planCount;
        this.statusName = Dict.planStatusName(status);
        this.color = Dict.planStatusColor(status);
    }
}
