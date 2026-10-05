package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.Gate;
import com.reservoir.irri.domain.GateOperation;
import com.reservoir.irri.dto.GateOperationRequest;
import com.reservoir.irri.dto.GateRequest;
import com.reservoir.irri.dto.Views.GateVO;
import com.reservoir.irri.dto.Views.OperationVO;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.GateOperationRepository;
import com.reservoir.irri.repository.GateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块二 · 闸门与开度操作。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>闸门编号全灌区唯一；闸门型式 4 选 1</li>
 *   <li>开度必须在 0~100（%）之间，安全开度下限不得高于上限</li>
 *   <li>单次调节幅度超过 30 个百分点必须走审批：状态置 PENDING，审批通过后才落开度</li>
 *   <li>操作记录时间不得早于该闸门上一次操作时间</li>
 *   <li>停用（DISABLED）闸门禁止操作</li>
 * </ol>
 */
@Service
public class GateService {

    private final GateRepository gateRepository;
    private final GateOperationRepository operationRepository;
    private final CanalSegmentRepository segmentRepository;
    private final CanalService canalService;

    public GateService(GateRepository gateRepository, GateOperationRepository operationRepository,
                       CanalSegmentRepository segmentRepository, CanalService canalService) {
        this.gateRepository = gateRepository;
        this.operationRepository = operationRepository;
        this.segmentRepository = segmentRepository;
        this.canalService = canalService;
    }

    // ---------------- 查询 ----------------

    public List<Gate> list(Long segmentId) {
        if (segmentId == null) {
            return gateRepository.findAllByOrderByGateCodeAsc();
        }
        return gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(segmentId);
    }

    /** 闸门面板：每个闸门带安全开度区、当前开度、待审批操作数 */
    public List<GateVO> panel(Long segmentId) {
        return list(segmentId).stream()
                .map(g -> toGateVO(g, segmentRepository.findById(g.getCanalSegmentId()).orElse(null)))
                .peek(vo -> {
                    long pending = operationRepository.findByGateIdOrderByOperateTimeDesc(vo.getId()).stream()
                            .filter(o -> "PENDING".equals(o.getStatus())).count();
                    vo.setPendingCount(pending);
                })
                .collect(Collectors.toList());
    }

    public List<OperationVO> operations(Long gateId) {
        List<GateOperation> list = gateId == null
                ? operationRepository.findAll()
                : operationRepository.findByGateIdOrderByOperateTimeDesc(gateId);
        return list.stream().map(this::toOperationVO).collect(Collectors.toList());
    }

    public Gate get(Long id) {
        return gateRepository.findById(id).orElseThrow(() -> new BizException("闸门不存在：" + id));
    }

    public GateOperation getOperation(Long id) {
        return operationRepository.findById(id).orElseThrow(() -> new BizException("闸门操作记录不存在：" + id));
    }

    // ---------------- 闸门 ----------------

    @Transactional
    public Gate create(GateRequest request) {
        if (request.getCanalSegmentId() == null) {
            throw new BizException("请选择闸门所属渠段");
        }
        CanalSegment segment = canalService.getSegment(request.getCanalSegmentId());
        if (!StringUtils.hasText(request.getGateCode())) {
            throw new BizException("闸门编号不能为空");
        }
        if (gateRepository.existsByGateCode(request.getGateCode())) {
            throw new BizException("闸门编号已存在：" + request.getGateCode());
        }
        if (!Dict.isValidGateType(request.getGateType())) {
            throw new BizException("闸门型式只能为 进水闸 / 节制闸 / 分水闸 / 退水闸");
        }
        assertOpening(request.getCurrentOpening(), "当前开度");
        assertSafeBand(request.getSafeMinOpening(), request.getSafeMaxOpening());
        Gate gate = new Gate();
        apply(gate, request);
        // 默认值写在 create 里
        if (gate.getStatus() == null) {
            gate.setStatus("ACTIVE");
        }
        if (gate.getCurrentOpening() == null) {
            gate.setCurrentOpening(BigDecimal.ZERO);
        }
        if (gate.getSafeMinOpening() == null) {
            gate.setSafeMinOpening(BigDecimal.valueOf(10));
        }
        if (gate.getSafeMaxOpening() == null) {
            gate.setSafeMaxOpening(BigDecimal.valueOf(85));
        }
        return gateRepository.save(gate);
    }

