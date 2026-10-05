package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.Allotment;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.IrrigationPlan;
import com.reservoir.irri.domain.OutageWindow;
import com.reservoir.irri.dto.AllotmentRequest;
import com.reservoir.irri.dto.PlanRequest;
import com.reservoir.irri.dto.Views.AllotmentVO;
import com.reservoir.irri.dto.Views.DayLoadVO;
import com.reservoir.irri.dto.Views.PlanGanttVO;
import com.reservoir.irri.dto.Views.PlanVO;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.IrrigationPlanRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块三 · 灌溉计划与配水。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>计划编号全灌区唯一</li>
 *   <li>同一渠段同一配水日内各条配水的流量合计不得超过该渠段设计流量</li>
 *   <li>计划周期结束日不得早于开始日</li>
 *   <li>计划审批通过后（已批准 / 配水中 / 已结束）不允许再改水量</li>
 *   <li>停用渠段不允许配水（复用 CanalService.assertCanAllot）</li>
 *   <li>与停水窗口联动：计划 / 配水新建、改期、删除后自动重扫待避让（见 OutageService）</li>
 * </ol>
 */
@Service
public class PlanService {

    private final IrrigationPlanRepository planRepository;
    private final AllotmentRepository allotmentRepository;
    private final CanalSegmentRepository segmentRepository;
    private final WaterRequestRepository requestRepository;
    private final CanalService canalService;
    private final OutageService outageService;

    public PlanService(IrrigationPlanRepository planRepository, AllotmentRepository allotmentRepository,
                       CanalSegmentRepository segmentRepository, WaterRequestRepository requestRepository,
                       CanalService canalService, OutageService outageService) {
        this.planRepository = planRepository;
        this.allotmentRepository = allotmentRepository;
        this.segmentRepository = segmentRepository;
        this.requestRepository = requestRepository;
        this.canalService = canalService;
        this.outageService = outageService;
    }

    // ---------------- 查询 ----------------

    public List<IrrigationPlan> list(Long segmentId) {
        if (segmentId == null) {
            return planRepository.findAllByOrderByStartDateDescIdDesc();
        }
        return planRepository.findByCanalSegmentIdOrderByStartDateDesc(segmentId);
    }

    /** 计划 + 它的配水甘特条 */
    public List<PlanVO> listVO(Long segmentId) {
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        List<PlanVO> result = new ArrayList<>();
        for (IrrigationPlan plan : list(segmentId)) {
            PlanVO vo = toPlanVO(plan, segments.get(plan.getCanalSegmentId()));
            List<Allotment> own = allotmentRepository.findByPlanIdOrderByAllotDateAsc(plan.getId());
            BigDecimal volume = BigDecimal.ZERO;
            for (Allotment allotment : own) {
                vo.getAllotments().add(toAllotmentVO(allotment, segments.get(allotment.getCanalSegmentId()), plan));
                if (!allotment.isCanceled()) {
                    volume = volume.add(CanalService.nvl(allotment.getVolumeWan()));
                }
            }
            vo.setAllottedVolume(volume.setScale(2, RoundingMode.HALF_UP));
            result.add(vo);
        }
        return result;
    }

