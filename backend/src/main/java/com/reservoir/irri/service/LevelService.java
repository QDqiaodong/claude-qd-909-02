package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.EmergencyOrder;
import com.reservoir.irri.domain.WaterLevel;
import com.reservoir.irri.domain.vo.LevelBand;
import com.reservoir.irri.domain.vo.MeasureWindow;
import com.reservoir.irri.dto.Views.DerivedColumnVO;
import com.reservoir.irri.dto.Views.LevelPointVO;
import com.reservoir.irri.dto.Views.LevelSeriesVO;
import com.reservoir.irri.dto.Views.LevelVO;
import com.reservoir.irri.dto.WaterLevelRequest;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.EmergencyOrderRepository;
import com.reservoir.irri.repository.WaterLevelRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块四 · 水位量测（本仓的 Hibernate @EmbeddableInstantiator 主战场）。
 *
 * <p>一条量测记录里嵌了两个 @Embeddable 值对象：
 * <ul>
 *   <li>{@link MeasureWindow} 量测时段：起始时刻 + 结束时刻 + 【派生】持续分钟数</li>
 *   <li>{@link LevelBand} 时段水位区间：最低水位 + 最高水位 + 【派生】变幅 + 【派生】安全等级</li>
 * </ul>
 * 两者都通过 {@code @EmbeddableInstantiator} 指定了自定义实例化器，读库时由 Hibernate
 * 把列值交给它，由它 new 出对象并算出派生属性；派生属性标注
 * insertable=false / updatable=false，Hibernate 永不写库。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>量测时段值对象的起止时刻都必须有值</li>
 *   <li>结束时刻不得早于开始时刻：值对象派生出的持续分钟数为负时，service 二次校验拦下</li>
 *   <li>最低水位不得高于最高水位；水位值必须在渠段设计水位范围内，越界标记异常</li>
 *   <li>同一测站同一时段不允许重复登记（区间重叠直接拒绝）</li>
 * </ol>
 */
@Service
public class LevelService {

    private final WaterLevelRepository levelRepository;
    private final CanalSegmentRepository segmentRepository;
    private final EmergencyOrderRepository emergencyOrderRepository;
    private final CanalService canalService;

    public LevelService(WaterLevelRepository levelRepository, CanalSegmentRepository segmentRepository,
                        EmergencyOrderRepository emergencyOrderRepository, CanalService canalService) {
        this.levelRepository = levelRepository;
        this.segmentRepository = segmentRepository;
        this.emergencyOrderRepository = emergencyOrderRepository;
        this.canalService = canalService;
    }

    // ---------------- 查询 ----------------

    public List<WaterLevel> list(Long segmentId) {
        if (segmentId == null) {
            return levelRepository.findAllDesc();
        }
        return levelRepository.findBySegmentDesc(segmentId);
    }