    @Transactional
    public Gate update(Long id, GateRequest request) {
        Gate gate = get(id);
        if (StringUtils.hasText(request.getGateCode())
                && !request.getGateCode().equals(gate.getGateCode())
                && gateRepository.existsByGateCode(request.getGateCode())) {
            throw new BizException("闸门编号已存在：" + request.getGateCode());
        }
        if (request.getCanalSegmentId() != null) {
            canalService.getSegment(request.getCanalSegmentId());
        }
        if (request.getGateType() != null && !Dict.isValidGateType(request.getGateType())) {
            throw new BizException("闸门型式只能为 进水闸 / 节制闸 / 分水闸 / 退水闸");
        }
        if (request.getStatus() != null && !Dict.isValidGateStatus(request.getStatus())) {
            throw new BizException("闸门状态只能为 运行 / 检修 / 停用");
        }
        assertOpening(request.getCurrentOpening(), "当前开度");
        assertSafeBand(request.getSafeMinOpening() == null ? gate.getSafeMinOpening() : request.getSafeMinOpening(),
                request.getSafeMaxOpening() == null ? gate.getSafeMaxOpening() : request.getSafeMaxOpening());
        CanalService.ifNotNull(request.getCanalSegmentId(), gate::setCanalSegmentId);
        CanalService.ifNotNull(request.getGateCode(), gate::setGateCode);
        CanalService.ifNotNull(request.getGateName(), gate::setGateName);
        CanalService.ifNotNull(request.getGateType(), gate::setGateType);
        CanalService.ifNotNull(request.getCurrentOpening(), gate::setCurrentOpening);
        CanalService.ifNotNull(request.getSafeMinOpening(), gate::setSafeMinOpening);
        CanalService.ifNotNull(request.getSafeMaxOpening(), gate::setSafeMaxOpening);
        CanalService.ifNotNull(request.getDesignFlow(), gate::setDesignFlow);
        CanalService.ifNotNull(request.getKeeper(), gate::setKeeper);
        CanalService.ifNotNull(request.getStatus(), gate::setStatus);
        CanalService.ifNotNull(request.getNote(), gate::setNote);
        return gateRepository.save(gate);
    }

    @Transactional
    public Gate changeStatus(Long id, String status) {
        if (!Dict.isValidGateStatus(status)) {
            throw new BizException("闸门状态只能为 运行 / 检修 / 停用：" + status);
        }
        Gate gate = get(id);
        gate.setStatus(status);
        return gateRepository.save(gate);
    }

    @Transactional
    public void remove(Long id) {
        Gate gate = get(id);
        if (!operationRepository.findByGateIdOrderByOperateTimeDesc(id).isEmpty()) {
            throw new BizException("闸门已有操作记录，不允许删除");
        }
        gateRepository.delete(gate);
    }

    // ---------------- 开度操作 ----------------

