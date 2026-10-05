package com.reservoir.irri.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 前端视图对象集合：列表接口统一返回这里的结构（带中文名称、颜色、派生比率），
 * 避免前端自己拼字典与算比率。
 */
public final class Views {

    private Views() {
    }

    // ---------------- 模块一：渠系与渠段 ----------------

    /** 纵向水系拓扑图里的一个渠段节点 */
    @Data
    public static class SegmentVO {
        private Long id;
        private Long canalId;
        private String canalCode;
        private String canalName;
        private String canalGrade;
        private String canalGradeName;
        private String canalGradeColor;
        private Integer canalGradeStroke;
        private String segmentCode;
        private String segmentName;
        private Integer sortNo;
        private BigDecimal startMileage;
        private BigDecimal endMileage;
        private BigDecimal lengthKm;
        private BigDecimal designFlow;
        private BigDecimal designLowLevel;
        private BigDecimal designHighLevel;
        private String liningType;
        private String liningTypeName;
        private Long upstreamId;
        /** 上游渠段编号，衔接校验提示用 */
        private String upstreamCode;
        private String status;
        private String statusName;
        private String statusColor;
        private String note;
        /** 停用渠段不允许配水 */
        private Boolean disabled = Boolean.FALSE;
        /** 该渠段上的闸门数 */
        private Long gateCount = 0L;
        /** 该渠段当日已排配水流量合计（m³/s） */
        private BigDecimal allottedFlow = BigDecimal.ZERO;
    }

    /** 拓扑图里的一条渠道（含其下渠段节点） */
    @Data
    public static class CanalVO {
        private Long id;
        private String canalCode;
        private String canalName;
        private String canalGrade;
        private String canalGradeName;
        private String canalGradeColor;
        private Integer canalGradeStroke;
        private String sourceName;
        private BigDecimal lengthKm;
        private BigDecimal designFlow;
        private Integer segmentCount = 0;
        private String manager;
        private Integer builtYear;
        private String status;
        private String statusName;
        private String statusColor;
        private String note;
        private Boolean disabled = Boolean.FALSE;
        private List<SegmentVO> segments = new ArrayList<>();
    }

    /** 点节点看渠段详情 */
    @Data
    public static class SegmentDetailVO {
        private SegmentVO segment;
        /** 该渠段上的闸门 */
        private List<GateVO> gates = new ArrayList<>();
        /** 该渠段近 10 条水位量测（含派生列） */
        private List<LevelVO> levels = new ArrayList<>();
        /** 该渠段未完成的配水安排 */
        private List<AllotmentVO> allotments = new ArrayList<>();
    }

    // ---------------- 模块二：闸门与开度操作 ----------------

    /** 闸门（带开度安全区与当前开度） */
    @Data
    public static class GateVO {
        private Long id;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String gateCode;
        private String gateName;
        private String gateType;
        private String gateTypeName;
        private String gateTypeColor;
        private BigDecimal currentOpening;
        private BigDecimal safeMinOpening;
        private BigDecimal safeMaxOpening;
        private BigDecimal designFlow;
        private String keeper;
        private String lastOperateTime;
        private String status;
        private String statusName;
        private String note;
        private Boolean disabled = Boolean.FALSE;
        /** 当前开度是否落在安全区内 */
        private Boolean inSafeBand = Boolean.FALSE;
        /** 该闸门的操作记录数 */
        private Long operationCount = 0L;
        /** 待审批操作数 */
        private Long pendingCount = 0L;
        /** 当前生效开度下的估算过流量（m³/s）= 设计流量 × 开度% */
        private BigDecimal estimateFlow = BigDecimal.ZERO;
    }

    /** 闸门操作记录 */
    @Data
    public static class OperationVO {
        private Long id;
        private Long gateId;
        private String gateCode;
        private String gateName;
        private String operationNo;
        private BigDecimal beforeOpening;
        private BigDecimal afterOpening;
        private BigDecimal adjustRange;
        private String operateTime;
        private String operator;
        private String status;
        private String statusName;
        private String statusColor;
        private String reason;
        private String approver;
        private String approveTime;
        private String note;
        /** 是否超过审批阈值（30 个百分点） */
        private Boolean needApprove = Boolean.FALSE;
        /** 由哪张险情处置单提出；历史普通闸门记录可为空，原记录照常查看 */
        private Long emergencyOrderId;
    }

    // ---------------- 模块三：灌溉计划与配水 ----------------

