package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.common.EmergencyStaleException;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.EmergencyEvent;
import com.reservoir.irri.domain.EmergencyGateSnapshot;
import com.reservoir.irri.domain.EmergencyLevelContext;
import com.reservoir.irri.domain.EmergencyOrder;
import com.reservoir.irri.domain.Gate;
import com.reservoir.irri.domain.GateOperation;
import com.reservoir.irri.domain.WaterLevel;
import com.reservoir.irri.dto.EmergencyRequest;
import com.reservoir.irri.dto.GateOperationRequest;
import com.reservoir.irri.dto.Views.EmergencyChangeVO;
import com.reservoir.irri.dto.Views.EmergencyContextVO;
import com.reservoir.irri.dto.Views.EmergencyDetailVO;
import com.reservoir.irri.dto.Views.EmergencyEventVO;
import com.reservoir.irri.dto.Views.EmergencyGateVO;
import com.reservoir.irri.dto.Views.EmergencyOrderVO;
import com.reservoir.irri.dto.Views.LevelVO;
import com.reservoir.irri.dto.Views.OperationVO;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.EmergencyEventRepository;
import com.reservoir.irri.repository.EmergencyGateSnapshotRepository;
import com.reservoir.irri.repository.EmergencyLevelContextRepository;
import com.reservoir.irri.repository.EmergencyOrderRepository;
import com.reservoir.irri.repository.GateOperationRepository;
import com.reservoir.irri.repository.GateRepository;
import com.reservoir.irri.repository.WaterLevelRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 异常水位险情处置台。
 *
 * <p>核心约束：
 * <ul>
 *   <li>同渠段、量测时段重叠的未结束处置单唯一；创建时锁渠段串行校验。</li>
 *   <li>待接管单只能由一名值班员接管；接管时锁处置单行，并记录接管人与时间。</li>
 *   <li>所有提交动作带 version；提交前比对最新水位、渠段和闸门快照，过期返回 409 和变化清单。</li>
 *   <li>闸门调整只按“当前”闸门运行状态、安全开度区间和审批规则执行，不使用创建时旧状态。</li>
 *   <li>时间线保留全部决定；历史普通水位和闸门记录没有处置单关联也照常可查。</li>
 * </ul>
 */
@Service
public class EmergencyService {

    private static final List<String> OPEN_STATUS = List.of("PENDING", "HANDLING", "REVIEW");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EmergencyOrderRepository orderRepository;
    private final EmergencyLevelContextRepository levelContextRepository;
    private final EmergencyGateSnapshotRepository gateSnapshotRepository;
    private final EmergencyEventRepository eventRepository;
    private final WaterLevelRepository waterLevelRepository;
    private final CanalSegmentRepository segmentRepository;
    private final GateRepository gateRepository;
    private final GateOperationRepository operationRepository;
    private final GateService gateService;

    public EmergencyService(EmergencyOrderRepository orderRepository,
                            EmergencyLevelContextRepository levelContextRepository,
                            EmergencyGateSnapshotRepository gateSnapshotRepository,
                            EmergencyEventRepository eventRepository,
                            WaterLevelRepository waterLevelRepository,
                            CanalSegmentRepository segmentRepository,
                            GateRepository gateRepository,
                            GateOperationRepository operationRepository,
                            GateService gateService) {
        this.orderRepository = orderRepository;
        this.levelContextRepository = levelContextRepository;
        this.gateSnapshotRepository = gateSnapshotRepository;
        this.eventRepository = eventRepository;
        this.waterLevelRepository = waterLevelRepository;
        this.segmentRepository = segmentRepository;
        this.gateRepository = gateRepository;
        this.operationRepository = operationRepository;
        this.gateService = gateService;
    }