    /**
     * 调节开度。
     *
     * @param request   目标开度等信息
     * @param confirmed 前端对「超安全幅度」的二次确认；未确认且幅度 &gt; 30 时直接拒绝
     */
    @Transactional
    public GateOperation adjust(GateOperationRequest request, boolean confirmed) {
        if (request.getGateId() == null) {
            throw new BizException("请选择要操作的闸门");
        }
        Gate gate = get(request.getGateId());
        // 规则：停用闸门禁止操作
        if (gate.isDisabled()) {
            throw new BizException("闸门 " + gate.getGateCode() + " 已停用，禁止操作");
        }
        if ("MAINTAIN".equals(gate.getStatus())) {
            throw new BizException("闸门 " + gate.getGateCode() + " 检修中，暂时不允许操作");
        }
        // 规则：渠段停用不允许配水（闸门操作等同配水动作）
        canalService.assertCanAllot(gate.getCanalSegmentId());
        if (!StringUtils.hasText(request.getOperationNo())) {
            throw new BizException("操作单号不能为空");
        }
        if (operationRepository.existsByOperationNo(request.getOperationNo())) {
            throw new BizException("操作单号已存在：" + request.getOperationNo());
        }
        // 规则：开度必须在 0~100
        assertOpening(request.getAfterOpening(), "目标开度");

        BigDecimal before = CanalService.nvl(gate.getCurrentOpening());
        BigDecimal after = request.getAfterOpening();
        BigDecimal range = after.subtract(before).abs().setScale(2, RoundingMode.HALF_UP);
        if (range.compareTo(Dict.MAX_ADJUST_RANGE) > 0) {
            throw new BizException("单次调节幅度不得超过 " + Dict.MAX_ADJUST_RANGE + " 个百分点，当前为 " + range);
        }
        if (range.compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("目标开度与当前开度一致（" + before + "%），无需调节");
        }

        LocalDateTime operateTime = request.getOperateTime() == null ? LocalDateTime.now() : request.getOperateTime();
        // 规则：操作时间不得早于该闸门上一次操作时间
        if (gate.getLastOperateTime() != null && operateTime.isBefore(gate.getLastOperateTime())) {
            throw new BizException("操作时间 " + CanalService.str(operateTime) + " 早于该闸门上一次操作时间 "
                    + CanalService.str(gate.getLastOperateTime()));
        }
        if (operationRepository.countLaterThan(gate.getId(), operateTime) > 0) {
            throw new BizException("该闸门已存在晚于 " + CanalService.str(operateTime) + " 的操作记录，时间不能倒挂");
        }

        // 规则：单次调节幅度超过 30 个百分点必须走审批
        boolean needApprove = range.compareTo(Dict.APPROVE_THRESHOLD) > 0;
        if (needApprove && !confirmed) {
            throw new BizException("本次调节幅度 " + range + " 个百分点，超过 " + Dict.APPROVE_THRESHOLD
                    + " 个百分点，请勾选二次确认后提交（将进入待审批）");
        }

        GateOperation operation = new GateOperation();
        operation.setGateId(gate.getId());
        operation.setOperationNo(request.getOperationNo());
        operation.setBeforeOpening(before);
        operation.setAfterOpening(after);
        operation.setAdjustRange(range);
        operation.setOperateTime(operateTime);
        operation.setOperator(request.getOperator() == null ? "值班员" : request.getOperator());
        operation.setReason(request.getReason());
        operation.setNote(request.getNote());
        operation.setEmergencyOrderId(request.getEmergencyOrderId());
        operation.setStatus(needApprove ? "PENDING" : "EXECUTED");
        if (!needApprove) {
            operation.setApprover("自动放行");
            operation.setApproveTime(LocalDateTime.now());
            gate.setCurrentOpening(after);
            gate.setLastOperateTime(operateTime);
            gateRepository.save(gate);
        }
        return operationRepository.save(operation);
    }

    /** 审批通过：落开度、置已执行 */
    @Transactional
    public GateOperation approve(Long id, String approver) {
        GateOperation operation = getOperation(id);
        if (!"PENDING".equals(operation.getStatus())) {
            throw new BizException("只有待审批（PENDING）的操作才能审批，当前为 "
                    + Dict.operationStatusName(operation.getStatus()));
        }
        Gate gate = get(operation.getGateId());
        if (gate.isDisabled()) {
            throw new BizException("闸门 " + gate.getGateCode() + " 已停用，不能执行该操作");
        }
        // 审批时再校验一次时间倒挂，防止排队期间补录了更新的操作
        if (gate.getLastOperateTime() != null && operation.getOperateTime().isBefore(gate.getLastOperateTime())) {
            throw new BizException("操作时间早于该闸门上一次操作时间 " + CanalService.str(gate.getLastOperateTime())
                    + "，不能批准");
        }
        operation.setStatus("EXECUTED");
        operation.setApprover(approver == null ? "灌区调度" : approver);
        operation.setApproveTime(LocalDateTime.now());
        gate.setCurrentOpening(operation.getAfterOpening());
        gate.setLastOperateTime(operation.getOperateTime());
        gateRepository.save(gate);
        return operationRepository.save(operation);
    }

    /** 驳回：必须填理由 */
    @Transactional
    public GateOperation reject(Long id, String approver, String reason) {
        GateOperation operation = getOperation(id);
        if (!"PENDING".equals(operation.getStatus())) {
            throw new BizException("只有待审批（PENDING）的操作才能驳回，当前为 "
                    + Dict.operationStatusName(operation.getStatus()));
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写理由");
        }
        operation.setStatus("REJECTED");
        operation.setApprover(approver == null ? "灌区调度" : approver);
        operation.setApproveTime(LocalDateTime.now());
        operation.setNote(reason);
        return operationRepository.save(operation);
    }

    /** 规则：开度必须在 0~100 */
    private void assertOpening(BigDecimal opening, String label) {
        if (opening == null) {
            return;
        }
        if (opening.compareTo(Dict.MIN_OPENING) < 0 || opening.compareTo(Dict.MAX_OPENING) > 0) {
            throw new BizException(label + "必须在 " + Dict.MIN_OPENING + "~" + Dict.MAX_OPENING
                    + "（%）之间，当前为 " + opening);
        }
    }