    /** 灌溉计划 + 它的配水甘特条 */
    @Data
    public static class PlanVO {
        private Long id;
        private String planCode;
        private String planName;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String startDate;
        private String endDate;
        private Integer planDays;
        private BigDecimal planFlow;
        private BigDecimal planVolume;
        private String cropType;
        private String cropTypeName;
        private String cropTypeColor;
        private BigDecimal irrigatedAreaMu;
        private String status;
        private String statusName;
        private String statusColor;
        private String approver;
        private String approveTime;
        private String note;
        /** 已批准及之后：不允许再改水量 */
        private Boolean locked = Boolean.FALSE;
        /** 已排配水量合计（万m³） */
        private BigDecimal allottedVolume = BigDecimal.ZERO;
        private List<AllotmentVO> allotments = new ArrayList<>();
    }

    /** 一条配水甘特条 */
    @Data
    public static class AllotmentVO {
        private Long id;
        private Long planId;
        private String planCode;
        private String planName;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String allotDate;
        private BigDecimal flow;
        private Integer hours;
        private BigDecimal volumeWan;
        private String status;
        private String statusName;
        private String statusColor;
        private String note;
        /** 所属渠段设计流量 */
        private BigDecimal designFlow = BigDecimal.ZERO;
        /** 该渠段该日已配流量合计（含本条） */
        private BigDecimal dayTotalFlow = BigDecimal.ZERO;
        /** 占设计流量比例 0~1 */
        private Double loadRate = 0D;
    }

    /** 甘特图的日期轴 + 每日负载 */
    @Data
    public static class PlanGanttVO {
        private String startDate;
        private String endDate;
        private List<String> dates = new ArrayList<>();
        private List<PlanVO> plans = new ArrayList<>();
        private List<AllotmentVO> allotments = new ArrayList<>();
        /** 每日各渠段配水流量合计（用于日负载色带） */
        private List<DayLoadVO> dayLoads = new ArrayList<>();
        /** 与日期轴相交的生效停水窗口（甘特停水色带） */
        private List<OutageWindowVO> windows = new ArrayList<>();
        /** 图上计划 / 配水条相关的避让项（待避让标记与最终去向） */
        private List<AvoidItemVO> avoidItems = new ArrayList<>();
    }

    /** 某渠段某日的配水负载 */
    @Data
    public static class DayLoadVO {
        private String allotDate;
        private Long canalSegmentId;
        private String segmentCode;
        private BigDecimal totalFlow = BigDecimal.ZERO;
        private BigDecimal designFlow = BigDecimal.ZERO;
        private Double loadRate = 0D;
        /** 剩余可配流量（m³/s） */
        private BigDecimal remainFlow = BigDecimal.ZERO;
        /** 剩余可配水量（万m³）= 剩余流量 × 8.64 */
        private BigDecimal remainVolume = BigDecimal.ZERO;
    }

    // ---------------- 模块三补：渠段检修停水窗口与避让 ----------------

    /** 停水窗口（渠段检修停水起止 + 原因 + 范围） */
    @Data
    public static class OutageWindowVO {
        private Long id;
        private String windowNo;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String startAt;
        private String endAt;
        /** 覆盖的整日区间（跨日窗口按覆盖日展开，甘特色带用） */
        private String startDay;
        private String endDay;
        private Integer coverDays = 0;
        private String reason;
        private String scopeType;
        private String scopeTypeName;
        private String scopeNote;
        private String status;
        private String statusName;
        private String statusColor;
        private String createdBy;
        private String createdAt;
        private String updatedAt;
        private String canceledAt;
        private String cancelNote;
        private String note;
        /** 待避让项数 */
        private Long pendingCount = 0L;
        /** 已人工处理（顺延 / 拆分 / 例外保留）项数 */
        private Long dealtCount = 0L;
        /** 窗口撤销 / 变更时自动恢复的项数 */
        private Long restoredCount = 0L;
    }

    /** 一条避让项：窗口命中的计划或配水条，含最终去向 */
    @Data
    public static class AvoidItemVO {
        private Long id;
        private Long windowId;
        private String windowNo;
        /** 停水原因（随窗口带出，甘特与窗口页都直接显示） */
        private String windowReason;
        private String targetType;
        private String targetTypeName;
        private Long planId;
        private String planCode;
        private String planName;
        private Long allotmentId;
        private String allotDate;
        private BigDecimal allotFlow;
        private Long canalSegmentId;
        private String segmentCode;
        private String origStartDate;
        private String origEndDate;
        private String status;
        private String statusName;
        private String statusColor;
        /** 最终去向：顺延 / 拆分 / 例外保留的处理说明 */
        private String resolutionNote;
        private String newStartDate;
        private String newEndDate;
        private Long splitPlanId;
        private String splitPlanCode;
        private String operator;
        private String resolvedAt;
        private String createdAt;
    }

