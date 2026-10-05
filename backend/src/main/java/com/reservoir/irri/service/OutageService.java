package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.Allotment;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.IrrigationPlan;
import com.reservoir.irri.domain.OutageAvoidItem;
import com.reservoir.irri.domain.OutageWindow;
import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.dto.AvoidResolveRequest;
import com.reservoir.irri.dto.OutageWindowRequest;
import com.reservoir.irri.dto.Views.AvoidItemVO;
import com.reservoir.irri.dto.Views.OutageWindowVO;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.IrrigationPlanRepository;
import com.reservoir.irri.repository.OutageAvoidItemRepository;
import com.reservoir.irri.repository.OutageWindowRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块三补 · 渠段检修停水窗口与避让。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>窗口登记 / 变更即扫描：重叠的灌溉计划与配水条标为「待避让」；变更后不再重叠的待避让项自动解除，
 *       已人工确认（顺延 / 拆分 / 例外保留）的结果与历史不被覆盖</li>
 *   <li>整体顺延：计划（或配水条）整体平移到窗口结束后，同步计划周期、剩余水量与相关用水申请的供水日期</li>
 *   <li>拆分改期：计划在窗口边界拆成前后两段（后段生成新计划）；配水条可指定窗口外日期</li>
 *   <li>例外保留：保留原安排，必须填写例外依据</li>
 *   <li>撤销窗口：只恢复尚未人工确认的待避让项，已处理结果保留为历史</li>
 *   <li>避让调整后与其它生效窗口再次冲突的，重新标为待避让；日流量合计仍不得超渠段设计流量</li>
 * </ol>
 */
@Service
public class OutageService {

    /** 参与避让扫描的计划状态（已结束的是历史，不再避让） */
    private static final List<String> PLAN_ACTIVE = List.of("DRAFT", "PENDING", "APPROVED", "RUNNING");
    /** 参与避让扫描的配水状态（已完成 / 已取消的是历史） */
    private static final List<String> ALLOT_ACTIVE = List.of("PLANNED", "SUPPLYING");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OutageWindowRepository windowRepository;
    private final OutageAvoidItemRepository itemRepository;
    private final IrrigationPlanRepository planRepository;
    private final AllotmentRepository allotmentRepository;
    private final WaterRequestRepository requestRepository;
    private final CanalSegmentRepository segmentRepository;
    private final CanalService canalService;

    public OutageService(OutageWindowRepository windowRepository, OutageAvoidItemRepository itemRepository,
                         IrrigationPlanRepository planRepository, AllotmentRepository allotmentRepository,
                         WaterRequestRepository requestRepository, CanalSegmentRepository segmentRepository,
                         CanalService canalService) {
        this.windowRepository = windowRepository;
        this.itemRepository = itemRepository;
        this.planRepository = planRepository;
        this.allotmentRepository = allotmentRepository;
        this.requestRepository = requestRepository;
        this.segmentRepository = segmentRepository;
        this.canalService = canalService;
    }

    // ---------------- 查询 ----------------

    public List<OutageWindowVO> list(Long segmentId) {
        List<OutageWindow> windows = segmentId == null
                ? windowRepository.findAllByOrderByIdDesc()
                : windowRepository.findByCanalSegmentIdOrderByIdDesc(segmentId);
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        List<OutageWindowVO> result = new ArrayList<>();
        for (OutageWindow window : windows) {
            OutageWindowVO vo = toWindowVO(window, segments.get(window.getCanalSegmentId()));
            fillItemCounts(vo);
            result.add(vo);
        }
        return result;
    }

    /** 某窗口的全部避让项（含历史去向） */
    public List<AvoidItemVO> items(Long windowId) {
        OutageWindow window = get(windowId);
        return itemRepository.findByWindowIdOrderByIdAsc(windowId).stream()
                .map(item -> toItemVO(item, window))
                .collect(Collectors.toList());
    }

    /** 全部待避让项（跨窗口，角标用） */
    public List<AvoidItemVO> pending() {
        Map<Long, OutageWindow> windows = windowRepository.findAll().stream()
                .collect(Collectors.toMap(OutageWindow::getId, w -> w, (a, b) -> a));
        List<AvoidItemVO> result = new ArrayList<>();
        for (OutageAvoidItem item : itemRepository.findByStatusOrderByIdAsc("PENDING")) {
            result.add(toItemVO(item, windows.get(item.getWindowId())));
        }
        return result;
    }

    public OutageWindow get(Long id) {
        return windowRepository.findById(id).orElseThrow(() -> new BizException("停水窗口不存在：" + id));
    }

    public OutageAvoidItem getItem(Long itemId) {
        return itemRepository.findById(itemId).orElseThrow(() -> new BizException("避让项不存在：" + itemId));
    }

    /** 生效中的窗口（计划甘特扩展日期轴用） */
    public List<OutageWindow> activeWindows() {
        return windowRepository.findByStatus("ACTIVE");
    }

    /** 甘特图：与日期轴相交的生效窗口 */
    public List<OutageWindowVO> ganttWindows(LocalDate from, LocalDate to) {
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        List<OutageWindowVO> result = new ArrayList<>();
        for (OutageWindow window : activeWindows()) {
            LocalDate[] days = windowDays(window);
            if (days[1].isBefore(from) || days[0].isAfter(to)) {
                continue;
            }
            result.add(toWindowVO(window, segments.get(window.getCanalSegmentId())));
        }
        return result;
    }