    /** 甘特图：日期轴 + 各渠段每日负载 */
    public PlanGanttVO gantt(Long segmentId) {
        List<PlanVO> plans = listVO(segmentId);
        LocalDate from = null;
        LocalDate to = null;
        for (PlanVO plan : plans) {
            LocalDate s = parseDate(plan.getStartDate());
            LocalDate e = parseDate(plan.getEndDate());
            if (s != null && (from == null || s.isBefore(from))) {
                from = s;
            }
            if (e != null && (to == null || e.isAfter(to))) {
                to = e;
            }
        }
        if (from == null) {
            from = LocalDate.now().withDayOfMonth(1);
        }
        if (to == null) {
            to = from.plusDays(13);
        }
        // 停水窗口：把生效窗口的覆盖日并入日期轴，保证甘特能看到停水色带
        List<OutageWindow> outageWindows = outageService.activeWindows();
        for (OutageWindow window : outageWindows) {
            LocalDate[] days = OutageService.windowDays(window);
            if (days[0].isBefore(from)) {
                from = days[0];
            }
            if (days[1].isAfter(to)) {
                to = days[1];
            }
        }
        if (ChronoUnit.DAYS.between(from, to) > 60) {
            to = from.plusDays(60);
        }

        PlanGanttVO vo = new PlanGanttVO();
        vo.setStartDate(from.toString());
        vo.setEndDate(to.toString());
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            vo.getDates().add(d.toString());
        }
        vo.setPlans(plans);

        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        List<Allotment> all = allotmentRepository.findBetween(from, to);
        // 每条配水补上当日合计与负载率
        for (Allotment allotment : all) {
            CanalSegment segment = segments.get(allotment.getCanalSegmentId());
            IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
            AllotmentVO avo = toAllotmentVO(allotment, segment, plan);
            BigDecimal dayTotal = CanalService.nvl(
                    allotmentRepository.sumFlowBySegmentAndDate(allotment.getCanalSegmentId(), allotment.getAllotDate()));
            avo.setDayTotalFlow(dayTotal.setScale(2, RoundingMode.HALF_UP));
            BigDecimal design = segment == null ? BigDecimal.ZERO : CanalService.nvl(segment.getDesignFlow());
            avo.setDesignFlow(design);
            avo.setLoadRate(design.compareTo(BigDecimal.ZERO) <= 0
                    ? 0D : Math.min(1D, dayTotal.doubleValue() / design.doubleValue()));
            vo.getAllotments().add(avo);
        }
        // 每日 × 每渠段的负载（去重）
        Map<String, DayLoadVO> loadMap = new LinkedHashMap<>();
        for (Allotment allotment : all) {
            CanalSegment segment = segments.get(allotment.getCanalSegmentId());
            String key = allotment.getAllotDate() + "#" + allotment.getCanalSegmentId();
            DayLoadVO load = loadMap.get(key);
            if (load == null) {
                load = new DayLoadVO();
                load.setAllotDate(allotment.getAllotDate() == null ? null : allotment.getAllotDate().toString());
                load.setCanalSegmentId(allotment.getCanalSegmentId());
                load.setSegmentCode(segment == null ? null : segment.getSegmentCode());
                load.setDesignFlow(segment == null ? BigDecimal.ZERO : CanalService.nvl(segment.getDesignFlow()));
                loadMap.put(key, load);
            }
        }
        for (DayLoadVO load : loadMap.values()) {
            LocalDate day = parseDate(load.getAllotDate());
            BigDecimal total = CanalService.nvl(
                    allotmentRepository.sumFlowBySegmentAndDate(load.getCanalSegmentId(), day));
            load.setTotalFlow(total.setScale(2, RoundingMode.HALF_UP));
            BigDecimal design = CanalService.nvl(load.getDesignFlow());
            load.setLoadRate(design.compareTo(BigDecimal.ZERO) <= 0
                    ? 0D : Math.min(1D, total.doubleValue() / design.doubleValue()));
            load.setRemainFlow(design.subtract(total).setScale(2, RoundingMode.HALF_UP));
            // 剩余可配水量：剩余流量 × 8.64（1 m³/s 放一天的来水量，万m³）
            load.setRemainVolume(design.subtract(total).multiply(Dict.DAY_FLOW_TO_VOLUME)
                    .setScale(2, RoundingMode.HALF_UP));
            vo.getDayLoads().add(load);
        }
        // 停水窗口与避让项随甘特返回：前端画停水色带、待避让标记与最终去向
        vo.setWindows(outageService.ganttWindows(from, to));
        Set<Long> planIds = plans.stream().map(PlanVO::getId).collect(Collectors.toSet());
        Set<Long> allotIds = all.stream().map(Allotment::getId).collect(Collectors.toSet());
        vo.setAvoidItems(outageService.ganttItems(planIds, allotIds));
        return vo;
    }

    public List<Allotment> allotments(Long planId, Long segmentId) {
        if (planId != null) {
            return allotmentRepository.findByPlanIdOrderByAllotDateAsc(planId);
        }
        if (segmentId != null) {
            return allotmentRepository.findByCanalSegmentIdOrderByAllotDateAsc(segmentId);
        }
        return allotmentRepository.findAll();
    }

    public IrrigationPlan get(Long id) {
        return planRepository.findById(id).orElseThrow(() -> new BizException("灌溉计划不存在：" + id));
    }

    public Allotment getAllotment(Long id) {
        return allotmentRepository.findById(id).orElseThrow(() -> new BizException("配水记录不存在：" + id));
    }

    /**
     * 该渠段该日剩余可配水量（万m³）=（设计流量 − 已配流量合计）× 8.64 − 已批准申请的额度预占。
     * 用水申请模块用它核算额度，保证「申请量不得超过剩余可配水量」与配水口径一致。
     * 已批 / 已供水申请形成额度预占（quota_reserved，历史数据无标记时按状态计入），
     * 待审与已驳回申请不占额度；申请卡片、审批提示与额度查询都走这里，口径一致。
     */
    public BigDecimal remainVolume(Long segmentId, LocalDate day) {
        CanalSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new BizException("渠段不存在：" + segmentId));
        BigDecimal used = CanalService.nvl(allotmentRepository.sumFlowBySegmentAndDate(segmentId, day));
        BigDecimal remain = CanalService.nvl(segment.getDesignFlow()).subtract(used);
        if (remain.signum() < 0) {
            remain = BigDecimal.ZERO;
        }
        // 同时扣除该渠段该日已批准（含已供水）用水申请的额度预占
        BigDecimal reserved = CanalService.nvl(requestRepository.sumReservedVolume(segmentId, day));
        BigDecimal volume = remain.multiply(Dict.DAY_FLOW_TO_VOLUME).subtract(reserved);
        if (volume.signum() < 0) {
            volume = BigDecimal.ZERO;
        }
        return volume.setScale(2, RoundingMode.HALF_UP);
    }

    /** 该渠段该日已配流量合计（m³/s） */
    public BigDecimal dayUsedFlow(Long segmentId, LocalDate day) {
        return CanalService.nvl(allotmentRepository.sumFlowBySegmentAndDate(segmentId, day))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------- 计划 ----------------

    @Transactional
    public IrrigationPlan create(PlanRequest request) {
        if (!StringUtils.hasText(request.getPlanCode())) {
            throw new BizException("计划编号不能为空");
        }
        if (planRepository.existsByPlanCode(request.getPlanCode())) {
            throw new BizException("计划编号已存在：" + request.getPlanCode());
        }
        if (request.getCanalSegmentId() == null) {
            throw new BizException("请选择计划的主配水渠段");
        }
        canalService.assertCanAllot(request.getCanalSegmentId());
        if (request.getCropType() != null && !Dict.isValidCropType(request.getCropType())) {
            throw new BizException("作物类型只能为 水稻 / 冬小麦 / 玉米 / 棉花 / 蔬菜");
        }
        assertPeriod(request.getStartDate(), request.getEndDate());

        IrrigationPlan plan = new IrrigationPlan();
        apply(plan, request);
        // 默认值写在 create 里
        if (plan.getStatus() == null) {
            plan.setStatus("DRAFT");
        }
        if (plan.getPlanFlow() == null) {
            plan.setPlanFlow(BigDecimal.ZERO);
        }
        recalcVolume(plan);
        IrrigationPlan saved = planRepository.save(plan);
        // 与停水窗口联动：新计划若落在生效窗口内即标待避让
        outageService.scanPlan(saved);
        return saved;
    }

    @Transactional
    public IrrigationPlan update(Long id, PlanRequest request) {
        IrrigationPlan plan = get(id);
        if (StringUtils.hasText(request.getPlanCode())
                && !request.getPlanCode().equals(plan.getPlanCode())
                && planRepository.existsByPlanCode(request.getPlanCode())) {
            throw new BizException("计划编号已存在：" + request.getPlanCode());
        }
        if (request.getCanalSegmentId() != null) {
            canalService.assertCanAllot(request.getCanalSegmentId());
        }
        if (request.getCropType() != null && !Dict.isValidCropType(request.getCropType())) {
            throw new BizException("作物类型只能为 水稻 / 冬小麦 / 玉米 / 棉花 / 蔬菜");
        }
        if (request.getStatus() != null && !Dict.isValidPlanStatus(request.getStatus())) {
            throw new BizException("计划状态只能为 编制 / 待审批 / 已批准 / 配水中 / 已结束");
        }
        assertPeriod(request.getStartDate() == null ? plan.getStartDate() : request.getStartDate(),
                request.getEndDate() == null ? plan.getEndDate() : request.getEndDate());
        // 规则：计划审批通过后不允许改水量
        if (plan.isLocked()
                && (request.getPlanFlow() != null || request.getPlanVolume() != null
                || request.getStartDate() != null || request.getEndDate() != null)) {
            throw new BizException("计划已" + Dict.planStatusName(plan.getStatus()) + "，不允许再改配水流量与周期");
        }
        CanalService.ifNotNull(request.getPlanCode(), plan::setPlanCode);
        CanalService.ifNotNull(request.getPlanName(), plan::setPlanName);
        CanalService.ifNotNull(request.getCanalSegmentId(), plan::setCanalSegmentId);
        CanalService.ifNotNull(request.getStartDate(), plan::setStartDate);
        CanalService.ifNotNull(request.getEndDate(), plan::setEndDate);
        CanalService.ifNotNull(request.getPlanFlow(), plan::setPlanFlow);
        CanalService.ifNotNull(request.getPlanVolume(), plan::setPlanVolume);
        CanalService.ifNotNull(request.getCropType(), plan::setCropType);
        CanalService.ifNotNull(request.getIrrigatedAreaMu(), plan::setIrrigatedAreaMu);
        CanalService.ifNotNull(request.getStatus(), plan::setStatus);
        CanalService.ifNotNull(request.getApprover(), plan::setApprover);
        CanalService.ifNotNull(request.getNote(), plan::setNote);
        recalcDays(plan);
        recalcVolume(plan);
        IrrigationPlan saved = planRepository.save(plan);
        // 与停水窗口联动：周期 / 渠段变化后重扫待避让
        outageService.scanPlan(saved);
        return saved;
    }

    /** 推进计划状态（编制 → 待审批 → 已批准 → 配水中 → 已结束） */
    @Transactional
    public IrrigationPlan advance(Long id, String approver) {
        IrrigationPlan plan = get(id);
        String next = Dict.nextPlanStatus(plan.getStatus());
        if (next == null) {
            throw new BizException("计划已处于终态 " + Dict.planStatusName(plan.getStatus()) + "，不能再推进");
        }
        if ("APPROVED".equals(next) && plan.getPlanFlow().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("计划配水流量为 0，不能批准");
        }
        plan.setStatus(next);
        if ("APPROVED".equals(next) || "RUNNING".equals(next)) {
            plan.setApprover(approver == null ? "灌区调度" : approver);
            plan.setApproveTime(LocalDateTime.now());
        }
        IrrigationPlan saved = planRepository.save(plan);
        // 已结束的计划不再是避让对象
        outageService.scanPlan(saved);
        return saved;
    }

    /** 驳回 / 退回编制 */
    @Transactional
    public IrrigationPlan backToDraft(Long id) {
        IrrigationPlan plan = get(id);
        if (plan.isLocked()) {
            throw new BizException("计划已" + Dict.planStatusName(plan.getStatus()) + "，不能退回编制");
        }
        plan.setStatus("DRAFT");
        plan.setApprover(null);
        plan.setApproveTime(null);
        IrrigationPlan saved = planRepository.save(plan);
        outageService.scanPlan(saved);
        return saved;
    }

    @Transactional
    public void remove(Long id) {
        IrrigationPlan plan = get(id);
        if (plan.isLocked()) {
            throw new BizException("计划已" + Dict.planStatusName(plan.getStatus()) + "，不允许删除");
        }
        List<Allotment> own = allotmentRepository.findByPlanIdOrderByAllotDateAsc(id);
        if (!own.isEmpty()) {
            throw new BizException("计划下仍有 " + own.size() + " 条配水记录，请先删除配水");
        }
        // 计划删除：其未人工确认的待避让项自动解除
        outageService.restorePendingForPlan(id, "计划已删除，自动解除");
        planRepository.delete(plan);
    }

    // ---------------- 配水 ----------------

    @Transactional
    public Allotment createAllotment(AllotmentRequest request) {
        if (request.getPlanId() == null) {
            throw new BizException("请选择配水所属灌溉计划");
        }
        IrrigationPlan plan = get(request.getPlanId());
        Long segmentId = request.getCanalSegmentId() != null
                ? request.getCanalSegmentId() : plan.getCanalSegmentId();
        CanalSegment segment = canalService.assertCanAllot(segmentId);
        if (request.getAllotDate() == null) {
            throw new BizException("请选择配水日期");
        }
        // 规则：配水日期必须落在计划周期内
        if (plan.getStartDate() != null && request.getAllotDate().isBefore(plan.getStartDate())) {
            throw new BizException("配水日期 " + request.getAllotDate() + " 早于计划开始日 " + plan.getStartDate());
        }
        if (plan.getEndDate() != null && request.getAllotDate().isAfter(plan.getEndDate())) {
            throw new BizException("配水日期 " + request.getAllotDate() + " 晚于计划结束日 " + plan.getEndDate());
        }
        if (request.getFlow() == null || request.getFlow().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("配水流量必须大于 0");
        }
        if (request.getFlow().compareTo(Dict.MAX_ALLOT_FLOW) > 0) {
            throw new BizException("单次配水流量不得超过 " + Dict.MAX_ALLOT_FLOW + " m³/s");
        }
        int hours = request.getHours() == null ? 24 : request.getHours();
        if (hours <= 0 || hours > Dict.MAX_ALLOT_HOURS) {
            throw new BizException("配水时长必须在 1~" + Dict.MAX_ALLOT_HOURS + " 小时之间");
        }
        if (request.getStatus() != null && !Dict.isValidAllotStatus(request.getStatus())) {
            throw new BizException("配水状态只能为 已排期 / 配水中 / 已完成 / 已取消");
        }
        // 规则：同一渠段同一配水日的流量合计不得超过该渠段设计流量
        assertDayCapacity(segment, request.getAllotDate(), request.getFlow(), null);

        Allotment allotment = new Allotment();
        applyAllotment(allotment, request, segmentId, hours);
        if (allotment.getStatus() == null) {
            allotment.setStatus("PLANNED");
        }
        Allotment saved = allotmentRepository.save(allotment);
        // 与停水窗口联动：新配水若落在生效窗口内即标待避让
        outageService.scanAllotment(saved);
        return saved;
    }

    @Transactional
    public Allotment updateAllotment(Long id, AllotmentRequest request) {
        Allotment allotment = getAllotment(id);
        IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
        if (plan != null && plan.isLocked() && (request.getFlow() != null || request.getHours() != null)) {
            throw new BizException("所属计划已" + Dict.planStatusName(plan.getStatus()) + "，不允许再改配水流量");
        }
        Long segmentId = request.getCanalSegmentId() != null ? request.getCanalSegmentId() : allotment.getCanalSegmentId();
        CanalSegment segment = canalService.assertCanAllot(segmentId);
        LocalDate day = request.getAllotDate() != null ? request.getAllotDate() : allotment.getAllotDate();
        if (request.getFlow() != null && request.getFlow().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("配水流量必须大于 0");
        }
        if (request.getHours() != null
                && (request.getHours() <= 0 || request.getHours() > Dict.MAX_ALLOT_HOURS)) {
            throw new BizException("配水时长必须在 1~" + Dict.MAX_ALLOT_HOURS + " 小时之间");
        }
        if (request.getStatus() != null && !Dict.isValidAllotStatus(request.getStatus())) {
            throw new BizException("配水状态只能为 已排期 / 配水中 / 已完成 / 已取消");
        }
        BigDecimal newFlow = request.getFlow() != null ? request.getFlow() : allotment.getFlow();
        assertDayCapacity(segment, day, newFlow, allotment.getId());

        CanalService.ifNotNull(request.getPlanId(), allotment::setPlanId);
        CanalService.ifNotNull(request.getCanalSegmentId(), allotment::setCanalSegmentId);
        CanalService.ifNotNull(request.getAllotDate(), allotment::setAllotDate);
        CanalService.ifNotNull(request.getFlow(), allotment::setFlow);
        CanalService.ifNotNull(request.getHours(), allotment::setHours);
        CanalService.ifNotNull(request.getStatus(), allotment::setStatus);
        CanalService.ifNotNull(request.getNote(), allotment::setNote);
        allotment.setVolumeWan(CanalService.nvl(allotment.getFlow())
                .multiply(BigDecimal.valueOf(allotment.getHours() == null ? 24 : allotment.getHours()))
                .multiply(Dict.HOUR_FLOW_TO_VOLUME)
                .setScale(2, RoundingMode.HALF_UP));
        Allotment saved = allotmentRepository.save(allotment);
        // 与停水窗口联动：改期 / 改状态后重扫待避让
        outageService.scanAllotment(saved);
        return saved;
    }

    /** 拖动甘特条改天数：只改日期，流量不变 */
    @Transactional
    public Allotment moveAllotment(Long id, LocalDate newDate) {
        Allotment allotment = getAllotment(id);
        if (newDate == null) {
            throw new BizException("请选择新的配水日期");
        }
        IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
        if (plan != null && plan.getStartDate() != null && newDate.isBefore(plan.getStartDate())) {
            throw new BizException("配水日期不能早于计划开始日 " + plan.getStartDate());
        }
        if (plan != null && plan.getEndDate() != null && newDate.isAfter(plan.getEndDate())) {
            throw new BizException("配水日期不能晚于计划结束日 " + plan.getEndDate());
        }
        CanalSegment segment = canalService.assertCanAllot(allotment.getCanalSegmentId());
        assertDayCapacity(segment, newDate, allotment.getFlow(), allotment.getId());
        allotment.setAllotDate(newDate);
        Allotment saved = allotmentRepository.save(allotment);
        // 与停水窗口联动：拖动改期后重扫待避让
        outageService.scanAllotment(saved);
        return saved;
    }

    @Transactional
    public void removeAllotment(Long id) {
        Allotment allotment = getAllotment(id);
        IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
        if (plan != null && plan.isLocked()) {
            throw new BizException("所属计划已" + Dict.planStatusName(plan.getStatus()) + "，不允许删除配水");
        }
        // 配水删除：其未人工确认的待避让项自动解除
        outageService.restorePendingForAllotment(id, "配水已删除，自动解除");
        allotmentRepository.delete(allotment);
    }

    /**
     * 规则：同一渠段同一配水日内各条配水的流量合计不得超过该渠段设计流量。
     *
     * @param selfId 更新时排除自己；新增传 null
     */
    private void assertDayCapacity(CanalSegment segment, LocalDate day, BigDecimal newFlow, Long selfId) {
        if (segment == null || day == null || newFlow == null) {
            return;
        }
        BigDecimal design = CanalService.nvl(segment.getDesignFlow());
        if (design.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，不能排配水");
        }
        BigDecimal used = BigDecimal.ZERO;
        for (Allotment a : allotmentRepository.findActiveBySegmentAndDate(segment.getId(), day)) {
            if (selfId != null && selfId.equals(a.getId())) {
                continue;
            }
            used = used.add(CanalService.nvl(a.getFlow()));
        }
        if (used.add(newFlow).compareTo(design) > 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " " + day + " 配水流量合计 "
                    + used.add(newFlow).setScale(2, RoundingMode.HALF_UP)
                    + " m³/s 超过设计流量 " + design + " m³/s（该日已排 " + used.setScale(2, RoundingMode.HALF_UP) + "）");
        }
    }

    /** 规则：周期结束日不得早于开始日 */
    private void assertPeriod(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BizException("计划周期结束日 " + end + " 不得早于开始日 " + start);
        }
    }

    /** 计划天数 = 结束日 − 开始日 + 1 */
    private void recalcDays(IrrigationPlan plan) {
        if (plan.getStartDate() != null && plan.getEndDate() != null) {
            long days = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;
            plan.setPlanDays((int) Math.max(0, days));
        }
    }

    /** 计划配水总量（万m³）= 计划流量 × 天数 × 8.64 */
    private void recalcVolume(IrrigationPlan plan) {
        if (plan.getPlanFlow() != null && plan.getPlanDays() != null && plan.getPlanDays() > 0) {
            plan.setPlanVolume(plan.getPlanFlow()
                    .multiply(BigDecimal.valueOf(plan.getPlanDays()))
                    .multiply(Dict.DAY_FLOW_TO_VOLUME)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void apply(IrrigationPlan plan, PlanRequest request) {
        plan.setPlanCode(request.getPlanCode());
        plan.setPlanName(request.getPlanName());
        plan.setCanalSegmentId(request.getCanalSegmentId());
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setPlanFlow(request.getPlanFlow());
        plan.setPlanVolume(request.getPlanVolume());
        plan.setCropType(request.getCropType());
        plan.setIrrigatedAreaMu(request.getIrrigatedAreaMu());
        plan.setStatus(request.getStatus());
        plan.setApprover(request.getApprover());
        plan.setNote(request.getNote());
        recalcDays(plan);
        if (request.getPlanVolume() == null) {
            recalcVolume(plan);
        }
    }

    private void applyAllotment(Allotment allotment, AllotmentRequest request, Long segmentId, int hours) {
        allotment.setPlanId(request.getPlanId());
        allotment.setCanalSegmentId(segmentId);
        allotment.setAllotDate(request.getAllotDate());
        allotment.setFlow(request.getFlow());
        allotment.setHours(hours);
        allotment.setStatus(request.getStatus());
        allotment.setNote(request.getNote());
        allotment.setVolumeWan(CanalService.nvl(request.getFlow())
                .multiply(BigDecimal.valueOf(hours))
                .multiply(Dict.HOUR_FLOW_TO_VOLUME)
                .setScale(2, RoundingMode.HALF_UP));
    }

    // ---------------- VO ----------------

    public static PlanVO toPlanVO(IrrigationPlan plan, CanalSegment segment) {
        PlanVO vo = new PlanVO();
        vo.setId(plan.getId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setPlanName(plan.getPlanName());
        vo.setCanalSegmentId(plan.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStartDate(CanalService.str(plan.getStartDate()));
        vo.setEndDate(CanalService.str(plan.getEndDate()));
        vo.setPlanDays(plan.getPlanDays());
        vo.setPlanFlow(plan.getPlanFlow());
        vo.setPlanVolume(plan.getPlanVolume());
        vo.setCropType(plan.getCropType());
        vo.setCropTypeName(Dict.cropTypeName(plan.getCropType()));
        vo.setCropTypeColor(Dict.cropTypeColor(plan.getCropType()));
        vo.setIrrigatedAreaMu(plan.getIrrigatedAreaMu());
        vo.setStatus(plan.getStatus());
        vo.setStatusName(Dict.planStatusName(plan.getStatus()));
        vo.setStatusColor(Dict.planStatusColor(plan.getStatus()));
        vo.setApprover(plan.getApprover());
        vo.setApproveTime(CanalService.str(plan.getApproveTime()));
        vo.setNote(plan.getNote());
        vo.setLocked(Boolean.valueOf(plan.isLocked()));
        return vo;
    }

    public static AllotmentVO toAllotmentVO(Allotment allotment, CanalSegment segment, IrrigationPlan plan) {
        AllotmentVO vo = new AllotmentVO();
        vo.setId(allotment.getId());
        vo.setPlanId(allotment.getPlanId());
        vo.setPlanCode(plan == null ? null : plan.getPlanCode());
        vo.setPlanName(plan == null ? null : plan.getPlanName());
        vo.setCanalSegmentId(allotment.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setAllotDate(CanalService.str(allotment.getAllotDate()));
        vo.setFlow(allotment.getFlow());
        vo.setHours(allotment.getHours());
        vo.setVolumeWan(allotment.getVolumeWan());
        vo.setStatus(allotment.getStatus());
        vo.setStatusName(Dict.allotStatusName(allotment.getStatus()));
        vo.setStatusColor(Dict.allotStatusColor(allotment.getStatus()));
        vo.setNote(allotment.getNote());
        vo.setDesignFlow(segment == null ? BigDecimal.ZERO : CanalService.nvl(segment.getDesignFlow()));
        return vo;
    }

    private static LocalDate parseDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