    /** 规则：安全开度下限不得高于上限 */
    private void assertSafeBand(BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new BizException("安全开度下限 " + min + "% 不得高于上限 " + max + "%");
        }
    }

    private void apply(Gate gate, GateRequest request) {
        gate.setCanalSegmentId(request.getCanalSegmentId());
        gate.setGateCode(request.getGateCode());
        gate.setGateName(request.getGateName());
        gate.setGateType(request.getGateType());
        gate.setCurrentOpening(request.getCurrentOpening());
        gate.setSafeMinOpening(request.getSafeMinOpening());
        gate.setSafeMaxOpening(request.getSafeMaxOpening());
        gate.setDesignFlow(request.getDesignFlow());
        gate.setKeeper(request.getKeeper());
        gate.setStatus(request.getStatus());
        gate.setNote(request.getNote());
    }

    // ---------------- VO ----------------

    public static GateVO toGateVO(Gate gate, CanalSegment segment) {
        GateVO vo = new GateVO();
        vo.setId(gate.getId());
        vo.setCanalSegmentId(gate.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setGateCode(gate.getGateCode());
        vo.setGateName(gate.getGateName());
        vo.setGateType(gate.getGateType());
        vo.setGateTypeName(Dict.gateTypeName(gate.getGateType()));
        vo.setGateTypeColor(Dict.gateTypeColor(gate.getGateType()));
        vo.setCurrentOpening(gate.getCurrentOpening());
        vo.setSafeMinOpening(gate.getSafeMinOpening());
        vo.setSafeMaxOpening(gate.getSafeMaxOpening());
        vo.setDesignFlow(gate.getDesignFlow());
        vo.setKeeper(gate.getKeeper());
        vo.setLastOperateTime(CanalService.str(gate.getLastOperateTime()));
        vo.setStatus(gate.getStatus());
        vo.setStatusName(Dict.gateStatusName(gate.getStatus()));
        vo.setNote(gate.getNote());
        vo.setDisabled(Boolean.valueOf(gate.isDisabled()));
        vo.setInSafeBand(Boolean.valueOf(gate.isInSafeBand()));
        BigDecimal opening = CanalService.nvl(gate.getCurrentOpening());
        vo.setEstimateFlow(CanalService.nvl(gate.getDesignFlow())
                .multiply(opening)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        return vo;
    }

    public OperationVO toOperationVO(GateOperation operation) {
        Gate gate = gateRepository.findById(operation.getGateId()).orElse(null);
        OperationVO vo = new OperationVO();
        vo.setId(operation.getId());
        vo.setGateId(operation.getGateId());
        vo.setGateCode(gate == null ? null : gate.getGateCode());
        vo.setGateName(gate == null ? null : gate.getGateName());
        vo.setOperationNo(operation.getOperationNo());
        vo.setBeforeOpening(operation.getBeforeOpening());
        vo.setAfterOpening(operation.getAfterOpening());
        vo.setAdjustRange(operation.getAdjustRange());
        vo.setOperateTime(CanalService.str(operation.getOperateTime()));
        vo.setOperator(operation.getOperator());
        vo.setStatus(operation.getStatus());
        vo.setStatusName(Dict.operationStatusName(operation.getStatus()));
        vo.setStatusColor(Dict.operationStatusColor(operation.getStatus()));
        vo.setReason(operation.getReason());
        vo.setApprover(operation.getApprover());
        vo.setApproveTime(CanalService.str(operation.getApproveTime()));
        vo.setNote(operation.getNote());
        vo.setEmergencyOrderId(operation.getEmergencyOrderId());
        vo.setNeedApprove(Boolean.valueOf(CanalService.nvl(operation.getAdjustRange())
                .compareTo(Dict.APPROVE_THRESHOLD) > 0));
        return vo;
    }

    /** 首页概览用：待审批操作数 */
    public long pendingCount() {
        return operationRepository.countByStatus("PENDING");
    }

    /** 闸门选项（前端下拉用） */
    public List<GateVO> options() {
        List<GateVO> result = new ArrayList<>();
        for (Gate gate : gateRepository.findAllByOrderByGateCodeAsc()) {
            result.add(toGateVO(gate, segmentRepository.findById(gate.getCanalSegmentId()).orElse(null)));
        }
        return result;
    }

    /** 最近一次操作（时间倒序第一条） */
    public GateOperation latestOperation(Long gateId) {
        List<GateOperation> list = operationRepository.findLatestByGate(gateId, PageRequest.of(0, 1));
        return list.isEmpty() ? null : list.get(0);
    }
}
