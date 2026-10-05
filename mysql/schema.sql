-- 水库灌区 · 渠系闸门与灌溉调度管理
-- 库名 reservoir_irri；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 关键：镜像构建期跑 initdb 时 mysql 客户端默认 latin1，会把 UTF-8 中文存成双编码，
--       因此每个 .sql 文件的第一句必须是 SET NAMES utf8mb4。
SET NAMES utf8mb4;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

CREATE DATABASE IF NOT EXISTS reservoir_irri
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE reservoir_irri;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS outage_avoid_item;
DROP TABLE IF EXISTS outage_window;
DROP TABLE IF EXISTS emergency_event;
DROP TABLE IF EXISTS emergency_gate_snapshot;
DROP TABLE IF EXISTS emergency_level_context;
DROP TABLE IF EXISTS emergency_order;
DROP TABLE IF EXISTS water_request;
DROP TABLE IF EXISTS water_user;
DROP TABLE IF EXISTS water_level;
DROP TABLE IF EXISTS allotment;
DROP TABLE IF EXISTS irrigation_plan;
DROP TABLE IF EXISTS gate_operation;
DROP TABLE IF EXISTS gate;
DROP TABLE IF EXISTS canal_segment;
DROP TABLE IF EXISTS canal;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 渠道（干渠 / 支渠 / 斗渠 三级） ----------------
-- canal_grade：TRUNK 干渠 / BRANCH 支渠 / LATERAL 斗渠（纵向水系拓扑图的三条主干）
-- status：ACTIVE 通水 / MAINTAIN 岁修 / DISABLED 停用（停用后其下渠段不允许配水）
CREATE TABLE canal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canal_code VARCHAR(32) NOT NULL UNIQUE,
    canal_name VARCHAR(64),
    canal_grade VARCHAR(24) NOT NULL,
    source_name VARCHAR(64),
    length_km DECIMAL(10,3),
    design_flow DECIMAL(10,2),
    segment_count INT DEFAULT 0,
    manager VARCHAR(32),
    built_year INT,
    status VARCHAR(16),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 渠段（渠道上按里程切分的管理单元） ----------------