    /** 甘特图：图上计划 / 配水条相关的避让项（生效窗口的项全带，已撤销窗口只带图上对象的历史去向） */
    public List<AvoidItemVO> ganttItems(Set<Long> planIds, Set<Long> allotIds) {
        Map<Long, OutageWindow> windows = windowRepository.findAll().stream()
                .collect(Collectors.toMap(OutageWindow::getId, w -> w, (a, b) -> a));
        List<AvoidItemVO> result = new ArrayList<>();
        for (OutageAvoidItem item : itemRepository.findAll()) {
            OutageWindow window = windows.get(item.getWindowId());
            boolean activeWindow = window != null && window.isActive();
            boolean onChart = (item.getPlanId() != null && planIds.contains(item.getPlanId()))
                    || (item.getAllotmentId() != null && allotIds.contains(item.getAllotmentId()));
            if (activeWindow || onChart) {
                result.add(toItemVO(item, window));
            }
        }
        return result;
    }

    // ---------------- 窗口登记 / 变更 / 撤销 ----------------

    @Transactional
    public OutageWindowVO create(OutageWindowRequest request) {
        if (request.getCanalSegmentId() == null) {
            throw new BizException("请选择停水渠段");
        }
        CanalSegment segment = canalService.getSegment(request.getCanalSegmentId());
        assertWindowPeriod(request.getStartAt(), request.getEndAt());
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("请填写停水原因");
        }
        String scope = request.getScopeType() == null ? "FULL" : request.getScopeType();
        if (!Dict.isValidOutageScope(scope)) {
            throw new BizException("停水范围只能为 全段停水 / 局部停水");
        }

