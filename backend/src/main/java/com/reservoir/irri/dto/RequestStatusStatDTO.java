package com.reservoir.irri.dto;

import com.reservoir.irri.common.Dict;
import lombok.Data;

/** JPQL `select new ...` 专用：用水申请状态分布。构造器显式声明，参数个数与 JPQL 一致。 */
@Data
public class RequestStatusStatDTO {

    private String status;
    private String statusName;
    private String color;
    private Long requestCount;

    public RequestStatusStatDTO() {
    }

    public RequestStatusStatDTO(String status, Long requestCount) {
        this.status = status;
        this.requestCount = requestCount == null ? 0L : requestCount;
        this.statusName = Dict.requestStatusName(status);
        this.color = Dict.requestStatusColor(status);
    }
}