    /** 量测列表（带值对象派生列） */
    public List<LevelVO> listVO(Long segmentId) {
        List<WaterLevel> list = list(segmentId);
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        Map<Long, EmergencyOrder> openOrders = emergencyOrderRepository
                .findByStatusIn(List.of("PENDING", "HANDLING", "REVIEW")).stream()
                .collect(Collectors.toMap(EmergencyOrder::getWaterLevelId, o -> o, (a, b) -> a));
        return list.stream()
                .map(l -> {
                    LevelVO vo = toLevelVO(l, segments.get(l.getCanalSegmentId()));
                    applyEmergency(vo, openOrders.get(l.getId()));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    /** 折线图：按测站分组，每组一串点位 */
    public List<LevelSeriesVO> series(Long segmentId) {
        List<WaterLevel> list = segmentId == null
                ? levelRepository.findAllDesc()
                : levelRepository.findBySegmentDesc(segmentId);
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        Map<Long, EmergencyOrder> openOrders = emergencyOrderRepository
                .findByStatusIn(List.of("PENDING", "HANDLING", "REVIEW")).stream()
                .collect(Collectors.toMap(EmergencyOrder::getWaterLevelId, o -> o, (a, b) -> a));
        // 按测站分组后按时间正序
        Map<String, List<WaterLevel>> byStation = list.stream()
                .collect(Collectors.groupingBy(l -> l.getStationCode() == null ? "未编号" : l.getStationCode()));
        List<LevelSeriesVO> result = new ArrayList<>();
        for (Map.Entry<String, List<WaterLevel>> entry : byStation.entrySet()) {
            List<WaterLevel> rows = entry.getValue().stream()
                    .sorted((a, b) -> {
                        LocalDateTime at = a.getWindow() == null ? null : a.getWindow().getStartAt();
                        LocalDateTime bt = b.getWindow() == null ? null : b.getWindow().getStartAt();
                        if (at == null) {
                            return 1;
                        }
                        if (bt == null) {
                            return -1;
                        }
                        return at.compareTo(bt);
                    })
                    .collect(Collectors.toList());
            WaterLevel first = rows.get(0);
            CanalSegment segment = segments.get(first.getCanalSegmentId());
            LevelSeriesVO vo = new LevelSeriesVO();
            vo.setStationCode(entry.getKey());
            vo.setCanalSegmentId(first.getCanalSegmentId());
            vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
            vo.setSegmentName(segment == null ? null : segment.getSegmentName());
            vo.setDesignLowLevel(segment == null ? null : segment.getDesignLowLevel());
            vo.setDesignHighLevel(segment == null ? null : segment.getDesignHighLevel());
            int abnormal = 0;
            for (WaterLevel row : rows) {
                LevelPointVO point = new LevelPointVO();
                point.setId(row.getId());
                point.setLabel(row.getWindow() == null || row.getWindow().getStartAt() == null
                        ? "#" + row.getId()
                        : row.getWindow().getStartAt().toString().replace('T', ' ').substring(5, 16));
                point.setValue(row.getMeasuredLevel());
                point.setLowLevel(row.getBand() == null ? null : row.getBand().getLowLevel());
                point.setHighLevel(row.getBand() == null ? null : row.getBand().getHighLevel());
                // 【派生】由 MeasureWindow 值对象算出
                point.setDurationMinutes(row.getDurationMinutes());
                // 【派生】由 LevelBand 值对象算出
                point.setBandSpan(row.getBandSpan());
                point.setSafetyGrade(row.getSafetyGrade());
                point.setAbnormal(row.getAbnormal());
                point.setAbnormalReason(row.getAbnormalReason());
                EmergencyOrder open = openOrders.get(row.getId());
                point.setEmergencyOpen(Boolean.valueOf(open != null));
                if (open != null) {
                    point.setEmergencyOrderId(open.getId());
                }
                vo.getPoints().add(point);
                if (row.isAbnormal()) {
                    abnormal++;
                }
            }
            vo.setAbnormalCount(abnormal);
            result.add(vo);
        }
        return result;
    }

    /** 派生列清单：前端把这几列显眼标注为「派生」 */
    public List<DerivedColumnVO> derivedColumns() {
        List<DerivedColumnVO> list = new ArrayList<>();
        list.add(new DerivedColumnVO("durationMinutes", "持续分钟数",
                "MeasureWindow（量测时段）", "MeasureWindowInstantiator"));
        list.add(new DerivedColumnVO("bandSpan", "水位变幅（m）",
                "LevelBand（水位区间）", "LevelBandInstantiator"));
        list.add(new DerivedColumnVO("safetyGrade", "变幅安全等级",
                "LevelBand（水位区间）", "LevelBandInstantiator"));
        return list;
    }

    public WaterLevel get(Long id) {
        return levelRepository.findById(id).orElseThrow(() -> new BizException("水位量测记录不存在：" + id));
    }

    // ---------------- 新增 / 修改 ----------------

    @Transactional
    public WaterLevel create(WaterLevelRequest request) {
        if (request.getCanalSegmentId() == null) {
            throw new BizException("请选择量测所属渠段");
        }
        CanalSegment segment = canalService.getSegment(request.getCanalSegmentId());
        // 规则 1：量测时段值对象的起止时刻都必须有值
        if (request.getStartAt() == null || request.getEndAt() == null) {
            throw new BizException("量测时段必须有起始时刻与结束时刻");
        }
        // 规则 2：结束时刻不得早于开始时刻（值对象派生出的时长为负时拦下）
        Integer minutes = MeasureWindow.deriveDurationMinutes(request.getStartAt(), request.getEndAt());
        if (minutes == null || minutes < 0) {
            throw new BizException("量测结束时刻不得早于开始时刻");
        }
        if (minutes == 0) {
            throw new BizException("量测时段长度不能为 0 分钟");
        }
        if (minutes > Dict.MAX_WINDOW_MINUTES) {
            throw new BizException("单次量测时段不得超过 " + Dict.MAX_WINDOW_MINUTES + " 分钟，当前 " + minutes + " 分钟");
        }
        if (request.getLowLevel() == null || request.getHighLevel() == null) {
            throw new BizException("时段最低水位与最高水位都必须有值");
        }
        // 规则 3a：最低水位不得高于最高水位
        if (request.getLowLevel().compareTo(request.getHighLevel()) > 0) {
            throw new BizException("时段最低水位不得高于最高水位");
        }
        if (request.getMeasuredLevel() == null) {
            throw new BizException("时段平均水位不能为空");
        }
        if (!StringUtils.hasText(request.getStationCode())) {
            throw new BizException("测站编号不能为空");
        }
        // 规则 4：同一测站同一时段不允许重叠登记
        if (levelRepository.countOverlap(request.getStationCode(), -1L, request.getStartAt(), request.getEndAt()) > 0) {
            throw new BizException("测站 " + request.getStationCode() + " 在 "
                    + CanalService.str(request.getStartAt()) + " ~ " + CanalService.str(request.getEndAt())
                    + " 时段内已有量测记录，不能重复登记");
        }

        WaterLevel level = new WaterLevel();
        level.setCanalSegmentId(segment.getId());
        level.setStationCode(request.getStationCode());
        // 值对象：构造即派生，instantiator 读库时走的是同一套派生逻辑
        level.setWindow(MeasureWindow.of(request.getStartAt(), request.getEndAt()));
        level.setBand(LevelBand.of(request.getLowLevel(), request.getHighLevel()));
        level.setMeasuredLevel(request.getMeasuredLevel());
        level.setObserver(request.getObserver() == null ? "自动测站" : request.getObserver());
        level.setCreatedAt(LocalDateTime.now());
        level.setNote(request.getNote());
        // 规则 3b：水位必须在渠段设计水位范围内，越界标记异常
        markAbnormal(level, segment);
        return levelRepository.save(level);
    }

    @Transactional
    public WaterLevel update(Long id, WaterLevelRequest request) {
        WaterLevel level = get(id);
        CanalSegment segment = segmentRepository.findById(level.getCanalSegmentId()).orElse(null);
        if (request.getCanalSegmentId() != null) {
            segment = canalService.getSegment(request.getCanalSegmentId());
            level.setCanalSegmentId(segment.getId());
        }
        LocalDateTime startAt = request.getStartAt() != null ? request.getStartAt() : level.getStartAt();
        LocalDateTime endAt = request.getEndAt() != null ? request.getEndAt() : level.getEndAt();
        if (startAt == null || endAt == null) {
            throw new BizException("量测时段必须有起始时刻与结束时刻");
        }
        Integer minutes = MeasureWindow.deriveDurationMinutes(startAt, endAt);
        if (minutes == null || minutes < 0) {
            throw new BizException("量测结束时刻不得早于开始时刻");
        }
        if (minutes > Dict.MAX_WINDOW_MINUTES) {
            throw new BizException("单次量测时段不得超过 " + Dict.MAX_WINDOW_MINUTES + " 分钟，当前 " + minutes + " 分钟");
        }
        BigDecimal low = request.getLowLevel() != null
                ? request.getLowLevel() : (level.getBand() == null ? null : level.getBand().getLowLevel());
        BigDecimal high = request.getHighLevel() != null
                ? request.getHighLevel() : (level.getBand() == null ? null : level.getBand().getHighLevel());
        if (low == null || high == null) {
            throw new BizException("时段最低水位与最高水位都必须有值");
        }
        if (low.compareTo(high) > 0) {
            throw new BizException("时段最低水位不得高于最高水位");
        }
        if (levelRepository.countOverlap(level.getStationCode(), level.getId(), startAt, endAt) > 0) {
            throw new BizException("测站 " + level.getStationCode() + " 在该时段内已有其它量测记录，不能重复登记");
        }

        level.setWindow(MeasureWindow.of(startAt, endAt));
        level.setBand(LevelBand.of(low, high));
        CanalService.ifNotNull(request.getStationCode(), level::setStationCode);
        CanalService.ifNotNull(request.getMeasuredLevel(), level::setMeasuredLevel);
        CanalService.ifNotNull(request.getObserver(), level::setObserver);
        CanalService.ifNotNull(request.getNote(), level::setNote);
        markAbnormal(level, segment);
        return levelRepository.save(level);
    }

    /** 重新派生并重算异常标记（调值对象 refresh 后再落库） */
    @Transactional
    public WaterLevel refresh(Long id) {
        WaterLevel level = get(id);
        if (level.getWindow() != null) {
            level.getWindow().refresh();
        }
        if (level.getBand() != null) {
            level.getBand().refresh();
        }
        CanalSegment segment = segmentRepository.findById(level.getCanalSegmentId()).orElse(null);
        markAbnormal(level, segment);
        return levelRepository.save(level);
    }

    @Transactional
    public void remove(Long id) {
        levelRepository.delete(get(id));
    }

    /**
     * 规则 3b：水位值必须在渠段设计水位范围内，越界要标记异常（不是直接拒绝）。
     * 越界时把 abnormal 置 1 并写清原因，前端折线图上该点标红。
     */
    private void markAbnormal(WaterLevel level, CanalSegment segment) {
        BigDecimal value = level.getMeasuredLevel();
        if (segment == null || value == null
                || segment.getDesignLowLevel() == null || segment.getDesignHighLevel() == null) {
            level.setAbnormal(0);
            level.setAbnormalReason(null);
            return;
        }
        if (value.compareTo(segment.getDesignLowLevel()) < 0) {
            level.setAbnormal(1);
            level.setAbnormalReason("低于设计最低水位 "
                    + segment.getDesignLowLevel().subtract(value).setScale(2, RoundingMode.HALF_UP)
                    + " m（实测 " + value + " m）");
            return;
        }
        if (value.compareTo(segment.getDesignHighLevel()) > 0) {
            level.setAbnormal(1);
            level.setAbnormalReason("高于设计最高水位 "
                    + value.subtract(segment.getDesignHighLevel()).setScale(2, RoundingMode.HALF_UP)
                    + " m（实测 " + value + " m）");
            return;
        }
        level.setAbnormal(0);
        level.setAbnormalReason(null);
    }

    // ---------------- VO ----------------

    public static LevelVO toLevelVO(WaterLevel level, CanalSegment segment) {
        LevelVO vo = new LevelVO();
        vo.setId(level.getId());
        vo.setCanalSegmentId(level.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStationCode(level.getStationCode());
        vo.setStartAt(CanalService.str(level.getStartAt()));
        vo.setEndAt(CanalService.str(level.getEndAt()));
        // 【派生】MeasureWindow 值对象派生
        vo.setDurationMinutes(level.getDurationMinutes());
        vo.setLowLevel(level.getBand() == null ? null : level.getBand().getLowLevel());
        vo.setHighLevel(level.getBand() == null ? null : level.getBand().getHighLevel());
        // 【派生】LevelBand 值对象派生
        vo.setBandSpan(level.getBandSpan());
        vo.setSafetyGrade(level.getSafetyGrade());
        vo.setSafetyGradeName(Dict.safetyGradeName(level.getSafetyGrade()));
        vo.setSafetyGradeColor(Dict.safetyGradeColor(level.getSafetyGrade()));
        vo.setMeasuredLevel(level.getMeasuredLevel());
        vo.setObserver(level.getObserver());
        vo.setAbnormal(level.getAbnormal());
        vo.setAbnormalReason(level.getAbnormalReason());
        vo.setCreatedAt(CanalService.str(level.getCreatedAt()));
        vo.setNote(level.getNote());
        vo.setDesignLowLevel(segment == null ? null : segment.getDesignLowLevel());
        vo.setDesignHighLevel(segment == null ? null : segment.getDesignHighLevel());
        return vo;
    }

    private void applyEmergency(LevelVO vo, EmergencyOrder order) {
        vo.setEmergencyOpen(Boolean.valueOf(order != null));
        if (order == null) {
            return;
        }
        vo.setEmergencyOrderId(order.getId());
        vo.setEmergencyStatus(order.getStatus());
        vo.setEmergencyStatusName(Dict.emergencyStatusName(order.getStatus()));
        vo.setEmergencyHandler(order.getHandler());
    }

    /** 首页概览用：异常量测条数 */
    public long abnormalCount() {
        return levelRepository.countAbnormal();
    }
}