        OutageWindow window = new OutageWindow();
        window.setWindowNo(nextWindowNo());
        window.setCanalSegmentId(segment.getId());
        window.setStartAt(request.getStartAt());
        window.setEndAt(request.getEndAt());
        window.setReason(request.getReason());
        window.setScopeType(scope);
        window.setScopeNote(request.getScopeNote());
        // 默认值写在 create 里
        window.setStatus("ACTIVE");
        window.setCreatedBy(StringUtils.hasText(request.getCreatedBy()) ? request.getCreatedBy() : "运维登记");
        window.setCreatedAt(LocalDateTime.now());
        window.setUpdatedAt(LocalDateTime.now());
        window.setNote(request.getNote());
        windowRepository.save(window);
        // 窗口生效：找出重叠的灌溉计划与配水条，标为待避让
        scanWindow(window);
        OutageWindowVO vo = toWindowVO(window, segment);
        fillItemCounts(vo);
        return vo;
    }

    @Transactional
    public OutageWindowVO update(Long id, OutageWindowRequest request) {
        OutageWindow window = get(id);
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，属于历史记录，不允许再变更");
        }
        if (request.getCanalSegmentId() != null && !request.getCanalSegmentId().equals(window.getCanalSegmentId())) {
            canalService.getSegment(request.getCanalSegmentId());
            window.setCanalSegmentId(request.getCanalSegmentId());
        }
        LocalDateTime start = request.getStartAt() == null ? window.getStartAt() : request.getStartAt();
        LocalDateTime end = request.getEndAt() == null ? window.getEndAt() : request.getEndAt();
        assertWindowPeriod(start, end);
        window.setStartAt(start);
        window.setEndAt(end);
        if (request.getScopeType() != null && !Dict.isValidOutageScope(request.getScopeType())) {
            throw new BizException("停水范围只能为 全段停水 / 局部停水");
        }
        CanalService.ifNotNull(request.getReason(), window::setReason);
        CanalService.ifNotNull(request.getScopeType(), window::setScopeType);
        CanalService.ifNotNull(request.getScopeNote(), window::setScopeNote);
        CanalService.ifNotNull(request.getNote(), window::setNote);
        window.setUpdatedAt(LocalDateTime.now());
        windowRepository.save(window);
        // 窗口变更：重新比对——新增重叠标待避让，不再重叠的待避让自动解除（已人工确认的不动）
        rescanWindow(window);
        OutageWindowVO vo = toWindowVO(window, segmentRepository.findById(window.getCanalSegmentId()).orElse(null));
        fillItemCounts(vo);
        return vo;
    }

    /** 撤销窗口：只恢复尚未人工确认的待避让项；已顺延 / 已拆分 / 例外保留的结果与历史不覆盖 */
    @Transactional
    public OutageWindowVO cancel(Long id, String note) {
        OutageWindow window = get(id);
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，不能重复操作");
        }
        window.setStatus("CANCELED");
        window.setCanceledAt(LocalDateTime.now());
        window.setCancelNote(note);
        window.setUpdatedAt(LocalDateTime.now());
        windowRepository.save(window);
        for (OutageAvoidItem item : itemRepository.findByWindowIdAndStatus(id, "PENDING")) {
            restore(item, "窗口撤销，自动恢复");
        }
        OutageWindowVO vo = toWindowVO(window, segmentRepository.findById(window.getCanalSegmentId()).orElse(null));
        fillItemCounts(vo);
        return vo;
    }

    // ---------------- 避让处理 ----------------

    @Transactional
    public AvoidItemVO resolve(Long itemId, AvoidResolveRequest request) {
        OutageAvoidItem item = getItem(itemId);
        if (!item.isPending()) {
            throw new BizException("该避让项已处理（" + Dict.avoidStatusName(item.getStatus()) + "），不能重复处理");
        }
        OutageWindow window = get(item.getWindowId());
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，其待避让项已自动恢复，请刷新后再处理");
        }
        String action = request.getAction();
        if (!Dict.isValidAvoidAction(action)) {
            throw new BizException("处理方式只能为 整体顺延 / 拆分改期 / 例外保留");
        }
        String operator = StringUtils.hasText(request.getOperator()) ? request.getOperator() : "灌区调度";
        if ("KEEP".equals(action)) {
            // 例外保留：原安排不动，必须填写例外依据
            if (!StringUtils.hasText(request.getReason())) {
                throw new BizException("例外保留必须填写例外依据");
            }
            item.setStatus("KEPT");
            item.setResolutionNote("例外依据：" + request.getReason());
            item.setOperator(operator);
            item.setResolvedAt(LocalDateTime.now());
            itemRepository.save(item);
            return toItemVO(item, window);
        }
        if ("PLAN".equals(item.getTargetType())) {
            if ("POSTPONE".equals(action)) {
                postponePlan(item, window, operator);
            } else {
                splitPlan(item, window, operator);
            }
        } else {
            if ("POSTPONE".equals(action)) {
                postponeAllotment(item, window, operator);
            } else {
                splitAllotment(item, window, operator, request.getTargetDate());
            }
        }
        return toItemVO(item, window);
    }

    /** 计划整体顺延：周期与未执行配水整体平移到窗口结束后首日，同步用水申请供水日期 */
    private void postponePlan(OutageAvoidItem item, OutageWindow window, String operator) {
        IrrigationPlan plan = planRepository.findById(item.getPlanId()).orElse(null);
        if (plan == null) {
            throw new BizException("计划已删除，无法顺延");
        }
        if (!PLAN_ACTIVE.contains(plan.getStatus())) {
            throw new BizException("计划已结束，无需避让");
        }
        LocalDate[] days = windowDays(window);
        LocalDate newStart = days[1].plusDays(1);
        long shift = ChronoUnit.DAYS.between(plan.getStartDate(), newStart);
        if (shift <= 0) {
            throw new BizException("计划起点已在窗口结束之后，无需顺延");
        }
        // 预检：整体平移后各目标日的流量合计不得超设计流量
        List<Allotment> actives = activeAllotments(plan.getId());
        assertBatchCapacity(actives, shift);

        LocalDate oldStart = plan.getStartDate();
        LocalDate oldEnd = plan.getEndDate();
        // 避让调整属于调度特权操作：即使计划已批准锁定，也随避让同步计划周期
        plan.setStartDate(oldStart.plusDays(shift));
        plan.setEndDate(oldEnd.plusDays(shift));
        recalcDays(plan);
        recalcVolume(plan);
        planRepository.save(plan);

        int movedAllots = 0;
        for (Allotment a : actives) {
            a.setAllotDate(a.getAllotDate().plusDays(shift));
            allotmentRepository.save(a);
            movedAllots++;
            // 同窗口下该配水条的待避让项随计划一并了结；历史已完成 / 已取消的配水保持原日期
            autoResolveAllotItem(window, a, "POSTPONED", "随计划整体顺延至 " + a.getAllotDate(), operator);
        }
        // 同步相关用水申请的供水日期：窗口覆盖日整体平移
        int movedReqs = moveRequestsBetween(plan.getCanalSegmentId(), days[0], days[1], shift);

        item.setStatus("POSTPONED");
        item.setNewStartDate(plan.getStartDate());
        item.setNewEndDate(plan.getEndDate());
        item.setResolutionNote("整体顺延 " + shift + " 天：" + oldStart + "~" + oldEnd + " → "
                + plan.getStartDate() + "~" + plan.getEndDate()
                + "；同步配水 " + movedAllots + " 条、用水申请 " + movedReqs + " 张；历史已完成配水保持原日期");
        item.setOperator(operator);
        item.setResolvedAt(LocalDateTime.now());
        itemRepository.save(item);
        // 调整后再次冲突：对新周期重新扫描全部生效窗口
        scanPlan(plan);
        for (Allotment a : actives) {
            scanAllotment(a);
        }
    }

    /** 计划拆分：窗口前段保留在原计划，窗口起及之后的后段平移生成新计划 */
    private void splitPlan(OutageAvoidItem item, OutageWindow window, String operator) {
        IrrigationPlan plan = planRepository.findById(item.getPlanId()).orElse(null);
        if (plan == null) {
            throw new BizException("计划已删除，无法拆分");
        }
        if (!PLAN_ACTIVE.contains(plan.getStatus())) {
            throw new BizException("计划已结束，无需避让");
        }
        LocalDate[] days = windowDays(window);
        LocalDate ws = days[0];
        LocalDate we = days[1];
        if (plan.getStartDate() == null || !plan.getStartDate().isBefore(ws)) {
            throw new BizException("计划起点不在窗口之前，没有可保留的前段，请改用整体顺延");
        }
        LocalDate oldStart = plan.getStartDate();
        LocalDate oldEnd = plan.getEndDate();
        LocalDate beforeEnd = ws.minusDays(1);
        long shift = ChronoUnit.DAYS.between(ws, we.plusDays(1));
        LocalDate newStart = we.plusDays(1);
        LocalDate newEnd = oldEnd.plusDays(shift);

        // 后段（窗口起始日及之后）的未执行配水整体平移到新计划
        List<Allotment> tail = activeAllotments(plan.getId()).stream()
                .filter(a -> a.getAllotDate() != null && !a.getAllotDate().isBefore(ws))
                .collect(Collectors.toList());
        assertBatchCapacity(tail, shift);

        IrrigationPlan later = new IrrigationPlan();
        later.setPlanCode(uniquePlanCode(plan.getPlanCode()));
        later.setPlanName(plan.getPlanName() + "（避让后段）");
        later.setCanalSegmentId(plan.getCanalSegmentId());
        later.setStartDate(newStart);
        later.setEndDate(newEnd);
        later.setPlanFlow(plan.getPlanFlow());
        later.setCropType(plan.getCropType());
        later.setIrrigatedAreaMu(plan.getIrrigatedAreaMu());
        later.setStatus(plan.getStatus());
        later.setApprover(plan.getApprover());
        later.setApproveTime(plan.getApproveTime());
        later.setNote("由停水窗口 " + window.getWindowNo() + " 拆分产生（原计划 " + plan.getPlanCode() + "）");
        recalcDays(later);
        recalcVolume(later);
        planRepository.save(later);

        // 原计划只保留窗口前段
        plan.setEndDate(beforeEnd);
        recalcDays(plan);
        recalcVolume(plan);
        planRepository.save(plan);

        int moved = 0;
        for (Allotment a : tail) {
            a.setPlanId(later.getId());
            a.setAllotDate(a.getAllotDate().plusDays(shift));
            allotmentRepository.save(a);
            moved++;
            autoResolveAllotItem(window, a, "SPLIT", "随计划拆分至 " + later.getPlanCode() + " " + a.getAllotDate(), operator);
        }
        // 同步相关用水申请：窗口起始日及之后（原计划周期内）的供水日期一并平移
        int movedReqs = moveRequestsBetween(plan.getCanalSegmentId(), ws, oldEnd, shift);

        item.setStatus("SPLIT");
        item.setSplitPlanId(later.getId());
        item.setNewStartDate(later.getStartDate());
        item.setNewEndDate(later.getEndDate());
        item.setResolutionNote("拆分避让：原计划保留 " + oldStart + "~" + beforeEnd + "，后段平移 " + shift
                + " 天生成 " + later.getPlanCode() + "（" + newStart + "~" + newEnd + "）"
                + "；同步配水 " + moved + " 条、用水申请 " + movedReqs + " 张");
        item.setOperator(operator);
        item.setResolvedAt(LocalDateTime.now());
        itemRepository.save(item);
        // 调整后再次冲突：两段计划与平移配水都重新扫描
        scanPlan(plan);
        scanPlan(later);
        for (Allotment a : tail) {
            scanAllotment(a);
        }
    }

    /** 配水条整体顺延：移到窗口结束后首日 */
    private void postponeAllotment(OutageAvoidItem item, OutageWindow window, String operator) {
        Allotment a = allotmentRepository.findById(item.getAllotmentId()).orElse(null);
        if (a == null) {
            throw new BizException("配水记录已删除，无法顺延");
        }
        if (!ALLOT_ACTIVE.contains(a.getStatus())) {
            throw new BizException("配水已" + Dict.allotStatusName(a.getStatus()) + "，无需避让");
        }
        LocalDate newDate = windowDays(window)[1].plusDays(1);
        moveAllotmentTo(item, a, newDate, operator, "POSTPONED", "整体顺延至 " + newDate);
    }

    /** 配水条拆分改期：移到调度员指定的窗口外日期 */
    private void splitAllotment(OutageAvoidItem item, OutageWindow window, String operator, LocalDate targetDate) {
        Allotment a = allotmentRepository.findById(item.getAllotmentId()).orElse(null);
        if (a == null) {
            throw new BizException("配水记录已删除，无法改期");
        }
        if (!ALLOT_ACTIVE.contains(a.getStatus())) {
            throw new BizException("配水已" + Dict.allotStatusName(a.getStatus()) + "，无需避让");
        }
        if (targetDate == null) {
            throw new BizException("拆分改期必须指定窗口外日期");
        }
        LocalDate[] days = windowDays(window);
        if (!targetDate.isBefore(days[0]) && !targetDate.isAfter(days[1])) {
            throw new BizException("指定日期 " + targetDate + " 仍在停水窗口 " + days[0] + "~" + days[1] + " 内");
        }
        moveAllotmentTo(item, a, targetDate, operator, "SPLIT", "拆分到窗口外日期 " + targetDate);
    }

    /** 配水条改期公共段：容量校验 → 改期 → 同步计划周期与用水申请 → 重扫冲突 */
    private void moveAllotmentTo(OutageAvoidItem item, Allotment a, LocalDate newDate,
                                 String operator, String status, String label) {
        if (newDate.equals(a.getAllotDate())) {
            throw new BizException("目标日期与原配水日期相同，无需调整");
        }
        assertCapacity(a.getCanalSegmentId(), newDate, a.getFlow(), a.getId());
        LocalDate oldDate = a.getAllotDate();
        a.setAllotDate(newDate);
        allotmentRepository.save(a);
        // 同步计划周期：目标日期超出原计划周期时顺延计划边界（剩余水量随周期重算）
        IrrigationPlan plan = planRepository.findById(a.getPlanId()).orElse(null);
        String planSync = "";
        if (plan != null) {
            boolean changed = false;
            if (plan.getStartDate() == null || newDate.isBefore(plan.getStartDate())) {
                plan.setStartDate(newDate);
                changed = true;
            }
            if (plan.getEndDate() == null || newDate.isAfter(plan.getEndDate())) {
                plan.setEndDate(newDate);
                changed = true;
            }
            if (changed) {
                recalcDays(plan);
                recalcVolume(plan);
                planRepository.save(plan);
                planSync = "；计划周期同步为 " + plan.getStartDate() + "~" + plan.getEndDate();
            }
        }
        // 同步相关用水申请的供水日期
        int movedReqs = moveRequestsOn(a.getCanalSegmentId(), oldDate, newDate);

        item.setStatus(status);
        item.setNewStartDate(newDate);
        item.setResolutionNote(label + "（原 " + oldDate + "）" + planSync + "；同步用水申请 " + movedReqs + " 张");
        item.setOperator(operator);
        item.setResolvedAt(LocalDateTime.now());
        itemRepository.save(item);
        // 调整后再次冲突：新日期重新扫描全部生效窗口
        scanAllotment(a);
        if (plan != null) {
            scanPlan(plan);
        }
    }

    // ---------------- 扫描（窗口生效 / 变更 / 计划与配水变动后） ----------------

    /** 窗口生效：找出重叠的灌溉计划与配水条，标为待避让 */
    private void scanWindow(OutageWindow window) {
        LocalDate[] days = windowDays(window);
        for (IrrigationPlan plan : planRepository.findOverlap(window.getCanalSegmentId(), days[0], days[1])) {
            if (PLAN_ACTIVE.contains(plan.getStatus())) {
                ensurePendingItem(window, "PLAN", plan, null);
            }
        }
        for (Allotment a : allotmentRepository.findActiveBetween(window.getCanalSegmentId(), days[0], days[1])) {
            ensurePendingItem(window, "ALLOT", null, a);
        }
    }

    /** 窗口变更：新增重叠标待避让；不再重叠的待避让自动解除（已人工确认的不动） */
    private void rescanWindow(OutageWindow window) {
        scanWindow(window);
        LocalDate[] days = windowDays(window);
        for (OutageAvoidItem item : itemRepository.findByWindowIdAndStatus(window.getId(), "PENDING")) {
            boolean overlap;
            if ("PLAN".equals(item.getTargetType())) {
                IrrigationPlan plan = item.getPlanId() == null
                        ? null : planRepository.findById(item.getPlanId()).orElse(null);
                overlap = plan != null && PLAN_ACTIVE.contains(plan.getStatus())
                        && overlaps(plan.getStartDate(), plan.getEndDate(), days);
            } else {
                Allotment a = item.getAllotmentId() == null
                        ? null : allotmentRepository.findById(item.getAllotmentId()).orElse(null);
                overlap = a != null && ALLOT_ACTIVE.contains(a.getStatus())
                        && a.getAllotDate() != null
                        && !a.getAllotDate().isBefore(days[0]) && !a.getAllotDate().isAfter(days[1]);
            }
            if (!overlap) {
                restore(item, "窗口变更后不再重叠，自动解除");
            }
        }
    }

    /**
     * 计划新建 / 改期 / 状态变化后（含避让调整）：与全部生效窗口比对，
     * 命中即标待避让；已移出窗口的待避让项自动解除。由 PlanService 联动调用。
     */
    public void scanPlan(IrrigationPlan plan) {
        if (plan == null || plan.getId() == null) {
            return;
        }
        if (!PLAN_ACTIVE.contains(plan.getStatus())) {
            restorePendingOf("PLAN", plan.getId(), null, "计划已结束，自动解除");
            return;
        }
        for (OutageWindow window : activeWindows()) {
            if (!window.getCanalSegmentId().equals(plan.getCanalSegmentId())) {
                continue;
            }
            if (overlaps(plan.getStartDate(), plan.getEndDate(), windowDays(window))) {
                ensurePendingItem(window, "PLAN", plan, null);
            }
        }
        for (OutageAvoidItem item : itemRepository.findByTargetTypeAndPlanIdAndStatus("PLAN", plan.getId(), "PENDING")) {
            OutageWindow window = windowRepository.findById(item.getWindowId()).orElse(null);
            if (window == null || !window.isActive()) {
                continue;
            }
            if (!window.getCanalSegmentId().equals(plan.getCanalSegmentId())
                    || !overlaps(plan.getStartDate(), plan.getEndDate(), windowDays(window))) {
                restore(item, "计划已移出窗口，自动解除");
            }
        }
    }

    /** 配水条新建 / 改期 / 状态变化后：同 scanPlan 的配水条版本 */
    public void scanAllotment(Allotment a) {
        if (a == null || a.getId() == null) {
            return;
        }
        if (!ALLOT_ACTIVE.contains(a.getStatus())) {
            restorePendingOf("ALLOT", null, a.getId(), "配水已" + Dict.allotStatusName(a.getStatus()) + "，自动解除");
            return;
        }
        for (OutageWindow window : activeWindows()) {
            if (!window.getCanalSegmentId().equals(a.getCanalSegmentId())) {
                continue;
            }
            LocalDate[] days = windowDays(window);
            if (a.getAllotDate() != null && !a.getAllotDate().isBefore(days[0]) && !a.getAllotDate().isAfter(days[1])) {
                ensurePendingItem(window, "ALLOT", null, a);
            }
        }
        for (OutageAvoidItem item : itemRepository.findByTargetTypeAndAllotmentIdAndStatus("ALLOT", a.getId(), "PENDING")) {
            OutageWindow window = windowRepository.findById(item.getWindowId()).orElse(null);
            if (window == null || !window.isActive()) {
                continue;
            }
            LocalDate[] days = windowDays(window);
            boolean in = window.getCanalSegmentId().equals(a.getCanalSegmentId())
                    && a.getAllotDate() != null
                    && !a.getAllotDate().isBefore(days[0]) && !a.getAllotDate().isAfter(days[1]);
            if (!in) {
                restore(item, "配水已移出窗口，自动解除");
            }
        }
    }

    /** 计划删除前：其名下未人工确认的待避让项自动解除（PlanService 联动调用） */
    public void restorePendingForPlan(Long planId, String note) {
        restorePendingOf("PLAN", planId, null, note);
    }

    /** 配水删除前：其未人工确认的待避让项自动解除（PlanService 联动调用） */
    public void restorePendingForAllotment(Long allotmentId, String note) {
        restorePendingOf("ALLOT", null, allotmentId, note);
    }

    private void restorePendingOf(String targetType, Long planId, Long allotmentId, String note) {
        List<OutageAvoidItem> items = "PLAN".equals(targetType)
                ? itemRepository.findByPlanIdAndStatus(planId, "PENDING")
                : itemRepository.findByAllotmentIdAndStatus(allotmentId, "PENDING");
        for (OutageAvoidItem item : items) {
            if (targetType.equals(item.getTargetType())) {
                restore(item, note);
            }
        }
    }

    /**
     * 同一窗口同一对象已有待避让项时不重复；已了结（顺延 / 拆分 / 例外 / 恢复）的保留为历史，
     * 再次冲突时开新项。
     */
    private void ensurePendingItem(OutageWindow window, String targetType, IrrigationPlan plan, Allotment allotment) {
        Long planId = plan != null ? plan.getId() : (allotment == null ? null : allotment.getPlanId());
        Long allotId = allotment == null ? null : allotment.getId();
        for (OutageAvoidItem item : itemRepository.findByWindowIdAndStatus(window.getId(), "PENDING")) {
            if (!targetType.equals(item.getTargetType())) {
                continue;
            }
            if ("PLAN".equals(targetType) && Objects.equals(item.getPlanId(), planId)) {
                return;
            }
            if ("ALLOT".equals(targetType) && Objects.equals(item.getAllotmentId(), allotId)) {
                return;
            }
        }
        OutageAvoidItem item = new OutageAvoidItem();
        item.setWindowId(window.getId());
        item.setTargetType(targetType);
        item.setPlanId(planId);
        item.setAllotmentId(allotId);
        item.setCanalSegmentId(window.getCanalSegmentId());
        if (plan != null) {
            item.setOrigStartDate(plan.getStartDate());
            item.setOrigEndDate(plan.getEndDate());
        }
        if (allotment != null) {
            item.setOrigStartDate(allotment.getAllotDate());
        }
        item.setStatus("PENDING");
        item.setCreatedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    /** 恢复待避让项：仅用于未人工确认的项；已顺延 / 已拆分 / 例外保留的历史不经过这里 */
    private void restore(OutageAvoidItem item, String note) {
        item.setStatus("RESTORED");
        item.setResolutionNote(note);
        item.setResolvedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    /** 同窗口下某配水条的待避让项随计划处理一并了结 */
    private void autoResolveAllotItem(OutageWindow window, Allotment a, String status, String note, String operator) {
        for (OutageAvoidItem item : itemRepository.findByWindowIdAndStatus(window.getId(), "PENDING")) {
            if ("ALLOT".equals(item.getTargetType()) && Objects.equals(item.getAllotmentId(), a.getId())) {
                item.setStatus(status);
                item.setNewStartDate(a.getAllotDate());
                item.setResolutionNote(note);
                item.setOperator(operator);
                item.setResolvedAt(LocalDateTime.now());
                itemRepository.save(item);
            }
        }
    }

    // ---------------- 联动：用水申请供水日期 / 容量与周期 ----------------

    /** 某渠段某日的可改期用水申请（待审 / 已批）整体改到新日期；已供水、已驳回为终态不动 */
    private int moveRequestsOn(Long segmentId, LocalDate from, LocalDate to) {
        if (from == null || from.equals(to)) {
            return 0;
        }
        List<WaterRequest> list = requestRepository.findMovableOn(segmentId, from);
        for (WaterRequest r : list) {
            r.setAllotDate(to);
        }
        requestRepository.saveAll(list);
        return list.size();
    }

    /** 某渠段日期区间内的可改期用水申请整体平移 shift 天 */
    private int moveRequestsBetween(Long segmentId, LocalDate from, LocalDate to, long shift) {
        if (from == null || to == null || shift == 0) {
            return 0;
        }
        List<WaterRequest> list = requestRepository.findMovableBetween(segmentId, from, to);
        for (WaterRequest r : list) {
            r.setAllotDate(r.getAllotDate().plusDays(shift));
        }
        requestRepository.saveAll(list);
        return list.size();
    }

    /** 与 PlanService 同口径：同一渠段同一配水日流量合计不得超过设计流量 */
    private void assertCapacity(Long segmentId, LocalDate day, BigDecimal flow, Long selfId) {
        CanalSegment segment = segmentRepository.findById(segmentId).orElse(null);
        if (segment == null || day == null) {
            return;
        }
        BigDecimal design = CanalService.nvl(segment.getDesignFlow());
        if (design.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，不能避让到该日");
        }
        BigDecimal used = BigDecimal.ZERO;
        for (Allotment other : allotmentRepository.findActiveBySegmentAndDate(segmentId, day)) {
            if (selfId != null && selfId.equals(other.getId())) {
                continue;
            }
            used = used.add(CanalService.nvl(other.getFlow()));
        }
        if (used.add(CanalService.nvl(flow)).compareTo(design) > 0) {
            throw new BizException("避让失败：渠段 " + segment.getSegmentCode() + " " + day + " 已排 "
                    + used.setScale(2, RoundingMode.HALF_UP) + " m³/s，再排 " + flow
                    + " m³/s 将超设计流量 " + design + " m³/s，请改用拆分改期另选日期");
        }
    }

    /** 批量平移预检：按「渠段 + 目标日」汇总本批次流量，与该日既有配水合计比对设计流量 */
    private void assertBatchCapacity(List<Allotment> moving, long shiftDays) {
        Map<String, BigDecimal> batchFlow = new LinkedHashMap<>();
        Map<String, Long> batchSegment = new HashMap<>();
        Map<String, LocalDate> batchDay = new HashMap<>();
        Set<Long> ids = moving.stream().map(Allotment::getId).collect(Collectors.toSet());
        for (Allotment a : moving) {
            if (a.getAllotDate() == null) {
                continue;
            }
            LocalDate target = a.getAllotDate().plusDays(shiftDays);
            String key = a.getCanalSegmentId() + "#" + target;
            batchFlow.merge(key, CanalService.nvl(a.getFlow()), BigDecimal::add);
            batchSegment.put(key, a.getCanalSegmentId());
            batchDay.put(key, target);
        }
        for (Map.Entry<String, BigDecimal> entry : batchFlow.entrySet()) {
            Long segmentId = batchSegment.get(entry.getKey());
            LocalDate day = batchDay.get(entry.getKey());
            CanalSegment segment = segmentRepository.findById(segmentId).orElse(null);
            if (segment == null) {
                continue;
            }
            BigDecimal design = CanalService.nvl(segment.getDesignFlow());
            if (design.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，不能避让到 " + day);
            }
            BigDecimal used = BigDecimal.ZERO;
            for (Allotment other : allotmentRepository.findActiveBySegmentAndDate(segmentId, day)) {
                if (ids.contains(other.getId())) {
                    continue;
                }
                used = used.add(CanalService.nvl(other.getFlow()));
            }
            if (used.add(entry.getValue()).compareTo(design) > 0) {
                throw new BizException("避让失败：渠段 " + segment.getSegmentCode() + " " + day + " 平移后流量合计 "
                        + used.add(entry.getValue()).setScale(2, RoundingMode.HALF_UP)
                        + " m³/s 将超设计流量 " + design + " m³/s，请改用拆分改期");
            }
        }
    }

    private List<Allotment> activeAllotments(Long planId) {
        return allotmentRepository.findByPlanIdOrderByAllotDateAsc(planId).stream()
                .filter(a -> ALLOT_ACTIVE.contains(a.getStatus()))
                .collect(Collectors.toList());
    }

    // ---------------- 小工具 ----------------

    /**
     * 窗口覆盖的整日区间 [startDay, endDay]：跨日窗口按覆盖日展开；
     * 结束时间恰为某日 00:00 时该日不算覆盖（停水在该日开始前已结束）。
     */
    public static LocalDate[] windowDays(OutageWindow window) {
        LocalDate startDay = window.getStartAt().toLocalDate();
        LocalDate endDay = window.getEndAt().toLocalDate();
        if (window.getEndAt().toLocalTime().equals(LocalTime.MIDNIGHT)
                && window.getEndAt().isAfter(window.getStartAt())) {
            endDay = endDay.minusDays(1);
        }
        return new LocalDate[]{startDay, endDay};
    }

    private static boolean overlaps(LocalDate start, LocalDate end, LocalDate[] windowDays) {
        return start != null && end != null && !start.isAfter(windowDays[1]) && !end.isBefore(windowDays[0]);
    }

    private void assertWindowPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new BizException("请填写停水起止时间");
        }
        if (!end.isAfter(start)) {
            throw new BizException("停水结束时间必须晚于开始时间");
        }
    }

    private String nextWindowNo() {
        String no = "MW-" + LocalDateTime.now().format(NO_FMT);
        return windowRepository.existsByWindowNo(no) ? no + "R" : no;
    }

    /** 拆分后段计划编号：原计划编号 + -B，占用则 -C、-D…（不超 32 位） */
    private String uniquePlanCode(String planCode) {
        String base = planCode.length() > 29 ? planCode.substring(0, 29) : planCode;
        String code = base + "-B";
        char suffix = 'C';
        while (planRepository.existsByPlanCode(code)) {
            code = base + "-" + (suffix++);
        }
        return code;
    }

    /** 计划天数 = 结束日 − 开始日 + 1（与 PlanService 同口径） */
    private void recalcDays(IrrigationPlan plan) {
        if (plan.getStartDate() != null && plan.getEndDate() != null) {
            long days = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;
            plan.setPlanDays((int) Math.max(0, days));
        }
    }

    /** 计划配水总量（万m³）= 计划流量 × 天数 × 8.64（与 PlanService 同口径） */
    private void recalcVolume(IrrigationPlan plan) {
        if (plan.getPlanFlow() != null && plan.getPlanDays() != null && plan.getPlanDays() > 0) {
            plan.setPlanVolume(plan.getPlanFlow()
                    .multiply(BigDecimal.valueOf(plan.getPlanDays()))
                    .multiply(Dict.DAY_FLOW_TO_VOLUME)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    // ---------------- VO ----------------

    public static OutageWindowVO toWindowVO(OutageWindow window, CanalSegment segment) {
        OutageWindowVO vo = new OutageWindowVO();
        vo.setId(window.getId());
        vo.setWindowNo(window.getWindowNo());
        vo.setCanalSegmentId(window.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStartAt(CanalService.str(window.getStartAt()));
        vo.setEndAt(CanalService.str(window.getEndAt()));
        LocalDate[] days = windowDays(window);
        vo.setStartDay(days[0].toString());
        vo.setEndDay(days[1].toString());
        vo.setCoverDays((int) (ChronoUnit.DAYS.between(days[0], days[1]) + 1));
        vo.setReason(window.getReason());
        vo.setScopeType(window.getScopeType());
        vo.setScopeTypeName(Dict.outageScopeName(window.getScopeType()));
        vo.setScopeNote(window.getScopeNote());
        vo.setStatus(window.getStatus());
        vo.setStatusName(Dict.outageStatusName(window.getStatus()));
        vo.setStatusColor(Dict.outageStatusColor(window.getStatus()));
        vo.setCreatedBy(window.getCreatedBy());
        vo.setCreatedAt(CanalService.str(window.getCreatedAt()));
        vo.setUpdatedAt(CanalService.str(window.getUpdatedAt()));
        vo.setCanceledAt(CanalService.str(window.getCanceledAt()));
        vo.setCancelNote(window.getCancelNote());
        vo.setNote(window.getNote());
        return vo;
    }

    private void fillItemCounts(OutageWindowVO vo) {
        long pending = 0;
        long dealt = 0;
        long restored = 0;
        for (OutageAvoidItem item : itemRepository.findByWindowIdOrderByIdAsc(vo.getId())) {
            if ("PENDING".equals(item.getStatus())) {
                pending++;
            } else if ("RESTORED".equals(item.getStatus())) {
                restored++;
            } else {
                dealt++;
            }
        }
        vo.setPendingCount(pending);
        vo.setDealtCount(dealt);
        vo.setRestoredCount(restored);
    }

    private AvoidItemVO toItemVO(OutageAvoidItem item, OutageWindow window) {
        AvoidItemVO vo = new AvoidItemVO();
        vo.setId(item.getId());
        vo.setWindowId(item.getWindowId());
        vo.setWindowNo(window == null ? null : window.getWindowNo());
        vo.setWindowReason(window == null ? null : window.getReason());
        vo.setTargetType(item.getTargetType());
        vo.setTargetTypeName("PLAN".equals(item.getTargetType()) ? "灌溉计划" : "配水条");
        vo.setPlanId(item.getPlanId());
        IrrigationPlan plan = item.getPlanId() == null
                ? null : planRepository.findById(item.getPlanId()).orElse(null);
        vo.setPlanCode(plan == null ? null : plan.getPlanCode());
        vo.setPlanName(plan == null ? null : plan.getPlanName());
        vo.setAllotmentId(item.getAllotmentId());
        if (item.getAllotmentId() != null) {
            Allotment a = allotmentRepository.findById(item.getAllotmentId()).orElse(null);
            vo.setAllotDate(a == null ? null : CanalService.str(a.getAllotDate()));
            vo.setAllotFlow(a == null ? null : a.getFlow());
        }
        vo.setCanalSegmentId(item.getCanalSegmentId());
        CanalSegment segment = item.getCanalSegmentId() == null
                ? null : segmentRepository.findById(item.getCanalSegmentId()).orElse(null);
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setOrigStartDate(CanalService.str(item.getOrigStartDate()));
        vo.setOrigEndDate(CanalService.str(item.getOrigEndDate()));
        vo.setStatus(item.getStatus());
        vo.setStatusName(Dict.avoidStatusName(item.getStatus()));
        vo.setStatusColor(Dict.avoidStatusColor(item.getStatus()));
        vo.setResolutionNote(item.getResolutionNote());
        vo.setNewStartDate(CanalService.str(item.getNewStartDate()));
        vo.setNewEndDate(CanalService.str(item.getNewEndDate()));
        vo.setSplitPlanId(item.getSplitPlanId());
        if (item.getSplitPlanId() != null) {
            vo.setSplitPlanCode(planRepository.findById(item.getSplitPlanId())
                    .map(IrrigationPlan::getPlanCode).orElse(null));
        }
        vo.setOperator(item.getOperator());
        vo.setResolvedAt(CanalService.str(item.getResolvedAt()));
        vo.setCreatedAt(CanalService.str(item.getCreatedAt()));
        return vo;
    }
}