    // ---------------- 模块四：水位量测 ----------------

    /** 水位量测：带值对象派生列 */
    @Data
    public static class LevelVO {
        private Long id;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String stationCode;
        private String startAt;
        private String endAt;
        /** 【派生】持续分钟数（MeasureWindow 值对象派生） */
        private Integer durationMinutes;
        private BigDecimal lowLevel;
        private BigDecimal highLevel;
        /** 【派生】变幅（LevelBand 值对象派生） */
        private BigDecimal bandSpan;
        /** 【派生】变幅安全等级（LevelBand 值对象派生） */
        private String safetyGrade;
        private String safetyGradeName;
        private String safetyGradeColor;
        private BigDecimal measuredLevel;
        private String observer;
        private Integer abnormal;
        private String abnormalReason;
        private String createdAt;
        private String note;
        /** 渠段设计水位区间，折线图上下限用 */
        private BigDecimal designLowLevel;
        private BigDecimal designHighLevel;
        /** 该异常记录是否已有未结束处置单 */
        private Boolean emergencyOpen = Boolean.FALSE;
        private Long emergencyOrderId;
        private String emergencyStatus;
        private String emergencyStatusName;
        private String emergencyHandler;
    }

    /** 折线图：一个测站 + 若干点位 */
    @Data
    public static class LevelSeriesVO {
        private String stationCode;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private BigDecimal designLowLevel;
        private BigDecimal designHighLevel;
        private List<LevelPointVO> points = new ArrayList<>();
        /** 异常点数 */
        private Integer abnormalCount = 0;
    }

    /** 折线图上的一个点位 */
    @Data
    public static class LevelPointVO {
        private Long id;
        private String label;
        private BigDecimal value;
        private BigDecimal lowLevel;
        private BigDecimal highLevel;
        /** 【派生】持续分钟数 */
        private Integer durationMinutes;
        /** 【派生】变幅 */
        private BigDecimal bandSpan;
        private String safetyGrade;
        private Integer abnormal;
        private String abnormalReason;
        private Boolean emergencyOpen = Boolean.FALSE;
        private Long emergencyOrderId;
    }

    // ---------------- 模块四补：异常水位险情处置台 ----------------

    /** 处置单列表项 */
    @Data
    public static class EmergencyOrderVO {
        private Long id;
        private String orderNo;
        private Long waterLevelId;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String stationCode;
        private String sourceWindowStart;
        private String sourceWindowEnd;
        private BigDecimal sourceMeasuredLevel;
        private BigDecimal sourceLowLevel;
        private BigDecimal sourceHighLevel;
        private String sourceAbnormalReason;
        private String sourceSegmentStatus;
        private String sourceSegmentStatusName;
        private String status;
        private String statusName;
        private String statusColor;
        private String handler;
        private String takenOverAt;
        private String nextReviewAt;
        private String decisionMode;
        private String decisionModeName;
        private String decisionComment;
        private String latestGateOperationNo;
        private String latestGateOperationStatus;
        private String resolvedAt;
        private String resolver;
        private String resolutionNote;
        private String manualReason;
        private Integer version;
        private String createdAt;
        private String updatedAt;
        private Boolean open = Boolean.TRUE;
        /** 复查时限是否已到 */
        private Boolean reviewDue = Boolean.FALSE;
    }

    /** 处置单详情：发起快照、最新上下文、闸门、变更清单、时间线 */
    @Data
    public static class EmergencyDetailVO {
        private EmergencyOrderVO order;
        private EmergencyContextVO source;
        private EmergencyContextVO latest;
        private List<EmergencyGateVO> snapshotGates = new ArrayList<>();
        private List<EmergencyChangeVO> changes = new ArrayList<>();
        private List<EmergencyEventVO> timeline = new ArrayList<>();
        private List<OperationVO> gateOperations = new ArrayList<>();
        /** 发起后的后续水位（含发起记录），按时间正序 */
        private List<LevelVO> followingLevels = new ArrayList<>();
        private Boolean stale = Boolean.FALSE;
    }

    @Data
    public static class EmergencyContextVO {
        private Integer versionNo;
        private Long waterLevelId;
        private BigDecimal measuredLevel;
        private Integer abnormal;
        private String abnormalReason;
        private String segmentStatus;
        private String segmentStatusName;
        private String segmentName;
        private BigDecimal designLowLevel;
        private BigDecimal designHighLevel;
        private String createdAt;
    }

