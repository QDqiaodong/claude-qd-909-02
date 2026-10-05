package com.reservoir.irri.dto;

import lombok.Data;

/**
 * JPQL `select new ...` 专用的统计 DTO。
 * 注意：这里的构造器必须显式写出来且参数个数与 JPQL 里的列一一对应，
 * 只靠 Lombok 的 @AllArgsConstructor 会在查询期报 "Missing constructor"。
 */
public final class StatDTOs {

    private StatDTOs() {
    }

    /** 渠道等级分布：等级 → 渠道数、渠段数 */
    @Data
    public static class CanalGradeStatDTO {

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
            this.canalGradeName = com.reservoir.irri.common.Dict.canalGradeName(canalGrade);
            this.color = com.reservoir.irri.common.Dict.canalGradeColor(canalGrade);
        }
    }

    /** 闸门型式分布：型式 → 闸门数、停用闸门数 */
    @Data
    public static class GateTypeStatDTO {

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
            this.gateTypeName = com.reservoir.irri.common.Dict.gateTypeName(gateType);
            this.color = com.reservoir.irri.common.Dict.gateTypeColor(gateType);
        }
    }

    /** 灌溉计划状态分布 */
    @Data
    public static class PlanStatusStatDTO {

        private String status;
        private String statusName;
        private String color;
        private Long planCount;

        public PlanStatusStatDTO() {
        }

        public PlanStatusStatDTO(String status, Long planCount) {
            this.status = status;
            this.planCount = planCount == null ? 0L : planCount;
            this.statusName = com.reservoir.irri.common.Dict.planStatusName(status);
            this.color = com.reservoir.irri.common.Dict.planStatusColor(status);
        }
    }

    /** 用水申请状态分布 */
    @Data
    public static class RequestStatusStatDTO {

        private String status;
        private String statusName;
        private String color;
        private Long requestCount;

        public RequestStatusStatDTO() {
        }

        public RequestStatusStatDTO(String status, Long requestCount) {
            this.status = status;
            this.requestCount = requestCount == null ? 0L : requestCount;
            this.statusName = com.reservoir.irri.common.Dict.requestStatusName(status);
            this.color = com.reservoir.irri.common.Dict.requestStatusColor(status);
        }
    }
}