-- design_flow 设计流量 > 0、length_km > 0；上下游衔接要求下游起点里程 >= 上游终点里程
-- design_low_level / design_high_level 是水位量测越界判定的上下界
-- status：ACTIVE 通水 / MAINTAIN 岁修 / DISABLED 停用（停用不允许配水）
CREATE TABLE canal_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canal_id BIGINT NOT NULL,
    segment_code VARCHAR(32) NOT NULL UNIQUE,
    segment_name VARCHAR(64),
    sort_no INT,
    start_mileage DECIMAL(10,3),
    end_mileage DECIMAL(10,3),
    length_km DECIMAL(10,3),
    design_flow DECIMAL(10,2),
    design_low_level DECIMAL(8,2),
    design_high_level DECIMAL(8,2),
    lining_type VARCHAR(24),
    upstream_id BIGINT,
    status VARCHAR(16),
    note VARCHAR(128),
    KEY idx_segment_canal (canal_id),
    KEY idx_segment_upstream (upstream_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 闸门 ----------------
-- current_opening 当前开度 0~100；safe_min_opening ~ safe_max_opening 是开度安全区色带
-- status：ACTIVE 运行 / MAINTAIN 检修 / DISABLED 停用（停用禁止操作）
CREATE TABLE gate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canal_segment_id BIGINT NOT NULL,
    gate_code VARCHAR(32) NOT NULL UNIQUE,
    gate_name VARCHAR(64),
    gate_type VARCHAR(24) NOT NULL,
    current_opening DECIMAL(5,2),
    safe_min_opening DECIMAL(5,2),
    safe_max_opening DECIMAL(5,2),
    design_flow DECIMAL(10,2),
    keeper VARCHAR(32),
    last_operate_time DATETIME,
    status VARCHAR(16),
    note VARCHAR(128),
    KEY idx_gate_segment (canal_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 闸门开度操作记录 ----------------
-- adjust_range 调节幅度（个百分点）；> 30 时 status 置 PENDING 走审批
-- status：PENDING 待审批 / APPROVED 已批准 / REJECTED 已驳回 / EXECUTED 已执行
CREATE TABLE gate_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    gate_id BIGINT NOT NULL,
    operation_no VARCHAR(32) NOT NULL UNIQUE,
    before_opening DECIMAL(5,2),
    after_opening DECIMAL(5,2),
    adjust_range DECIMAL(5,2),
    operate_time DATETIME,
    operator VARCHAR(32),
    status VARCHAR(16),
    reason VARCHAR(128),
    approver VARCHAR(32),
    approve_time DATETIME,
    note VARCHAR(128),
    emergency_order_id BIGINT,
    KEY idx_operation_gate (gate_id),
    KEY idx_operation_emergency (emergency_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 灌溉计划 ----------------
-- status：DRAFT 编制 / PENDING 待审批 / APPROVED 已批准 / RUNNING 配水中 / FINISHED 已结束
-- 已批准及之后（APPROVED / RUNNING / FINISHED）不允许再改配水流量与周期
CREATE TABLE irrigation_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(32) NOT NULL UNIQUE,
    plan_name VARCHAR(64),
    canal_segment_id BIGINT NOT NULL,
    start_date DATE,
    end_date DATE,
    plan_days INT,
    plan_flow DECIMAL(10,2),
    plan_volume DECIMAL(12,2),
    crop_type VARCHAR(24),
    irrigated_area_mu DECIMAL(10,2),
    status VARCHAR(16),
    approver VARCHAR(32),
    approve_time DATETIME,
    note VARCHAR(128),
    KEY idx_plan_segment (canal_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 配水记录（灌溉计划在某渠段某天的安排） ----------------
-- 同一渠段同一 allot_date 内各条 flow 合计不得超过该渠段 design_flow
-- status：PLANNED 已排期 / SUPPLYING 配水中 / DONE 已完成 / CANCELED 已取消
CREATE TABLE allotment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    canal_segment_id BIGINT NOT NULL,
    allot_date DATE,
    flow DECIMAL(10,2),
    hours INT,
    volume_wan DECIMAL(12,2),
    status VARCHAR(16),
    note VARCHAR(128),
    KEY idx_allot_plan (plan_id),
    KEY idx_allot_segment (canal_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 渠段检修停水窗口 ----------------
-- 运维登记的渠段停水起止（可跨日）、原因与范围；生效 / 变更时扫描重叠的灌溉计划与配水条标为待避让
-- status：ACTIVE 生效中 / CANCELED 已撤销（撤销只恢复未人工确认的待避让项，已处理结果保留）
CREATE TABLE outage_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_no VARCHAR(32) NOT NULL UNIQUE,
    canal_segment_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    reason VARCHAR(128),
    scope_type VARCHAR(16),
    scope_note VARCHAR(128),
    status VARCHAR(16),
    created_by VARCHAR(32),
    created_at DATETIME,
    updated_at DATETIME,
    canceled_at DATETIME,
    cancel_note VARCHAR(128),
    note VARCHAR(128),
    KEY idx_outage_segment (canal_segment_id),
    KEY idx_outage_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 停水窗口避让项 ----------------
-- 一个窗口 × 一个重叠对象（一条计划或一条配水条）；orig_* 是命中时的周期快照
-- status：PENDING 待避让 / POSTPONED 已顺延 / SPLIT 已拆分 / KEPT 例外保留 / RESTORED 已恢复
-- resolution_note 记录最终去向；KEPT 时为例外依据
CREATE TABLE outage_avoid_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    plan_id BIGINT,
    allotment_id BIGINT,
    canal_segment_id BIGINT NOT NULL,
    orig_start_date DATE,
    orig_end_date DATE,
    status VARCHAR(16) NOT NULL,
    resolution_note VARCHAR(256),
    new_start_date DATE,
    new_end_date DATE,
    split_plan_id BIGINT,
    operator VARCHAR(32),
    resolved_at DATETIME,
    created_at DATETIME,
    KEY idx_avoid_window (window_id),
    KEY idx_avoid_plan (plan_id),
    KEY idx_avoid_allotment (allotment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 水位量测（含两个 @EmbeddableInstantiator 值对象） ----------------
-- window_start_at / window_end_at 属于值对象 MeasureWindow（量测时段），
--   window_duration_minutes 是它派生的持续分钟数，insertable=false/updatable=false，不落库。
-- band_low_level / band_high_level 属于值对象 LevelBand（水位区间），
--   band_span（变幅）与 band_safety_grade（安全等级）由它派生，同样不落库。
-- 下面 seed 里给这三列的值只是初始化快照，读库时一律由 EmbeddableInstantiator 即时重算。
CREATE TABLE water_level (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canal_segment_id BIGINT NOT NULL,
    station_code VARCHAR(32),
    window_start_at DATETIME,
    window_end_at DATETIME,
    window_duration_minutes INT,
    band_low_level DECIMAL(8,2),
    band_high_level DECIMAL(8,2),
    band_span DECIMAL(8,2),
    band_safety_grade VARCHAR(16),
    measured_level DECIMAL(8,2),
    observer VARCHAR(32),
    abnormal TINYINT(1) DEFAULT 0,
    abnormal_reason VARCHAR(128),
    created_at DATETIME,
    note VARCHAR(128),
    KEY idx_level_segment (canal_segment_id),
    KEY idx_level_station (station_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 异常水位险情处置单 ----------------
-- status：PENDING 待接管 / HANDLING 处置中 / REVIEW 待复核 / RESOLVED 已解除 / MANUAL 转人工
-- version 是 JPA 乐观锁，接管、提交意见、复核解除都必须带刷新后的 version，防止两个值班员基于旧页面覆盖。
-- source_window_start_at / source_window_end_at 保留发起险情时的量测时段；
-- 同一渠段内与未结束处置单的量测时段重叠时拒绝创建。
CREATE TABLE emergency_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    water_level_id BIGINT NOT NULL,
    canal_segment_id BIGINT NOT NULL,
    station_code VARCHAR(32),
    source_window_start_at DATETIME,
    source_window_end_at DATETIME,
    source_measured_level DECIMAL(8,2),
    source_low_level DECIMAL(8,2),
    source_high_level DECIMAL(8,2),
    source_abnormal_reason VARCHAR(128),
    source_segment_status VARCHAR(16),
    source_segment_name VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    handler VARCHAR(32),
    taken_over_at DATETIME,
    next_review_at DATETIME,
    latest_gate_operation_id BIGINT,
    latest_gate_operation_no VARCHAR(32),
    decision_mode VARCHAR(16),
    decision_comment VARCHAR(256),
    resolved_at DATETIME,
    resolver VARCHAR(32),
    resolution_note VARCHAR(256),
    manual_reason VARCHAR(256),
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_emergency_segment (canal_segment_id),
    KEY idx_emergency_level (water_level_id),
    KEY idx_emergency_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每次接管或主动刷新时的最新水位上下文；version=0 为发起快照，后续版本用于和当前库值比对。
CREATE TABLE emergency_level_context (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    emergency_order_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    water_level_id BIGINT NOT NULL,
    measured_level DECIMAL(8,2),
    abnormal TINYINT(1),
    abnormal_reason VARCHAR(128),
    segment_status VARCHAR(16),
    segment_name VARCHAR(64),
    design_low_level DECIMAL(8,2),
    design_high_level DECIMAL(8,2),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_level_context (emergency_order_id, version_no),
    KEY idx_lctx_order (emergency_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 同渠段全部关联闸门的快照；后续闸门检修、停用、开度或新增操作都由 latest_version 与现状比对发现。
CREATE TABLE emergency_gate_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    emergency_order_id BIGINT NOT NULL,
    gate_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    gate_code VARCHAR(32),
    gate_name VARCHAR(64),
    gate_type VARCHAR(24),
    current_opening DECIMAL(5,2),
    safe_min_opening DECIMAL(5,2),
    safe_max_opening DECIMAL(5,2),
    status VARCHAR(16),
    keeper VARCHAR(32),
    last_operate_time DATETIME,
    latest_operation_id BIGINT,
    latest_operation_no VARCHAR(32),
    latest_operation_status VARCHAR(16),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_gate_snapshot (emergency_order_id, gate_id, version_no),
    KEY idx_gsnap_order (emergency_order_id),
    KEY idx_gsnap_gate (gate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 完整处置时间线：谁在什么状态下接管、继续观测、提出/撤回调整、刷新、转人工或解除。
CREATE TABLE emergency_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    emergency_order_id BIGINT NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator VARCHAR(32),
    event_time DATETIME NOT NULL,
    detail VARCHAR(512),
    gate_id BIGINT,
    gate_operation_id BIGINT,
    KEY idx_eevent_order_time (emergency_order_id, event_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 用水户 ----------------
-- irrigated_area_mu 灌溉面积必须 > 0
-- status：ACTIVE 在册 / ARREAR 欠费停供 / DISABLED 注销
CREATE TABLE water_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_code VARCHAR(32) NOT NULL UNIQUE,
    user_name VARCHAR(32),
    village VARCHAR(64),
    phone VARCHAR(20),
    irrigated_area_mu DECIMAL(10,2),
    crop_type VARCHAR(24),
    canal_segment_id BIGINT,
    status VARCHAR(16),
    note VARCHAR(128),
    KEY idx_user_segment (canal_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 用水申请 ----------------
-- request_volume 申请水量（万m³），不得超过该渠段该日剩余可配水量
-- status：PENDING 待审 / APPROVED 已批 / SUPPLIED 已供水 / REJECTED 已驳回（后两者为终态）
CREATE TABLE water_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no VARCHAR(32) NOT NULL UNIQUE,
    water_user_id BIGINT NOT NULL,
    canal_segment_id BIGINT NOT NULL,
    allot_date DATE,
    request_volume DECIMAL(12,2),
    crop_type VARCHAR(24),
    apply_area_mu DECIMAL(10,2),
    urgency VARCHAR(16),
    status VARCHAR(16),
    reject_reason VARCHAR(128),
    approver VARCHAR(32),
    apply_time DATETIME,
    approve_time DATETIME,
    supply_time DATETIME,
    note VARCHAR(128),
    KEY idx_request_user (water_user_id),
    KEY idx_request_segment (canal_segment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据（MySQL 容器是 UTC，所以时间一律用 UTC_TIMESTAMP() + INTERVAL 8 HOUR）
-- =====================================================================

-- ---------------- 渠道 ----------------
INSERT INTO canal (canal_code, canal_name, canal_grade, source_name, length_km, design_flow, segment_count, manager, built_year, status, note) VALUES
('GQ-TR-01', '东干渠',   'TRUNK',  '青龙水库主坝放水洞',   18.600, 12.50, 3, '王建国', 1978, 'ACTIVE',   '灌区主输水通道，承担东侧 3.6 万亩'),
('GQ-TR-02', '西干渠',   'TRUNK',  '青龙水库副坝涵管',     15.200,  9.80, 3, '李根生', 1982, 'ACTIVE',   '西侧输水主干，尾部段已停用待改造'),
('GQ-BR-01', '东一支渠', 'BRANCH', '东干渠 3+200 分水口',  7.400,  4.20, 2, '赵德海', 1995, 'ACTIVE',   '覆盖东河村、河东村'),
('GQ-BR-02', '东二支渠', 'BRANCH', '东干渠 7+800 分水口',  6.100,  3.60, 0, '孙立军', 1998, 'MAINTAIN', '岁修中，衬砌翻新尚未完成'),
('GQ-BR-03', '西一支渠', 'BRANCH', '西干渠 2+600 分水口',  8.300,  5.00, 1, '周文华', 1996, 'ACTIVE',   '河西村、南洼村共用'),
('GQ-LT-01', '东一斗渠', 'LATERAL','东一支渠 1+500 斗口',  2.400,  1.20, 1, '陈守田', 2005, 'ACTIVE',   '斗渠末级，蔬菜滴灌专用'),
('GQ-LT-02', '东二斗渠', 'LATERAL','东一支渠 3+800 斗口',  1.900,  0.90, 0, '吴春来', 2008, 'DISABLED', '已废弃，斗口闸封停');

-- ---------------- 渠段 ----------------
INSERT INTO canal_segment (canal_id, segment_code, segment_name, sort_no, start_mileage, end_mileage, length_km, design_flow, design_low_level, design_high_level, lining_type, upstream_id, status, note) VALUES
(1, 'GQ-TR-01-S1', '东干渠进口段',   1,  0.000,  3.200, 3.200, 12.50, 42.50, 44.80, 'CONCRETE', NULL, 'ACTIVE',   '渠首段，含进水闸与量水堰'),
(1, 'GQ-TR-01-S2', '东干渠中游段',   2,  3.200,  7.800, 4.600, 11.20, 41.80, 44.20, 'CONCRETE', 1,    'ACTIVE',   '中游段，东一支渠分水口位于 3+200'),
(1, 'GQ-TR-01-S3', '东干渠下游段',   3,  7.800, 12.400, 4.600,  9.60, 40.90, 43.50, 'MASONRY',  2,    'ACTIVE',   '下游段，浆砌石衬砌老化'),
(2, 'GQ-TR-02-S1', '西干渠进口段',   1,  0.000,  2.600, 2.600,  9.80, 43.10, 45.60, 'CONCRETE', NULL, 'ACTIVE',   '渠首段，取水口设拦污栅'),
(2, 'GQ-TR-02-S2', '西干渠中游段',   2,  2.600,  6.900, 4.300,  8.40, 42.30, 44.90, 'EARTH',    4,    'MAINTAIN', '土渠段，夏季冲刷严重，岁修中'),
(2, 'GQ-TR-02-S3', '西干渠尾段',     3,  6.900, 10.500, 3.600,  6.50, 41.60, 43.80, 'EARTH',    5,    'DISABLED', '尾段已停用，列入 2027 改造计划'),
(3, 'GQ-BR-01-S1', '东一支渠上段',   1,  0.000,  1.500, 1.500,  4.20, 41.20, 43.40, 'CONCRETE', NULL, 'ACTIVE',   '上段，含分水闸'),
(3, 'GQ-BR-01-S2', '东一支渠下段',   2,  1.500,  4.200, 2.700,  3.60, 40.60, 42.80, 'FILM',     7,    'ACTIVE',   '下段，膜料防渗试点'),
(5, 'GQ-BR-03-S1', '西一支渠全段',   1,  0.000,  5.600, 5.600,  5.00, 42.00, 44.50, 'CONCRETE', NULL, 'ACTIVE',   '全段混凝土衬砌'),
(6, 'GQ-LT-01-S1', '东一斗渠全段',   1,  0.000,  2.400, 2.400,  1.20, 40.80, 42.60, 'EARTH',    NULL, 'ACTIVE',   '斗渠土渠，仅供蔬菜滴灌');

-- ---------------- 闸门 ----------------
INSERT INTO gate (canal_segment_id, gate_code, gate_name, gate_type, current_opening, safe_min_opening, safe_max_opening, design_flow, keeper, last_operate_time, status, note) VALUES
(1,  'GT-TR-01', '东干渠首进水闸',  'INTAKE',    62.00, 15.00, 85.00, 12.50, '张国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, 'ACTIVE',   '渠首控制闸，配电动启闭机'),
(2,  'GT-TR-02', '东干渠节制闸',    'CONTROL',   45.00, 10.00, 80.00, 11.20, '李海涛', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, 'ACTIVE',   '中游节制，调节东一支渠水位'),
(3,  'GT-TR-03', '东干渠下游分水闸','DIVERSION', 30.00, 10.00, 75.00,  9.60, '王连喜', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY, 'ACTIVE',   '下游分水，向斗渠配水'),
(4,  'GT-TR-04', '西干渠首进水闸',  'INTAKE',    62.00, 15.00, 85.00,  9.80, '刘长顺', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, 'ACTIVE',   '渠首控制闸'),
(5,  'GT-TR-05', '西干渠节制闸',    'CONTROL',   20.00, 10.00, 80.00,  8.40, '马振山', NULL,                                              'MAINTAIN', '随渠段岁修一并检修'),
(7,  'GT-BR-01', '东一支渠分水闸',  'DIVERSION', 48.00, 12.00, 78.00,  4.20, '赵德海', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, 'ACTIVE',   '支渠分水口'),
(8,  'GT-BR-02', '东一支渠节制闸',  'CONTROL',   35.00, 10.00, 75.00,  3.60, '孙立军', NULL,                                              'ACTIVE',   '启闭机手轮有轻微卡涩'),
(10, 'GT-LT-01', '东一斗渠斗口闸',  'DIVERSION',  0.00,  5.00, 70.00,  1.20, '陈守田', NULL,                                              'DISABLED', '斗口闸封停，停用中');

-- ---------------- 闸门开度操作记录 ----------------
INSERT INTO gate_operation (gate_id, operation_no, before_opening, after_opening, adjust_range, operate_time, operator, status, reason, approver, approve_time, note) VALUES
(1, 'OP-2026-0001', 40.00, 62.00, 22.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY, '张国栋', 'EXECUTED', '夏灌开局加大引水',   '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY, ''),
(1, 'OP-2026-0002', 62.00, 55.00,  7.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, '张国栋', 'EXECUTED', '库水位回落小幅收闸', '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, ''),
(2, 'OP-2026-0003', 20.00, 45.00, 25.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, '李海涛', 'EXECUTED', '抬高中游水位保泡田', '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, ''),
(3, 'OP-2026-0004', 10.00, 30.00, 20.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY, '王连喜', 'EXECUTED', '下游斗渠开始配水',   '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY, ''),
(2, 'OP-2026-0005', 45.00, 78.00, 33.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, '李海涛', 'PENDING',  '集中泡田需大流量',   NULL,       NULL, '调节幅度 33 个百分点，超过 30 需调度审批'),
(4, 'OP-2026-0006', 30.00, 55.00, 25.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, '刘长顺', 'EXECUTED', '西干渠开灌',         '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, ''),
(3, 'OP-2026-0007', 30.00, 65.00, 35.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '王连喜', 'PENDING',  '下游干旱应急提闸',   NULL,       NULL, '调节幅度 35 个百分点，等待灌区调度审批'),
(6, 'OP-2026-0008', 20.00, 48.00, 28.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '赵德海', 'EXECUTED', '东一支渠水稻泡田',   '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, ''),
(7, 'OP-2026-0009', 70.00, 35.00, 35.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '孙立军', 'REJECTED', '退水减量',           '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '退水理由不充分，且下游尚有 3 户待供水，暂缓'),
(4, 'OP-2026-0010', 55.00, 62.00,  7.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '刘长顺', 'EXECUTED', '补抬西干渠水位',     '自动放行', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '');

-- ---------------- 灌溉计划 ----------------
INSERT INTO irrigation_plan (plan_code, plan_name, canal_segment_id, start_date, end_date, plan_days, plan_flow, plan_volume, crop_type, irrigated_area_mu, status, approver, approve_time, note) VALUES
('IP-2026-S-01', '东干渠夏灌第一轮', 1,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY), DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL  1 DAY), 10, 6.50, 561.60, 'RICE',      4200.00, 'FINISHED', '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, '第一轮泡田已完成'),
('IP-2026-S-02', '东干渠夏灌第二轮', 1,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR),                   DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  9 DAY), 10, 7.20, 622.08, 'RICE',      4600.00, 'APPROVED', '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,  '第二轮已批准，正在排配水'),
('IP-2026-S-03', '东一支渠水稻泡田', 7,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY),  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  7 DAY),  7, 2.80, 169.34, 'RICE',      1800.00, 'PENDING',  NULL,       NULL, '待灌区调度审批'),
('IP-2026-A-01', '西干渠秋灌预案',   4,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 DAY),  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 12 DAY), 10, 5.60, 483.84, 'WHEAT',     3200.00, 'DRAFT',    NULL,       NULL, '冬小麦播前灌预案'),
('IP-2026-S-04', '西一支渠玉米补水', 9,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  3 DAY),  6, 3.20, 165.89, 'MAIZE',     2100.00, 'RUNNING',  '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,  '抽雄期补水，配水中'),
('IP-2026-V-01', '东一斗渠蔬菜滴灌', 10, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL  2 DAY),  8, 0.80,  55.30, 'VEGETABLE',  620.00, 'APPROVED', '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY,  '滴灌需水量小，短时段多轮');

-- ---------------- 配水记录 ----------------
INSERT INTO allotment (plan_id, canal_segment_id, allot_date, flow, hours, volume_wan, status, note) VALUES
(1,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),  5.50, 24, 47.52, 'DONE',     '第一轮第 8 天'),
(1,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),  5.00, 24, 43.20, 'DONE',     '第一轮第 9 天'),
(1,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),  4.80, 18, 31.10, 'DONE',     '第一轮收尾'),
(2,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR),                   6.00, 24, 51.84, 'DONE',     '第二轮首日'),
(2,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY),  6.50, 24, 56.16, 'PLANNED',  '第二轮第 2 天'),
(2,  1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY),  7.00, 20, 50.40, 'PLANNED',  '第二轮第 3 天'),
(3,  7, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY),  2.40, 24, 20.74, 'PLANNED',  '东一支渠泡田首日'),
(3,  7, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY),  2.60, 24, 22.46, 'PLANNED',  '东一支渠泡田次日'),
(5,  9, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),  3.00, 24, 25.92, 'DONE',     '西一支渠补水'),
(5,  9, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY),  3.40, 24, 29.38, 'SUPPLYING','西一支渠补水中'),
(6, 10, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY),  0.90, 12,  3.89, 'PLANNED',  '斗渠滴灌短时段'),
(6, 10, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY),  1.00, 10,  3.60, 'PLANNED',  '斗渠滴灌短时段');

-- ---------------- 水位量测 ----------------
-- window_duration_minutes / band_span / band_safety_grade 三列是派生列（值对象读库时重算），
-- 这里给的值只是初始化快照；实测值越出渠段设计水位区间的记录会被服务层标记 abnormal = 1。
INSERT INTO water_level (canal_segment_id, station_code, window_start_at, window_end_at, window_duration_minutes, band_low_level, band_high_level, band_span, band_safety_grade, measured_level, observer, abnormal, abnormal_reason, created_at, note) VALUES
(1, 'SW-TR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 0 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 240 MINUTE, 240, 43.10, 43.35, 0.25, 'NORMAL', 43.22, '王建国', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, '上午量测，水位平稳'),
(1, 'SW-TR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 360 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY + INTERVAL 600 MINUTE, 240, 43.05, 43.40, 0.35, 'NORMAL', 43.18, '王建国', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, '下午量测'),
(1, 'SW-TR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 0 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 210 MINUTE, 210, 43.05, 43.20, 0.15, 'STEADY', 43.12, '王建国', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '变幅 0.15m，判为平稳'),
(1, 'SW-TR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 360 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 540 MINUTE, 180, 42.60, 43.05, 0.45, 'NORMAL', 42.80, '王建国', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '接近设计最低水位'),
(1, 'SW-TR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 0 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 120 MINUTE, 120, 42.10, 42.55, 0.45, 'NORMAL', 42.30, '王建国', 1, '低于设计最低水位 0.20 m（实测 42.30 m）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '越界，已标记异常'),
(2, 'SW-TR-02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 60 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY + INTERVAL 300 MINUTE, 240, 42.40, 43.10, 0.70, 'SURGE',  42.75, '李根生', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, '提闸后变幅 0.70m，判为骤变'),
(2, 'SW-TR-02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 60 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 420 MINUTE, 360, 42.55, 43.05, 0.50, 'NORMAL', 42.80, '李根生', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '恢复正常'),
(3, 'SW-TR-03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 120 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 360 MINUTE, 240, 41.20, 41.75, 0.55, 'NORMAL', 41.45, '王连喜', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '下游段量测'),
(4, 'SW-TR-04', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 90 MINUTE,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 240 MINUTE, 150, 43.80, 44.30, 0.50, 'NORMAL', 44.05, '刘长顺', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '西干渠渠首'),
(7, 'SW-BR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 30 MINUTE,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 270 MINUTE, 240, 41.80, 42.15, 0.35, 'NORMAL', 41.95, '赵德海', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '支渠上段'),
(7, 'SW-BR-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 300 MINUTE, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 540 MINUTE, 240, 42.05, 42.60, 0.55, 'NORMAL', 42.30, '赵德海', 0, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '支渠上段下午'),
(10,'SW-LT-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 0 MINUTE,   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY + INTERVAL 150 MINUTE, 150, 42.70, 43.00, 0.30, 'NORMAL', 42.90, '陈守田', 1, '高于设计最高水位 0.30 m（实测 42.90 m）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '斗渠超设计水位，已标记异常');

-- ---------------- 用水户 ----------------
INSERT INTO water_user (user_code, user_name, village, phone, irrigated_area_mu, crop_type, canal_segment_id, status, note) VALUES
('WU-2026-001', '张广田', '东河村三组', '13805310001', 46.50, 'RICE',      1,  'ACTIVE',   '种植大户，连续 3 年无欠费'),
('WU-2026-002', '李秀莲', '东河村一组', '13805310002', 32.80, 'RICE',      1,  'ACTIVE',   '稻田连片，渠道末端'),
('WU-2026-003', '王德胜', '东河村五组', '13805310003', 58.20, 'RICE',      1,  'ARREAR',   '2025 年度水费未结清，已停供'),
('WU-2026-004', '赵长河', '河西村二组', '13805310004', 41.00, 'WHEAT',     4,  'ACTIVE',   '冬小麦种植户'),
('WU-2026-005', '孙茂才', '河西村四组', '13805310005', 27.60, 'WHEAT',     4,  'ACTIVE',   '兼种油菜'),
('WU-2026-006', '周桂芳', '河东村一组', '13805310006', 63.40, 'MAIZE',     7,  'ACTIVE',   '玉米制种田'),
('WU-2026-007', '吴学文', '河东村三组', '13805310007', 19.80, 'MAIZE',     7,  'ACTIVE',   '地块分散，需多次轮灌'),
('WU-2026-008', '郑守业', '河东村六组', '13805310008', 88.50, 'COTTON',    9,  'ACTIVE',   '棉花种植面积最大'),
('WU-2026-009', '冯春梅', '南洼村二组', '13805310009', 12.40, 'VEGETABLE', 10, 'ACTIVE',   '大棚蔬菜滴灌'),
('WU-2026-010', '陈家富', '南洼村五组', '13805310010', 55.70, 'RICE',      8,  'DISABLED', '整户外迁，已注销');

-- ---------------- 用水申请 ----------------
INSERT INTO water_request (request_no, water_user_id, canal_segment_id, allot_date, request_volume, crop_type, apply_area_mu, urgency, status, reject_reason, approver, apply_time, approve_time, supply_time, note) VALUES
('WR-2026-0101', 1,  1,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 3.20, 'RICE',      46.50, 'NORMAL', 'PENDING',  NULL,                     NULL,       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL, NULL, '第二轮泡田用水'),
('WR-2026-0102', 2,  1,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 2.40, 'RICE',      32.80, 'NORMAL', 'APPROVED', NULL,                     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '渠道末端，需压时供水'),
('WR-2026-0103', 3,  1,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 1.80, 'RICE',      58.20, 'URGENT', 'REJECTED', '该户水费未结清，须先缴清欠费再受理', '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '抗旱应急申请'),
('WR-2026-0104', 4,  4,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 2.60, 'WHEAT',     41.00, 'NORMAL', 'PENDING',  NULL,                     NULL,       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, NULL, '播前灌用水'),
('WR-2026-0105', 5,  4,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY), 1.90, 'WHEAT',     27.60, 'NORMAL', 'APPROVED', NULL,                     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR, NULL, ''),
('WR-2026-0106', 6,  7,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 1.50, 'MAIZE',     63.40, 'NORMAL', 'SUPPLIED', NULL,                     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR, '已供水'),
('WR-2026-0107', 7,  7,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY), 1.20, 'MAIZE',     19.80, 'URGENT', 'PENDING',  NULL,                     NULL,       UTC_TIMESTAMP() + INTERVAL 8 HOUR, NULL, NULL, '地块分散需二次轮灌'),
('WR-2026-0108', 8,  9,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY), 3.60, 'COTTON',    88.50, 'NORMAL', 'SUPPLIED', NULL,                     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR, '花铃期补水'),
('WR-2026-0109', 9,  10, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), 0.60, 'VEGETABLE', 12.40, 'NORMAL', 'APPROVED', NULL,                     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR, NULL, '滴灌用水，量小次数多'),
('WR-2026-0110', 10, 8,  DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY), 2.20, 'RICE',      55.70, 'NORMAL', 'REJECTED', '该户已注销，不再受理用水申请',     '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL, '');

-- ---------------- 渠段检修停水窗口 ----------------
-- MW-2026-0001：东干渠进口段跨 3 天全段停水（生效中），命中计划 IP-2026-S-02 与其 +2 天配水条
-- MW-2026-0002：西一支渠局部停水（已撤销），演示「撤销只恢复未确认项，例外保留的历史不覆盖」
INSERT INTO outage_window (window_no, canal_segment_id, start_at, end_at, reason, scope_type, scope_note, status, created_by, created_at, updated_at, canceled_at, cancel_note, note) VALUES
('MW-2026-0001', 1,
 DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY) + INTERVAL 6 HOUR,
 DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 4 DAY) + INTERVAL 22 HOUR,
 '进口段量水堰拆除重建，需断流施工', 'FULL', '桩号 0+800~1+600 全断面', 'ACTIVE',
 '运维-赵工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR,
 NULL, NULL, '预计 3 天，遇雨顺延另行登记'),
('MW-2026-0002', 9,
 DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY) + INTERVAL 8 HOUR,
 DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY) + INTERVAL 18 HOUR,
 '西一支渠节制闸启闭机更换', 'PARTIAL', '可维持 1.0 m³/s 以下低压过水', 'CANCELED',
 '运维-赵工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '与供电所停电计划冲突，取消本次检修，另行登记', NULL);

-- ---------------- 停水窗口避让项 ----------------
-- MW-2026-0001 命中：计划 IP-2026-S-02（今天 ~ +9 天）与其 +2 天的配水条，均待避让
-- MW-2026-0002 已撤销：计划 IP-2026-S-04 例外保留（历史不覆盖），SUPPLYING 配水条自动恢复
INSERT INTO outage_avoid_item (window_id, target_type, plan_id, allotment_id, canal_segment_id, orig_start_date, orig_end_date, status, resolution_note, new_start_date, new_end_date, split_plan_id, operator, resolved_at, created_at) VALUES
(1, 'PLAN', 2, NULL, 1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR), DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 9 DAY), 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(1, 'ALLOT', 2, 6, 1, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 2 DAY), NULL, 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(2, 'PLAN', 5, NULL, 9, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY), DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 DAY), 'KEPT', '例外依据：抽雄期补水临界，经灌区调度批准保持 1.0 m³/s 以下低压过水，检修与供水并行', NULL, NULL, NULL, '灌区调度', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 'ALLOT', 5, 10, 9, DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY), NULL, 'RESTORED', '窗口撤销，自动恢复', NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY);
