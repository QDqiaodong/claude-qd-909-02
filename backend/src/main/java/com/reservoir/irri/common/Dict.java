package com.reservoir.irri.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 水库灌区词典与业务规则参数表：渠道等级、衬砌型式、闸门型式、开度安全区、计划状态、
 * 量测变幅安全等级（由 LevelBand 值对象派生）、用水申请流转、作物类型等。
 * 规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 渠道等级（3 级：干渠 / 支渠 / 斗渠） ----------------
    private static final Map<String, String> CANAL_GRADE = Map.of(
            "TRUNK", "干渠",
            "BRANCH", "支渠",
            "LATERAL", "斗渠");

    /** 三级渠系在纵向水系拓扑图里的层级（0 最粗，2 最细） */
    private static final Map<String, Integer> CANAL_GRADE_LEVEL = Map.of(
            "TRUNK", 0,
            "BRANCH", 1,
            "LATERAL", 2);

    private static final Map<String, String> CANAL_GRADE_COLOR = Map.of(
            "TRUNK", "#01579b",
            "BRANCH", "#0288d1",
            "LATERAL", "#4fc3f7");

    /** 各级渠道的线条宽度（SVG 拓扑图用，px） */
    private static final Map<String, Integer> CANAL_GRADE_STROKE = Map.of(
            "TRUNK", 10,
            "BRANCH", 6,
            "LATERAL", 3);

    // ---------------- 渠道 / 渠段状态 ----------------
    private static final Map<String, String> CANAL_STATUS = Map.of(
            "ACTIVE", "通水",
            "MAINTAIN", "岁修",
            "DISABLED", "停用");

    private static final Map<String, String> CANAL_STATUS_COLOR = Map.of(
            "ACTIVE", "#0288d1",
            "MAINTAIN", "#f9a825",
            "DISABLED", "#90a4ae");

    // ---------------- 衬砌型式 ----------------
    private static final Map<String, String> LINING_TYPE = Map.of(
            "CONCRETE", "混凝土衬砌",
            "MASONRY", "浆砌石",
            "EARTH", "土渠",
            "FILM", "膜料防渗");

    // ---------------- 闸门型式 ----------------
    private static final Map<String, String> GATE_TYPE = Map.of(
            "INTAKE", "进水闸",
            "CONTROL", "节制闸",
            "DIVERSION", "分水闸",
            "DRAIN", "退水闸");

    private static final Map<String, String> GATE_TYPE_COLOR = Map.of(
            "INTAKE", "#00838f",
            "CONTROL", "#0288d1",
            "DIVERSION", "#5e35b1",
            "DRAIN", "#e53935");

    /** 闸门状态 */
    private static final Map<String, String> GATE_STATUS = Map.of(
            "ACTIVE", "运行",
            "MAINTAIN", "检修",
            "DISABLED", "停用");

    // ---------------- 闸门操作状态（4 态） ----------------
    private static final Map<String, String> OPERATION_STATUS = Map.of(
            "PENDING", "待审批",
            "APPROVED", "已批准",
            "REJECTED", "已驳回",
            "EXECUTED", "已执行");

    private static final Map<String, String> OPERATION_STATUS_COLOR = Map.of(
            "PENDING", "#f9a825",
            "APPROVED", "#0288d1",
            "REJECTED", "#e53935",
            "EXECUTED", "#43a047");

    // ---------------- 灌溉计划状态（5 态顺序推进） ----------------
    private static final Map<String, String> PLAN_STATUS = Map.of(
            "DRAFT", "编制",
            "PENDING", "待审批",
            "APPROVED", "已批准",
            "RUNNING", "配水中",
            "FINISHED", "已结束");

    /** 计划状态推进顺序：编制 → 待审批 → 已批准 → 配水中 → 已结束 */
    public static final List<String> PLAN_FLOW = List.of("DRAFT", "PENDING", "APPROVED", "RUNNING", "FINISHED");

    private static final Map<String, String> PLAN_STATUS_COLOR = Map.of(
            "DRAFT", "#90a4ae",
            "PENDING", "#f9a825",
            "APPROVED", "#0288d1",
            "RUNNING", "#26c6da",
            "FINISHED", "#43a047");

    /** 已批准及之后的状态：不允许再改计划配水流量 */
    private static final Set<String> PLAN_LOCKED_STATUS = Set.of("APPROVED", "RUNNING", "FINISHED");

    // ---------------- 配水状态 ----------------
    private static final Map<String, String> ALLOT_STATUS = Map.of(
            "PLANNED", "已排期",
            "SUPPLYING", "配水中",
            "DONE", "已完成",
            "CANCELED", "已取消");

    private static final Map<String, String> ALLOT_STATUS_COLOR = Map.of(
            "PLANNED", "#90a4ae",
            "SUPPLYING", "#0288d1",
            "DONE", "#43a047",
            "CANCELED", "#e53935");

    // ---------------- 渠段检修停水窗口 ----------------
    private static final Map<String, String> OUTAGE_STATUS = Map.of(
            "ACTIVE", "生效中",
            "CANCELED", "已撤销");

    private static final Map<String, String> OUTAGE_STATUS_COLOR = Map.of(
            "ACTIVE", "#e53935",
            "CANCELED", "#90a4ae");

    /** 停水范围：全段停水 / 局部停水（局部停水也要标避让，由调度员决定是否例外保留） */
    private static final Map<String, String> OUTAGE_SCOPE = Map.of(
            "FULL", "全段停水",
            "PARTIAL", "局部停水");

    // ---------------- 停水窗口避让项 ----------------
    private static final Map<String, String> AVOID_STATUS = Map.of(
            "PENDING", "待避让",
            "POSTPONED", "已顺延",
            "SPLIT", "已拆分",
            "KEPT", "例外保留",
            "RESTORED", "已恢复");

    private static final Map<String, String> AVOID_STATUS_COLOR = Map.of(
            "PENDING", "#f9a825",
            "POSTPONED", "#0288d1",
            "SPLIT", "#5e35b1",
            "KEPT", "#8d6e63",
            "RESTORED", "#43a047");

    /** 避让处理方式：整体顺延 / 拆分改期 / 例外保留 */
    private static final Map<String, String> AVOID_ACTION = Map.of(
            "POSTPONE", "整体顺延",
            "SPLIT", "拆分改期",
            "KEEP", "例外保留");

    // ---------------- 量测变幅安全等级（由 LevelBand 值对象派生） ----------------
    private static final Map<String, String> SAFETY_GRADE = Map.of(
            "STEADY", "平稳",
            "NORMAL", "常规",
            "SURGE", "骤变");

    private static final Map<String, String> SAFETY_GRADE_COLOR = Map.of(
            "STEADY", "#43a047",
            "NORMAL", "#0288d1",
            "SURGE", "#e53935");

    /** 变幅 < 0.20 m 判为平稳 */
    public static final BigDecimal BAND_SURGE_STEADY = BigDecimal.valueOf(0.20);

    /** 变幅 < 0.60 m 判为常规，否则骤变 */
    public static final BigDecimal BAND_SURGE_NORMAL = BigDecimal.valueOf(0.60);

    // ---------------- 作物类型 ----------------
    private static final Map<String, String> CROP_TYPE = Map.of(
            "RICE", "水稻",
            "WHEAT", "冬小麦",
            "MAIZE", "玉米",
            "COTTON", "棉花",
            "VEGETABLE", "蔬菜");

    private static final Map<String, String> CROP_TYPE_COLOR = Map.of(
            "RICE", "#43a047",
            "WHEAT", "#f9a825",
            "MAIZE", "#ff7043",
            "COTTON", "#8d6e63",
            "VEGETABLE", "#26a69a");

    // ---------------- 用水户状态 ----------------
    private static final Map<String, String> USER_STATUS = Map.of(
            "ACTIVE", "在册",
            "ARREAR", "欠费停供",
            "DISABLED", "注销");

    // ---------------- 用水申请状态（4 态） ----------------
    private static final Map<String, String> REQUEST_STATUS = Map.of(
            "PENDING", "待审",
            "APPROVED", "已批",
            "SUPPLIED", "已供水",
            "REJECTED", "已驳回");

    /** 申请卡三列审批流的列顺序 */
    public static final List<String> REQUEST_COLUMNS = List.of("PENDING", "APPROVED", "SUPPLIED");

    private static final Map<String, String> REQUEST_STATUS_COLOR = Map.of(
            "PENDING", "#f9a825",
            "APPROVED", "#0288d1",
            "SUPPLIED", "#43a047",
            "REJECTED", "#e53935");

    /** 已供水 / 已驳回：终态，不允许再推进 */
    private static final Set<String> REQUEST_TERMINAL_STATUS = Set.of("SUPPLIED", "REJECTED");

    // ---------------- 异常水位险情处置 ----------------
    private static final Map<String, String> EMERGENCY_STATUS = Map.of(
            "PENDING", "待接管",
            "HANDLING", "处置中",
            "REVIEW", "待复核",
            "RESOLVED", "已解除",
            "MANUAL", "转人工");

    private static final Map<String, String> EMERGENCY_STATUS_COLOR = Map.of(
            "PENDING", "#e53935",
            "HANDLING", "#f9a825",
            "REVIEW", "#0288d1",
            "RESOLVED", "#43a047",
            "MANUAL", "#8d6e63");

    private static final Set<String> EMERGENCY_OPEN_STATUS = Set.of("PENDING", "HANDLING", "REVIEW");

    private static final Map<String, String> EMERGENCY_EVENT = Map.of(
            "CREATE", "发起处置",
            "TAKE", "接管",
            "OBSERVE", "继续观测",
            "GATE_PROPOSE", "提出闸门调整",
            "GATE_WITHDRAW", "撤回闸门调整",
            "REVIEW", "提交复核",
            "REFRESH", "刷新快照",
            "MANUAL", "转人工",
            "RESOLVE", "复核解除");

    private static final Map<String, String> EMERGENCY_DECISION = Map.of(
            "OBSERVE", "继续观测",
            "GATE", "闸门调整");

    // ---------------- 申请紧急度 ----------------
    private static final Map<String, String> URGENCY = Map.of(
            "NORMAL", "常规",
            "URGENT", "抗旱应急");

    // ---------------- 业务参数上限 ----------------
    /** 闸门开度上限（%） */
    public static final BigDecimal MAX_OPENING = BigDecimal.valueOf(100);

    /** 闸门开度下限（%） */
    public static final BigDecimal MIN_OPENING = BigDecimal.valueOf(0);

    /** 单次调节幅度超过该值（个百分点）必须走审批 */
    public static final BigDecimal APPROVE_THRESHOLD = BigDecimal.valueOf(30);

    /** 单次调节幅度上限（个百分点），超过直接拒绝 */
    public static final BigDecimal MAX_ADJUST_RANGE = BigDecimal.valueOf(80);

    /** 单次配水流量上限（m³/s） */
    public static final BigDecimal MAX_ALLOT_FLOW = BigDecimal.valueOf(60);

    /** 单次配水时长上限（h） */
    public static final int MAX_ALLOT_HOURS = 24;

    /** 用水户灌溉面积上限（亩） */
    public static final BigDecimal MAX_IRRIGATED_AREA_MU = BigDecimal.valueOf(5000);

    /** 一次量测时段上限（分钟）：超过即视为录入有误 */
    public static final long MAX_WINDOW_MINUTES = 24 * 60L;

    /** 1 m³/s 恒定放水一天的来水量（万m³）：86400 m³ = 8.64 万m³ */
    public static final BigDecimal DAY_FLOW_TO_VOLUME = BigDecimal.valueOf(8.64);

    /** 配水量换算：flow(m³/s) × hours(h) × 3600 / 10000 = 万m³ */
    public static final BigDecimal HOUR_FLOW_TO_VOLUME = BigDecimal.valueOf(0.36);

    private static String name(Map<String, String> source, String key) {
        if (key == null) {
            return "";
        }
        return source.getOrDefault(key, key);
    }

    // ---------------- 取值方法 ----------------

    public static boolean isValidCanalGrade(String grade) {
        return grade != null && CANAL_GRADE.containsKey(grade);
    }

    public static String canalGradeName(String grade) {
        return name(CANAL_GRADE, grade);
    }

    public static int canalGradeLevel(String grade) {
        return CANAL_GRADE_LEVEL.getOrDefault(grade, 2);
    }

    public static String canalGradeColor(String grade) {
        return grade == null ? "#90a4ae" : CANAL_GRADE_COLOR.getOrDefault(grade, "#90a4ae");
    }

    public static int canalGradeStroke(String grade) {
        return CANAL_GRADE_STROKE.getOrDefault(grade, 3);
    }

    public static boolean isValidCanalStatus(String status) {
        return status != null && CANAL_STATUS.containsKey(status);
    }

    public static String canalStatusName(String status) {
        return name(CANAL_STATUS, status);
    }

    public static String canalStatusColor(String status) {
        return status == null ? "#90a4ae" : CANAL_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isValidLiningType(String type) {
        return type != null && LINING_TYPE.containsKey(type);
    }

    public static String liningTypeName(String type) {
        return name(LINING_TYPE, type);
    }

    public static boolean isValidGateType(String type) {
        return type != null && GATE_TYPE.containsKey(type);
    }

    public static String gateTypeName(String type) {
        return name(GATE_TYPE, type);
    }

    public static String gateTypeColor(String type) {
        return type == null ? "#90a4ae" : GATE_TYPE_COLOR.getOrDefault(type, "#90a4ae");
    }

    public static boolean isValidGateStatus(String status) {
        return status != null && GATE_STATUS.containsKey(status);
    }

    public static String gateStatusName(String status) {
        return name(GATE_STATUS, status);
    }

    public static boolean isValidOperationStatus(String status) {
        return status != null && OPERATION_STATUS.containsKey(status);
    }

    public static String operationStatusName(String status) {
        return name(OPERATION_STATUS, status);
    }

    public static String operationStatusColor(String status) {
        return status == null ? "#90a4ae" : OPERATION_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isValidPlanStatus(String status) {
        return status != null && PLAN_STATUS.containsKey(status);
    }

    public static String planStatusName(String status) {
        return name(PLAN_STATUS, status);
    }

    public static String planStatusColor(String status) {
        return status == null ? "#90a4ae" : PLAN_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    /** 计划状态的下一站；已是终态返回 null */
    public static String nextPlanStatus(String status) {
        int index = PLAN_FLOW.indexOf(status);
        if (index < 0 || index >= PLAN_FLOW.size() - 1) {
            return null;
        }
        return PLAN_FLOW.get(index + 1);
    }

    /** 已批准 / 配水中 / 已结束：不允许再改计划配水流量与周期 */
    public static boolean isPlanLocked(String status) {
        return status != null && PLAN_LOCKED_STATUS.contains(status);
    }

    public static boolean isValidAllotStatus(String status) {
        return status != null && ALLOT_STATUS.containsKey(status);
    }

    public static String allotStatusName(String status) {
        return name(ALLOT_STATUS, status);
    }

    public static boolean isValidOutageStatus(String status) {
        return status != null && OUTAGE_STATUS.containsKey(status);
    }

    public static String outageStatusName(String status) {
        return name(OUTAGE_STATUS, status);
    }

    public static String outageStatusColor(String status) {
        return status == null ? "#90a4ae" : OUTAGE_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isValidOutageScope(String scope) {
        return scope != null && OUTAGE_SCOPE.containsKey(scope);
    }

    public static String outageScopeName(String scope) {
        return name(OUTAGE_SCOPE, scope);
    }

    public static boolean isValidAvoidStatus(String status) {
        return status != null && AVOID_STATUS.containsKey(status);
    }

    public static String avoidStatusName(String status) {
        return name(AVOID_STATUS, status);
    }

    public static String avoidStatusColor(String status) {
        return status == null ? "#90a4ae" : AVOID_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isValidAvoidAction(String action) {
        return action != null && AVOID_ACTION.containsKey(action);
    }

    public static String avoidActionName(String action) {
        return name(AVOID_ACTION, action);
    }

    public static String allotStatusColor(String status) {
        return status == null ? "#90a4ae" : ALLOT_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isValidSafetyGrade(String grade) {
        return grade != null && SAFETY_GRADE.containsKey(grade);
    }

    public static String safetyGradeName(String grade) {
        return name(SAFETY_GRADE, grade);
    }

    public static String safetyGradeColor(String grade) {
        return grade == null ? "#90a4ae" : SAFETY_GRADE_COLOR.getOrDefault(grade, "#90a4ae");
    }

    public static boolean isValidCropType(String type) {
        return type != null && CROP_TYPE.containsKey(type);
    }

    public static String cropTypeName(String type) {
        return name(CROP_TYPE, type);
    }

    public static String cropTypeColor(String type) {
        return type == null ? "#90a4ae" : CROP_TYPE_COLOR.getOrDefault(type, "#90a4ae");
    }

    public static boolean isValidUserStatus(String status) {
        return status != null && USER_STATUS.containsKey(status);
    }

    public static String userStatusName(String status) {
        return name(USER_STATUS, status);
    }

    public static boolean isValidRequestStatus(String status) {
        return status != null && REQUEST_STATUS.containsKey(status);
    }

    public static String requestStatusName(String status) {
        return name(REQUEST_STATUS, status);
    }

    public static String requestStatusColor(String status) {
        return status == null ? "#90a4ae" : REQUEST_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    /** 已供水 / 已驳回为终态，不允许再推进 */
    public static boolean isTerminalRequestStatus(String status) {
        return status != null && REQUEST_TERMINAL_STATUS.contains(status);
    }

    public static boolean isValidUrgency(String urgency) {
        return urgency != null && URGENCY.containsKey(urgency);
    }

    public static String urgencyName(String urgency) {
        return name(URGENCY, urgency);
    }

    public static String emergencyStatusName(String status) {
        return name(EMERGENCY_STATUS, status);
    }

    public static String emergencyStatusColor(String status) {
        return status == null ? "#90a4ae" : EMERGENCY_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    public static boolean isEmergencyOpen(String status) {
        return status != null && EMERGENCY_OPEN_STATUS.contains(status);
    }

    public static String emergencyEventName(String type) {
        return name(EMERGENCY_EVENT, type);
    }

    public static String emergencyDecisionName(String mode) {
        return name(EMERGENCY_DECISION, mode);
    }
}