    @Data
    public static class EmergencyGateVO {
        private Long gateId;
        private Integer versionNo;
        private String gateCode;
        private String gateName;
        private String gateType;
        private String gateTypeName;
        private BigDecimal snapshotOpening;
        private BigDecimal currentOpening;
        private BigDecimal safeMinOpening;
        private BigDecimal safeMaxOpening;
        private String snapshotStatus;
        private String snapshotStatusName;
        private String currentStatus;
        private String currentStatusName;
        private String keeper;
        private String snapshotLastOperateTime;
        private String currentLastOperateTime;
        private Long snapshotOperationId;
        private String snapshotOperationNo;
        private String snapshotOperationStatus;
        private Long currentOperationId;
        private String currentOperationNo;
        private String currentOperationStatus;
        private Boolean changed = Boolean.FALSE;
        private Boolean operable = Boolean.FALSE;
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class EmergencyChangeVO {
        private String type;
        private String label;
        private String snapshotValue;
        private String currentValue;
        private String detail;
    }

    @Data
    public static class EmergencyEventVO {
        private Long id;
        private String type;
        private String typeName;
        private String fromStatus;
        private String fromStatusName;
        private String toStatus;
        private String toStatusName;
        private String operator;
        private String eventTime;
        private String detail;
        private Long gateId;
        private String gateCode;
        private Long gateOperationId;
        private String gateOperationNo;
    }

    // ---------------- 模块五：用水户与用水申请 ----------------

    /** 用水户 */
    @Data
    public static class UserVO {
        private Long id;
        private String userCode;
        private String userName;
        private String village;
        private String phone;
        private BigDecimal irrigatedAreaMu;
        private String cropType;
        private String cropTypeName;
        private String cropTypeColor;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String status;
        private String statusName;
        private String note;
        private Boolean arrear = Boolean.FALSE;
        /** 历史申请数 */
        private Long requestCount = 0L;
    }

    /** 申请卡（三列审批流用） */
    @Data
    public static class RequestVO {
        private Long id;
        private String requestNo;
        private Long waterUserId;
        private String userCode;
        private String userName;
        private String village;
        private Long canalSegmentId;
        private String segmentCode;
        private String segmentName;
        private String allotDate;
        private BigDecimal requestVolume;
        private String cropType;
        private String cropTypeName;
        private String cropTypeColor;
        private BigDecimal applyAreaMu;
        private String urgency;
        private String urgencyName;
        private String status;
        private String statusName;
        private String statusColor;
        private String rejectReason;
        private String approver;
        private String applyTime;
        private String approveTime;
        private String supplyTime;
        private String note;
        /** 该渠段该日剩余可配水量（万m³） */
        private BigDecimal remainVolume = BigDecimal.ZERO;
        /** 申请量占剩余额度比例 0~1 */
        private Double quotaRate = 0D;
    }

    // ---------------- 统计 ----------------

    /** 顶部概览数字 */
    @Data
    public static class OverviewVO {
        private Long canalCount;
        private Long segmentCount;
        private Long gateCount;
        private Long operationCount;
        private Long pendingOperationCount;
        private Long planCount;
        private Long allotmentCount;
        private Long levelCount;
        private Long abnormalLevelCount;
        private Long userCount;
        private Long requestCount;
        private Long pendingRequestCount;
        /** 灌区设计流量合计（m³/s） */
        private BigDecimal totalDesignFlow = BigDecimal.ZERO;
        /** 灌溉面积合计（亩） */
        private BigDecimal totalAreaMu = BigDecimal.ZERO;
    }

    /** 通用两列分布（名称 + 数量 + 颜色） */
    @Data
    public static class StatItemVO {
        private String key;
        private String name;
        private Long count;
        private String color;

        public StatItemVO() {
        }

        public StatItemVO(String key, String name, Long count, String color) {
            this.key = key;
            this.name = name;
            this.count = count;
            this.color = color;
        }
    }

    /** 折线图通用点 */
    @Data
    public static class TrendPointVO {
        private String label;
        private BigDecimal value;

        public TrendPointVO() {
        }

        public TrendPointVO(String label, BigDecimal value) {
            this.label = label;
            this.value = value;
        }
    }

    /** 派生列说明（前端把这几列标成「派生」） */
    @Data
    public static class DerivedColumnVO {
        private String field;
        private String label;
        private String owner;
        private String instantiator;

        public DerivedColumnVO() {
        }

        public DerivedColumnVO(String field, String label, String owner, String instantiator) {
            this.field = field;
            this.label = label;
            this.owner = owner;
            this.instantiator = instantiator;
        }
    }
}
