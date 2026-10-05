package com.reservoir.irri.dto;

import com.reservoir.irri.common.Dict;
import lombok.Data;

/**
 * JPQL `select new ...` 专用：渠道等级分布。
 * 构造器必须显式写出且参数个数与 JPQL 列一一对应，不能只靠 Lombok 的 @AllArgsConstructor。
 */
@Data
public class CanalGradeStatDTO {

    private String canalGrade;
    private String canalGradeName;
    private String color;
    private Long canalCount;
    private Long segmentCount;

    public CanalGradeStatDTO() {
    }

    public CanalGradeStatDTO(String canalGrade, Long canalCount, Long segmentCount) {
        this.canalGrade = canalGrade;
        this.canalCount = canalCount == null ? 0L : canalCount;
        this.segmentCount = segmentCount == null ? 0L : segmentCount;
        this.canalGradeName = Dict.canalGradeName(canalGrade);
        this.color = Dict.canalGradeColor(canalGrade);
    }
}
