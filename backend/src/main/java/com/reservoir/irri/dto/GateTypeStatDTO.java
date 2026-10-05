package com.reservoir.irri.dto;

import com.reservoir.irri.common.Dict;
import lombok.Data;

/** JPQL `select new ...` 专用：闸门型式分布。构造器显式声明，参数个数与 JPQL 一致。 */
@Data
public class GateTypeStatDTO {

    private String gateType;
    private String gateTypeName;
    private String color;
    private Long gateCount;
    private Long disabledCount;

    public GateTypeStatDTO() {
    }

    public GateTypeStatDTO(String gateType, Long gateCount, Long disabledCount) {
        this.gateType = gateType;
        this.gateCount = gateCount == null ? 0L : gateCount;
        this.disabledCount = disabledCount == null ? 0L : disabledCount;
        this.gateTypeName = Dict.gateTypeName(gateType);
        this.color = Dict.gateTypeColor(gateType);
    }
}