    @Transactional(readOnly = true)
    public List<EmergencyOrderVO> list(Long segmentId) {
        List<EmergencyOrder> list = segmentId == null
                ? orderRepository.findAllByOrderByCreatedAtDescIdDesc()
                : orderRepository.findByCanalSegmentIdOrderByCreatedAtDescIdDesc(segmentId);
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        return list.stream().map(o -> toOrderVO(o, segments.get(o.getCanalSegmentId()))).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmergencyDetailVO detail(Long id) {
        return buildDetail(get(id), false);
    }

    @Transactional
    public EmergencyDetailVO create(EmergencyRequest request) {
        Long levelId = request.getWaterLevelId();
        if (levelId == null) {
            throw new BizException("请选择异常水位记录");
        }
        WaterLevel source = waterLevelRepository.findById(levelId)
                .orElseThrow(() -> new BizException("水位量测记录不存在：" + levelId));
        if (!source.isAbnormal()) {
            throw new BizException("只能从异常水位记录发起险情处置单");
        }
        if (source.getStartAt() == null || source.getEndAt() == null) {
            throw new BizException("异常水位记录缺少量测时段，不能发起处置单");
        }
        // 锁渠段：两个值班员同时点“发起处置”时，第二个会在同一把锁内看到第一张未结束单。
        CanalSegment segment = segmentRepository.findForUpdate(source.getCanalSegmentId())
                .orElseThrow(() -> new BizException("渠段不存在：" + source.getCanalSegmentId()));
        List<EmergencyOrder> overlaps = orderRepository.findOpenOverlap(
                segment.getId(), source.getStartAt(), source.getEndAt());
        if (!overlaps.isEmpty()) {
            EmergencyOrder exists = overlaps.get(0);
            throw new BizException("该渠段同一量测时段已有未结束处置单 " + exists.getOrderNo()
                    + "（状态：" + Dict.emergencyStatusName(exists.getStatus())
                    + (exists.getHandler() == null ? "，尚待接管" : "，接管人：" + exists.getHandler()) + "）");
        }

        LocalDateTime now = LocalDateTime.now();
        EmergencyOrder order = new EmergencyOrder();
        order.setOrderNo(uniqueOrderNo(now));
        order.setWaterLevelId(source.getId());
        order.setCanalSegmentId(segment.getId());
        order.setStationCode(source.getStationCode());
        order.setSourceWindowStartAt(source.getStartAt());
        order.setSourceWindowEndAt(source.getEndAt());
        order.setSourceMeasuredLevel(source.getMeasuredLevel());
        order.setSourceLowLevel(source.getBand() == null ? null : source.getBand().getLowLevel());
        order.setSourceHighLevel(source.getBand() == null ? null : source.getBand().getHighLevel());
        order.setSourceAbnormalReason(source.getAbnormalReason());
        order.setSourceSegmentStatus(segment.getStatus());
        order.setSourceSegmentName(segment.getSegmentName());
        order.setStatus("PENDING");
        order.setVersion(0);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);
        writeSnapshots(order, 0, now);
        addEvent(order, "CREATE", null, "PENDING", request.getCreator(),
                "由异常水位 " + source.getStationCode() + " 发起，实测 " + source.getMeasuredLevel()
                        + " m；" + CanalService.nvl(source.getAbnormalReason()), null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO take(Long id, EmergencyRequest request) {
        EmergencyOrder locked = orderRepository.findForUpdate(id)
                .orElseThrow(() -> new BizException("处置单不存在：" + id));
        // 取最新版本号，避免 A 已接管后 B 仍用旧页面覆盖。
        EmergencyOrder order = orderRepository.findById(locked.getId()).orElse(locked);
        if (!"PENDING".equals(order.getStatus())) {
            String handler = order.getHandler() == null ? "其他值班员" : order.getHandler();
            throw new BizException("处置单 " + order.getOrderNo() + " 已由 " + handler
                    + " 接管（当前状态：" + Dict.emergencyStatusName(order.getStatus()) + "），不能重复接管");
        }
        String operator = operator(request.getOperator(), "值班员");
        LocalDateTime now = LocalDateTime.now();
        order.setHandler(operator);
        order.setTakenOverAt(now);
        order.setStatus("HANDLING");
        order.setUpdatedAt(now);
        order = orderRepository.save(order);
        writeSnapshots(order, 1, now);
        addEvent(order, "TAKE", "PENDING", "HANDLING", operator,
                operator + " 接管处置单，并刷新接管时水位与闸门快照", null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO refresh(Long id, EmergencyRequest request) {
        EmergencyOrder order = get(id);
        assertVersion(order, request.getVersion());
        assertHandler(order, request.getOperator());
        int nextVersion = latestVersionNo(id) + 1;
        LocalDateTime now = LocalDateTime.now();
        order.setUpdatedAt(now);
        order = orderRepository.save(order);
        writeSnapshots(order, nextVersion, now);
        addEvent(order, "REFRESH", order.getStatus(), order.getStatus(), request.getOperator(),
                "主动刷新最新水位、渠段状态、关联闸门开度/状态和操作记录，已形成 v" + nextVersion + " 快照",
                null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO observe(Long id, EmergencyRequest request) {
        EmergencyOrder order = lockAndPrepare(id, request, "HANDLING");
        if (request.getNextReviewAt() == null) {
            throw new BizException("继续观测必须给出复查时限");
        }
        if (!request.getNextReviewAt().isAfter(LocalDateTime.now())) {
            throw new BizException("复查时限必须晚于当前时间");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new BizException("请填写继续观测意见");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setDecisionMode("OBSERVE");
        order.setNextReviewAt(request.getNextReviewAt());
        order.setDecisionComment(request.getComment().trim());
        order.setUpdatedAt(now);
        addEvent(order, "OBSERVE", "HANDLING", "HANDLING", request.getOperator(),
                "继续观测，复查时限 " + CanalService.str(request.getNextReviewAt())
                        + "；意见：" + request.getComment().trim(), null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO proposeGate(Long id, EmergencyRequest request) {
        EmergencyOrder order = lockAndPrepare(id, request, "HANDLING");
        if (request.getGateId() == null) {
            throw new BizException("请选择要调整的闸门");
        }
        Gate gate = gateRepository.findById(request.getGateId())
                .orElseThrow(() -> new BizException("闸门不存在：" + request.getGateId()));
        if (!Objects.equals(gate.getCanalSegmentId(), order.getCanalSegmentId())) {
            throw new BizException("只能调整本险情渠段上的关联闸门");
        }
        if (!"ACTIVE".equals(gate.getStatus())) {
            throw new BizException("闸门 " + gate.getGateCode() + " 当前为「"
                    + Dict.gateStatusName(gate.getStatus()) + "」，不能按处置单提出调整，请继续观测或转人工");
        }
        if (request.getAfterOpening() == null) {
            throw new BizException("请填写目标开度");
        }
        BigDecimal target = request.getAfterOpening();
        if (target.compareTo(gate.getSafeMinOpening()) < 0 || target.compareTo(gate.getSafeMaxOpening()) > 0) {
            throw new BizException("目标开度 " + target + "% 不在闸门当前安全开度区间 "
                    + gate.getSafeMinOpening() + "~" + gate.getSafeMaxOpening() + "% 内");
        }
        if (operationRepository.countByGateIdAndStatus(gate.getId(), "PENDING") > 0) {
            throw new BizException("闸门 " + gate.getGateCode() + " 已有待审批操作，请先处理后再提交险情调整");
        }
        BigDecimal before = CanalService.nvl(gate.getCurrentOpening());
        BigDecimal range = target.subtract(before).abs().setScale(2, RoundingMode.HALF_UP);
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("目标开度与当前开度一致（" + before + "%），无需调整");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new BizException("请填写闸门调整处置意见");
        }

        GateOperationRequest operationRequest = new GateOperationRequest();
        operationRequest.setGateId(gate.getId());
        operationRequest.setOperationNo(StringUtils.hasText(request.getOperationNo())
                ? request.getOperationNo() : "OP-EM-" + order.getId() + "-" + System.currentTimeMillis());
        operationRequest.setAfterOpening(target);
        operationRequest.setOperateTime(request.getOperateTime() == null ? LocalDateTime.now() : request.getOperateTime());
        operationRequest.setOperator(request.getOperator());
        operationRequest.setReason(StringUtils.hasText(request.getGateReason()) ? request.getGateReason()
                : "险情 " + order.getOrderNo() + " 闸门调整");
        operationRequest.setNote(request.getComment().trim());
        operationRequest.setEmergencyOrderId(order.getId());
        GateOperation operation = gateService.adjust(operationRequest, range.compareTo(Dict.APPROVE_THRESHOLD) > 0);

        int nextVersion = latestVersionNo(id) + 1;
        LocalDateTime now = LocalDateTime.now();
        writeSnapshots(order, nextVersion, now);
        order.setDecisionMode("GATE");
        order.setDecisionComment(request.getComment().trim());
        order.setLatestGateOperationId(operation.getId());
        order.setLatestGateOperationNo(operation.getOperationNo());
        order.setUpdatedAt(now);
        addEvent(order, "GATE_PROPOSE", "HANDLING", "HANDLING", request.getOperator(),
                "提出闸门 " + gate.getGateCode() + " 调整：" + before + "% → " + target
                        + "%（幅度 " + operation.getAdjustRange() + "），操作单 " + operation.getOperationNo()
                        + "，当前审批状态：" + Dict.operationStatusName(operation.getStatus()),
                gate.getId(), operation.getId());
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO withdrawGate(Long id, EmergencyRequest request) {
        EmergencyOrder order = get(id);
        assertVersion(order, request.getVersion());
        assertHandler(order, request.getOperator());
        if (!"HANDLING".equals(order.getStatus())) {
            throw new BizException("只有处置中的处置单才能撤回闸门调整");
        }
        if (order.getLatestGateOperationId() == null) {
            throw new BizException("当前处置单没有已提出的闸门调整");
        }
        GateOperation operation = operationRepository.findById(order.getLatestGateOperationId())
                .orElseThrow(() -> new BizException("关联闸门操作记录不存在"));
        if (!"PENDING".equals(operation.getStatus())) {
            throw new BizException("闸门操作 " + operation.getOperationNo() + " 已"
                    + Dict.operationStatusName(operation.getStatus()) + "，不能撤回；请继续观测、提交复核或转人工");
        }
        gateService.reject(operation.getId(), request.getOperator(),
                "险情处置单 " + order.getOrderNo() + " 值班员主动撤回：" + CanalService.nvl(request.getReason()));
        int nextVersion = latestVersionNo(id) + 1;
        LocalDateTime now = LocalDateTime.now();
        writeSnapshots(order, nextVersion, now);
        order.setDecisionMode(null);
        order.setUpdatedAt(now);
        addEvent(order, "GATE_WITHDRAW", "HANDLING", "HANDLING", request.getOperator(),
                "撤回闸门调整 " + operation.getOperationNo() + "；已保留填写内容，可刷新后改提观测或新方案",
                operation.getGateId(), operation.getId());
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO review(Long id, EmergencyRequest request) {
        EmergencyOrder order = lockAndPrepare(id, request, "HANDLING");
        if (!StringUtils.hasText(request.getComment())) {
            throw new BizException("请填写提交复核的处置意见");
        }
        assertNoPendingGate(order);
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("REVIEW");
        order.setDecisionComment(request.getComment().trim());
        order.setUpdatedAt(now);
        addEvent(order, "REVIEW", "HANDLING", "REVIEW", request.getOperator(),
                "提交复核：" + request.getComment().trim(), null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO resolve(Long id, EmergencyRequest request) {
        EmergencyOrder order = lockAndPrepare(id, request, "REVIEW");
        if (!StringUtils.hasText(request.getResolutionNote())) {
            throw new BizException("请填写复核解除意见");
        }
        assertNoPendingGate(order);
        WaterLevel latest = findLatestLevel(order.getCanalSegmentId());
        if (latest != null && latest.isAbnormal()) {
            throw new BizException("最新水位仍异常（" + latest.getMeasuredLevel() + " m，"
                    + CanalService.nvl(latest.getAbnormalReason()) + "），不能解除；请继续处置或转人工");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("RESOLVED");
        order.setResolver(request.getOperator());
        order.setResolvedAt(now);
        order.setResolutionNote(request.getResolutionNote().trim());
        order.setUpdatedAt(now);
        addEvent(order, "RESOLVE", "REVIEW", "RESOLVED", request.getOperator(),
                "复核解除：" + request.getResolutionNote().trim(), null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    @Transactional
    public EmergencyDetailVO manual(Long id, EmergencyRequest request) {
        EmergencyOrder order = get(id);
        assertVersion(order, request.getVersion());
        if (!Dict.isEmergencyOpen(order.getStatus())) {
            throw new BizException("处置单已结束，不能转人工");
        }
        if (order.getHandler() == null) {
            throw new BizException("处置单尚未接管，不能直接转人工");
        }
        if (!StringUtils.hasText(request.getOperator())) {
            throw new BizException("请选择当前值班人员");
        }
        if (!order.getHandler().equals(request.getOperator())) {
            throw new BizException("只有接管人 " + order.getHandler() + " 可以提交转人工");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("转人工必须填写原因");
        }
        LocalDateTime now = LocalDateTime.now();
        String from = order.getStatus();
        order.setStatus("MANUAL");
        order.setManualReason(request.getReason().trim());
        order.setUpdatedAt(now);
        addEvent(order, "MANUAL", from, "MANUAL", request.getOperator(),
                "转人工推进：" + request.getReason().trim(), null, null);
        orderRepository.saveAndFlush(order);
        return buildDetail(order, false);
    }

    // ---------------- 快照与核对 ----------------

    private EmergencyOrder lockAndPrepare(Long id, EmergencyRequest request, String requiredStatus) {
        EmergencyOrder order = get(id);
        assertVersion(order, request.getVersion());
        assertHandler(order, request.getOperator());
        if (!requiredStatus.equals(order.getStatus())) {
            throw new BizException("当前状态为「" + Dict.emergencyStatusName(order.getStatus())
                    + "」，不能执行该操作");
        }
        EmergencyDetailVO detail = buildDetail(order, true);
        if (Boolean.TRUE.equals(detail.getStale())) {
            throw new EmergencyStaleException(detail);
        }
        if (!Boolean.TRUE.equals(request.getFresh())) {
            throw new BizException("提交前请先核对最新水位与闸门状态，并确认使用刷新后的快照");
        }
        return order;
    }

    private void writeSnapshots(EmergencyOrder order, int versionNo, LocalDateTime now) {
        CanalSegment segment = segmentRepository.findById(order.getCanalSegmentId()).orElse(null);
        WaterLevel latest = findLatestLevel(order.getCanalSegmentId());
        WaterLevel source = waterLevelRepository.findById(order.getWaterLevelId()).orElse(null);
        // v0 必须固化发起记录本身；接管/刷新版本才记录“当前最新”后续水位。
        WaterLevel contextLevel = versionNo == 0 ? source : (latest == null ? source : latest);

        EmergencyLevelContext lctx = new EmergencyLevelContext();
        lctx.setEmergencyOrderId(order.getId());
        lctx.setVersionNo(versionNo);
        lctx.setWaterLevelId(contextLevel == null ? order.getWaterLevelId() : contextLevel.getId());
        lctx.setMeasuredLevel(contextLevel == null ? null : contextLevel.getMeasuredLevel());
        lctx.setAbnormal(contextLevel == null ? null : contextLevel.getAbnormal());
        lctx.setAbnormalReason(contextLevel == null ? null : contextLevel.getAbnormalReason());
        lctx.setSegmentStatus(segment == null ? null : segment.getStatus());
        lctx.setSegmentName(segment == null ? null : segment.getSegmentName());
        lctx.setDesignLowLevel(segment == null ? null : segment.getDesignLowLevel());
        lctx.setDesignHighLevel(segment == null ? null : segment.getDesignHighLevel());
        lctx.setCreatedAt(now);
        levelContextRepository.save(lctx);

        List<Gate> gates = gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(order.getCanalSegmentId());
        for (Gate gate : gates) {
            GateOperation latestOp = gateService.latestOperation(gate.getId());
            EmergencyGateSnapshot snap = new EmergencyGateSnapshot();
            snap.setEmergencyOrderId(order.getId());
            snap.setGateId(gate.getId());
            snap.setVersionNo(versionNo);
            snap.setGateCode(gate.getGateCode());
            snap.setGateName(gate.getGateName());
            snap.setGateType(gate.getGateType());
            snap.setCurrentOpening(gate.getCurrentOpening());
            snap.setSafeMinOpening(gate.getSafeMinOpening());
            snap.setSafeMaxOpening(gate.getSafeMaxOpening());
            snap.setStatus(gate.getStatus());
            snap.setKeeper(gate.getKeeper());
            snap.setLastOperateTime(gate.getLastOperateTime());
            if (latestOp != null) {
                snap.setLatestOperationId(latestOp.getId());
                snap.setLatestOperationNo(latestOp.getOperationNo());
                snap.setLatestOperationStatus(latestOp.getStatus());
            }
            snap.setCreatedAt(now);
            gateSnapshotRepository.save(snap);
        }
    }

    private EmergencyDetailVO buildDetail(EmergencyOrder order, boolean forStaleCheck) {
        CanalSegment segment = segmentRepository.findById(order.getCanalSegmentId()).orElse(null);
        EmergencyDetailVO detail = new EmergencyDetailVO();
        detail.setOrder(toOrderVO(order, segment));

        List<EmergencyLevelContext> contexts =
                levelContextRepository.findByEmergencyOrderIdOrderByVersionNoAscIdAsc(order.getId());
        if (!contexts.isEmpty()) {
            detail.setSource(toContextVO(contexts.get(0)));
            detail.setLatest(toContextVO(contexts.get(contexts.size() - 1)));
        }

        int latestVersionNo = contexts.stream().map(EmergencyLevelContext::getVersionNo)
                .max(Integer::compareTo).orElse(0);
        List<EmergencyGateSnapshot> snapshots = gateSnapshotRepository
                .findByEmergencyOrderIdAndVersionNoOrderByGateCodeAscIdAsc(order.getId(), latestVersionNo);
        List<Gate> currentGates = gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(order.getCanalSegmentId());
        Map<Long, Gate> currentGateMap = currentGates.stream()
                .collect(Collectors.toMap(Gate::getId, g -> g, (a, b) -> a));
        List<EmergencyGateVO> gateVOs = new ArrayList<>();
        for (EmergencyGateSnapshot snapshot : snapshots) {
            gateVOs.add(toGateVO(snapshot, currentGateMap.get(snapshot.getGateId())));
        }
        detail.setSnapshotGates(gateVOs);
        detail.setChanges(buildChanges(order, contexts, snapshots, currentGates));
        detail.setStale(!detail.getChanges().isEmpty());

        detail.setTimeline(eventRepository.findByEmergencyOrderIdOrderByEventTimeAscIdAsc(order.getId())
                .stream().map(this::toEventVO).collect(Collectors.toList()));
        detail.setFollowingLevels(buildFollowingLevels(order, segment));
        detail.setGateOperations(buildFollowingOperations(order, currentGates));
        return detail;
    }

    private List<EmergencyChangeVO> buildChanges(EmergencyOrder order,
                                                 List<EmergencyLevelContext> contexts,
                                                 List<EmergencyGateSnapshot> snapshots,
                                                 List<Gate> currentGates) {
        List<EmergencyChangeVO> changes = new ArrayList<>();
        EmergencyLevelContext context = contexts.isEmpty() ? null : contexts.get(contexts.size() - 1);
        CanalSegment segment = segmentRepository.findById(order.getCanalSegmentId()).orElse(null);
        WaterLevel latestLevel = findLatestLevel(order.getCanalSegmentId());

        if (context != null) {
            Long snapshotLevelId = context.getWaterLevelId();
            WaterLevel snapshotLevel = waterLevelRepository.findById(snapshotLevelId).orElse(null);
            if (latestLevel != null && snapshotLevel != null && !Objects.equals(snapshotLevelId, latestLevel.getId())
                    && latestLevel.getStartAt() != null
                    && latestLevel.getStartAt().isAfter(snapshotLevel.getStartAt())) {
                changes.add(change("LEVEL", "出现后续水位记录",
                        "#" + snapshotLevelId + " " + CanalService.nvl(context.getMeasuredLevel()) + " m",
                        "#" + latestLevel.getId() + " " + CanalService.nvl(latestLevel.getMeasuredLevel()) + " m"
                                + (latestLevel.isAbnormal() ? "（仍异常）" : "（已恢复）"),
                        "后续量测可能改变险情判断，提交前必须重新核对"));
            } else if (latestLevel != null) {
                if (!Objects.equals(context.getMeasuredLevel(), latestLevel.getMeasuredLevel())
                        || !Objects.equals(context.getAbnormal(), latestLevel.getAbnormal())
                        || !Objects.equals(CanalService.nvl(context.getAbnormalReason()),
                                CanalService.nvl(latestLevel.getAbnormalReason()))) {
                    changes.add(change("LEVEL", "原水位记录已更新",
                            CanalService.nvl(context.getMeasuredLevel()) + " m / "
                                    + CanalService.nvl(context.getAbnormalReason()),
                            CanalService.nvl(latestLevel.getMeasuredLevel()) + " m / "
                                    + CanalService.nvl(latestLevel.getAbnormalReason()),
                            "水位记录在快照后发生变化"));
                }
            }
            if (segment != null && !Objects.equals(context.getSegmentStatus(), segment.getStatus())) {
                changes.add(change("SEGMENT", "渠段状态变化",
                        Dict.canalStatusName(context.getSegmentStatus()),
                        Dict.canalStatusName(segment.getStatus()),
                        "渠段进入" + Dict.canalStatusName(segment.getStatus()) + "状态"));
            }
        }

        Map<Long, EmergencyGateSnapshot> snapMap = snapshots.stream()
                .collect(Collectors.toMap(EmergencyGateSnapshot::getGateId, s -> s, (a, b) -> a));
        for (Gate gate : currentGates) {
            EmergencyGateSnapshot snapshot = snapMap.get(gate.getId());
            if (snapshot == null) {
                changes.add(change("GATE_NEW", "新增关联闸门", "无",
                        gate.getGateCode() + " " + Dict.gateStatusName(gate.getStatus()),
                        "快照后该渠段新增闸门，调整方案需重新评估"));
                continue;
            }
            if (!Objects.equals(snapshot.getStatus(), gate.getStatus())) {
                changes.add(change("GATE_STATUS", "闸门运行状态变化：" + gate.getGateCode(),
                        Dict.gateStatusName(snapshot.getStatus()), Dict.gateStatusName(gate.getStatus()),
                        "不能依据创建/接管时的可用状态直接调闸"));
            }
            if (!Objects.equals(snapshot.getCurrentOpening(), gate.getCurrentOpening())) {
                changes.add(change("GATE_OPENING", "闸门当前开度变化：" + gate.getGateCode(),
                        snapshot.getCurrentOpening() + "%", gate.getCurrentOpening() + "%",
                        "出现开度变化，需重新计算调节幅度"));
            }
            if (!Objects.equals(snapshot.getSafeMinOpening(), gate.getSafeMinOpening())
                    || !Objects.equals(snapshot.getSafeMaxOpening(), gate.getSafeMaxOpening())) {
                changes.add(change("GATE_SAFE_BAND", "安全开度区间变化：" + gate.getGateCode(),
                        snapshot.getSafeMinOpening() + "~" + snapshot.getSafeMaxOpening() + "%",
                        gate.getSafeMinOpening() + "~" + gate.getSafeMaxOpening() + "%",
                        "目标开度必须按当前安全区间重新校验"));
            }
            GateOperation op = gateService.latestOperation(gate.getId());
            Long currentOpId = op == null ? null : op.getId();
            if (!Objects.equals(snapshot.getLatestOperationId(), currentOpId)) {
                changes.add(change("GATE_OPERATION", "闸门出现新操作记录：" + gate.getGateCode(),
                        snapshot.getLatestOperationNo() == null ? "无"
                                : snapshot.getLatestOperationNo() + "（"
                                + Dict.operationStatusName(snapshot.getLatestOperationStatus()) + "）",
                        currentOpId == null ? "无"
                                : op.getOperationNo() + "（" + Dict.operationStatusName(op.getStatus()) + "）",
                        "需确认该操作对险情水位的影响"));
            } else if (op != null && !Objects.equals(snapshot.getLatestOperationStatus(), op.getStatus())) {
                changes.add(change("GATE_OPERATION_STATUS", "闸门审批状态变化：" + gate.getGateCode(),
                        Dict.operationStatusName(snapshot.getLatestOperationStatus()),
                        Dict.operationStatusName(op.getStatus()),
                        "操作单 " + op.getOperationNo() + " 状态已更新"));
            }
        }
        for (EmergencyGateSnapshot snapshot : snapshots) {
            if (!currentGateMapContains(currentGates, snapshot.getGateId())) {
                changes.add(change("GATE_REMOVED", "关联闸门移除：" + snapshot.getGateCode(),
                        Dict.gateStatusName(snapshot.getStatus()), "当前不存在",
                        "原调整对象已不存在"));
            }
        }
        return changes;
    }

    private boolean currentGateMapContains(List<Gate> gates, Long gateId) {
        return gates.stream().anyMatch(g -> Objects.equals(g.getId(), gateId));
    }

    private EmergencyGateVO toGateVO(EmergencyGateSnapshot snapshot, Gate gate) {
        EmergencyGateVO vo = new EmergencyGateVO();
        vo.setGateId(snapshot.getGateId());
        vo.setVersionNo(snapshot.getVersionNo());
        vo.setGateCode(snapshot.getGateCode());
        vo.setGateName(snapshot.getGateName());
        vo.setGateType(snapshot.getGateType());
        vo.setGateTypeName(Dict.gateTypeName(snapshot.getGateType()));
        vo.setSnapshotOpening(snapshot.getCurrentOpening());
        vo.setSafeMinOpening(snapshot.getSafeMinOpening());
        vo.setSafeMaxOpening(snapshot.getSafeMaxOpening());
        vo.setSnapshotStatus(snapshot.getStatus());
        vo.setSnapshotStatusName(Dict.gateStatusName(snapshot.getStatus()));
        vo.setSnapshotLastOperateTime(CanalService.str(snapshot.getLastOperateTime()));
        vo.setSnapshotOperationId(snapshot.getLatestOperationId());
        vo.setSnapshotOperationNo(snapshot.getLatestOperationNo());
        vo.setSnapshotOperationStatus(snapshot.getLatestOperationStatus());
        if (gate != null) {
            vo.setCurrentOpening(gate.getCurrentOpening());
            vo.setSafeMinOpening(gate.getSafeMinOpening());
            vo.setSafeMaxOpening(gate.getSafeMaxOpening());
            vo.setCurrentStatus(gate.getStatus());
            vo.setCurrentStatusName(Dict.gateStatusName(gate.getStatus()));
            vo.setCurrentLastOperateTime(CanalService.str(gate.getLastOperateTime()));
            GateOperation op = gateService.latestOperation(gate.getId());
            if (op != null) {
                vo.setCurrentOperationId(op.getId());
                vo.setCurrentOperationNo(op.getOperationNo());
                vo.setCurrentOperationStatus(op.getStatus());
            }
            vo.setOperable("ACTIVE".equals(gate.getStatus()));
            if (!"ACTIVE".equals(gate.getStatus())) {
                vo.getWarnings().add("闸门当前" + Dict.gateStatusName(gate.getStatus()) + "，禁止调整");
            }
            if (operationRepository.countByGateIdAndStatus(gate.getId(), "PENDING") > 0) {
                vo.getWarnings().add("存在待审批闸门操作");
            }
            vo.setChanged(!Objects.equals(snapshot.getStatus(), gate.getStatus())
                    || !Objects.equals(snapshot.getCurrentOpening(), gate.getCurrentOpening())
                    || !Objects.equals(snapshot.getSafeMinOpening(), gate.getSafeMinOpening())
                    || !Objects.equals(snapshot.getSafeMaxOpening(), gate.getSafeMaxOpening())
                    || !Objects.equals(snapshot.getLatestOperationId(),
                            opId(gateService.latestOperation(gate.getId()))));
        } else {
            vo.getWarnings().add("闸门当前不存在");
            vo.setChanged(true);
        }
        return vo;
    }

    private Long opId(GateOperation op) {
        return op == null ? null : op.getId();
    }

    private List<LevelVO> buildFollowingLevels(EmergencyOrder order, CanalSegment segment) {
        return waterLevelRepository.findBySegmentDesc(order.getCanalSegmentId()).stream()
                .filter(l -> l.getStartAt() != null
                        && !l.getStartAt().isBefore(order.getSourceWindowStartAt()))
                .sorted(Comparator.comparing(WaterLevel::getStartAt).thenComparing(WaterLevel::getId))
                .map(l -> LevelService.toLevelVO(l, segment))
                .collect(Collectors.toList());
    }

    private List<OperationVO> buildFollowingOperations(EmergencyOrder order, List<Gate> gates) {
        List<GateOperation> result = new ArrayList<>();
        for (Gate gate : gates) {
            for (GateOperation op : operationRepository.findByGateIdOrderByOperateTimeDesc(gate.getId())) {
                boolean related = Objects.equals(op.getEmergencyOrderId(), order.getId());
                boolean afterSource = op.getOperateTime() != null
                        && !op.getOperateTime().isBefore(order.getSourceWindowStartAt());
                if (related || afterSource) {
                    result.add(op);
                }
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(GateOperation::getOperateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(GateOperation::getId))
                .map(gateService::toOperationVO)
                .collect(Collectors.toList());
    }

    private EmergencyContextVO toContextVO(EmergencyLevelContext context) {
        EmergencyContextVO vo = new EmergencyContextVO();
        vo.setVersionNo(context.getVersionNo());
        vo.setWaterLevelId(context.getWaterLevelId());
        vo.setMeasuredLevel(context.getMeasuredLevel());
        vo.setAbnormal(context.getAbnormal());
        vo.setAbnormalReason(context.getAbnormalReason());
        vo.setSegmentStatus(context.getSegmentStatus());
        vo.setSegmentStatusName(Dict.canalStatusName(context.getSegmentStatus()));
        vo.setSegmentName(context.getSegmentName());
        vo.setDesignLowLevel(context.getDesignLowLevel());
        vo.setDesignHighLevel(context.getDesignHighLevel());
        vo.setCreatedAt(CanalService.str(context.getCreatedAt()));
        return vo;
    }

    private EmergencyEventVO toEventVO(EmergencyEvent event) {
        EmergencyEventVO vo = new EmergencyEventVO();
        vo.setId(event.getId());
        vo.setType(event.getEventType());
        vo.setTypeName(Dict.emergencyEventName(event.getEventType()));
        vo.setFromStatus(event.getFromStatus());
        vo.setFromStatusName(Dict.emergencyStatusName(event.getFromStatus()));
        vo.setToStatus(event.getToStatus());
        vo.setToStatusName(Dict.emergencyStatusName(event.getToStatus()));
        vo.setOperator(event.getOperator());
        vo.setEventTime(CanalService.str(event.getEventTime()));
        vo.setDetail(event.getDetail());
        vo.setGateId(event.getGateId());
        vo.setGateOperationId(event.getGateOperationId());
        if (event.getGateId() != null) {
            gateRepository.findById(event.getGateId()).ifPresent(g -> vo.setGateCode(g.getGateCode()));
        }
        if (event.getGateOperationId() != null) {
            operationRepository.findById(event.getGateOperationId())
                    .ifPresent(op -> vo.setGateOperationNo(op.getOperationNo()));
        }
        return vo;
    }

    private EmergencyOrderVO toOrderVO(EmergencyOrder order, CanalSegment segment) {
        EmergencyOrderVO vo = new EmergencyOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setWaterLevelId(order.getWaterLevelId());
        vo.setCanalSegmentId(order.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStationCode(order.getStationCode());
        vo.setSourceWindowStart(CanalService.str(order.getSourceWindowStartAt()));
        vo.setSourceWindowEnd(CanalService.str(order.getSourceWindowEndAt()));
        vo.setSourceMeasuredLevel(order.getSourceMeasuredLevel());
        vo.setSourceLowLevel(order.getSourceLowLevel());
        vo.setSourceHighLevel(order.getSourceHighLevel());
        vo.setSourceAbnormalReason(order.getSourceAbnormalReason());
        vo.setSourceSegmentStatus(order.getSourceSegmentStatus());
        vo.setSourceSegmentStatusName(Dict.canalStatusName(order.getSourceSegmentStatus()));
        vo.setStatus(order.getStatus());
        vo.setStatusName(Dict.emergencyStatusName(order.getStatus()));
        vo.setStatusColor(Dict.emergencyStatusColor(order.getStatus()));
        vo.setHandler(order.getHandler());
        vo.setTakenOverAt(CanalService.str(order.getTakenOverAt()));
        vo.setNextReviewAt(CanalService.str(order.getNextReviewAt()));
        vo.setDecisionMode(order.getDecisionMode());
        vo.setDecisionModeName(Dict.emergencyDecisionName(order.getDecisionMode()));
        vo.setDecisionComment(order.getDecisionComment());
        vo.setLatestGateOperationNo(order.getLatestGateOperationNo());
        if (order.getLatestGateOperationId() != null) {
            operationRepository.findById(order.getLatestGateOperationId())
                    .ifPresent(op -> vo.setLatestGateOperationStatus(op.getStatus()));
        }
        vo.setResolvedAt(CanalService.str(order.getResolvedAt()));
        vo.setResolver(order.getResolver());
        vo.setResolutionNote(order.getResolutionNote());
        vo.setManualReason(order.getManualReason());
        vo.setVersion(order.getVersion());
        vo.setCreatedAt(CanalService.str(order.getCreatedAt()));
        vo.setUpdatedAt(CanalService.str(order.getUpdatedAt()));
        vo.setOpen(Dict.isEmergencyOpen(order.getStatus()));
        vo.setReviewDue(order.getNextReviewAt() != null
                && Dict.isEmergencyOpen(order.getStatus())
                && !order.getNextReviewAt().isAfter(LocalDateTime.now()));
        return vo;
    }

    private EmergencyChangeVO change(String type, String label, String snapshotValue,
                                     String currentValue, String detail) {
        EmergencyChangeVO vo = new EmergencyChangeVO();
        vo.setType(type);
        vo.setLabel(label);
        vo.setSnapshotValue(snapshotValue);
        vo.setCurrentValue(currentValue);
        vo.setDetail(detail);
        return vo;
    }

    private void addEvent(EmergencyOrder order, String type, String from, String to,
                          String operator, String detail, Long gateId, Long operationId) {
        EmergencyEvent event = new EmergencyEvent();
        event.setEmergencyOrderId(order.getId());
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperator(operator);
        event.setEventTime(LocalDateTime.now());
        event.setDetail(detail);
        event.setGateId(gateId);
        event.setGateOperationId(operationId);
        eventRepository.save(event);
    }

    private WaterLevel findLatestLevel(Long segmentId) {
        return waterLevelRepository.findBySegmentDesc(segmentId).stream()
                .filter(l -> l.getStartAt() != null)
                .max(Comparator.comparing(WaterLevel::getStartAt).thenComparing(WaterLevel::getId))
                .orElse(null);
    }

    private int latestVersionNo(Long orderId) {
        return levelContextRepository.findFirstByEmergencyOrderIdOrderByVersionNoDescIdDesc(orderId)
                .map(EmergencyLevelContext::getVersionNo).orElse(0);
    }

    private EmergencyOrder get(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("处置单不存在：" + id));
    }

    private void assertVersion(EmergencyOrder order, Integer version) {
        if (version == null || !order.getVersion().equals(version)) {
            throw new BizException("处置单状态已被其他人更新，请刷新后查看最新接管人和时间线（本地版本 "
                    + version + "，当前版本 " + order.getVersion() + "）");
        }
    }

    private void assertHandler(EmergencyOrder order, String operator) {
        if (order.getHandler() == null) {
            throw new BizException("处置单尚未接管，请先接管");
        }
        if (!StringUtils.hasText(operator)) {
            throw new BizException("请选择当前值班人员");
        }
        if (!order.getHandler().equals(operator.trim())) {
            throw new BizException("处置单已由 " + order.getHandler() + " 接管，当前值班员不能操作");
        }
    }

    private void assertNoPendingGate(EmergencyOrder order) {
        List<String> pending = new ArrayList<>();
        for (Gate gate : gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(order.getCanalSegmentId())) {
            if (operationRepository.countByGateIdAndStatus(gate.getId(), "PENDING") > 0) {
                pending.add(gate.getGateCode());
            }
        }
        if (!pending.isEmpty()) {
            throw new BizException("闸门 " + String.join("、", pending) + " 仍有待审批操作，不能提交复核/解除");
        }
    }

    private String uniqueOrderNo(LocalDateTime now) {
        String base = "EM-" + now.format(NO_FMT);
        String candidate = base;
        int suffix = 1;
        while (orderRepository.existsByOrderNo(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private String operator(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    /** 给首页/统计预留的未结束处置单数 */
    @Transactional(readOnly = true)
    public long openCount() {
        return orderRepository.findByStatusIn(OPEN_STATUS).size();
    }

    /** 待接管险情数 */
    @Transactional(readOnly = true)
    public long pendingTakeCount() {
        return orderRepository.findByStatusIn(List.of("PENDING")).size();
    }
}
