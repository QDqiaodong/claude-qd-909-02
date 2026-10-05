package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.domain.WaterUser;
import com.reservoir.irri.dto.Views.RequestVO;
import com.reservoir.irri.dto.Views.UserVO;
import com.reservoir.irri.dto.WaterRequestRequest;
import com.reservoir.irri.dto.WaterUserRequest;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import com.reservoir.irri.repository.WaterUserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块五 · 用水户与用水申请。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>农户编号唯一；灌溉面积 &gt; 0 且不得超过 5000 亩</li>
 *   <li>申请水量 &gt; 0，且不得超过该时段该渠段剩余可配水量额度（由配水模块算出）</li>
 *   <li>状态流转：待审 → 已批 → 已供水 / 已驳回，已供水与已驳回为终态</li>
 *   <li>驳回必须填理由</li>
 *   <li>欠费停供（ARREAR）用户不允许提交申请；停用渠段不允许申请</li>
 *   <li>额度预占：批准即按「渠段 + 用水日」预占额度（quota_reserved，记 reserved_at），
 *       退回待审 / 驳回已批时释放（记 released_at）；已供水终态保留预占与审计信息不再释放；
 *       待审与已驳回不占额度。审批在申请行锁 + 渠段行锁内核额，
 *       两个调度员并发审批时只有仍能容纳的申请才会通过</li>
 * </ol>
 */
@Service
public class WaterUserService {

    private final WaterUserRepository userRepository;
    private final WaterRequestRepository requestRepository;
    private final CanalSegmentRepository segmentRepository;
    private final CanalService canalService;
    private final PlanService planService;

    public WaterUserService(WaterUserRepository userRepository, WaterRequestRepository requestRepository,
                            CanalSegmentRepository segmentRepository, CanalService canalService,
                            PlanService planService) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.segmentRepository = segmentRepository;
        this.canalService = canalService;
        this.planService = planService;
    }

    // ---------------- 查询 ----------------

    public List<WaterUser> listUsers(Long segmentId) {
        if (segmentId == null) {
            return userRepository.findAllByOrderByUserCodeAsc();
        }
        return userRepository.findByCanalSegmentIdOrderByUserCodeAsc(segmentId);
    }

    public List<UserVO> listUserVO(Long segmentId) {
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        return listUsers(segmentId).stream()
                .map(u -> {
                    UserVO vo = toUserVO(u, segments.get(u.getCanalSegmentId()));
                    vo.setRequestCount(requestRepository.findByWaterUserIdOrderByIdDesc(u.getId()).size() * 1L);
                    return vo;
                })
                .collect(Collectors.toList());
    }

    /** 三列审批流：待审 / 已批 / 已供水（+ 已驳回） */
    public List<RequestVO> listRequestVO(Long segmentId) {
        List<WaterRequest> list = segmentId == null
                ? requestRepository.findAllOrdered()
                : requestRepository.findByCanalSegmentIdOrderByIdDesc(segmentId);
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        Map<Long, WaterUser> users = userRepository.findAll().stream()
                .collect(Collectors.toMap(WaterUser::getId, u -> u, (a, b) -> a));
        return list.stream()
                .map(r -> toRequestVO(r, users.get(r.getWaterUserId()), segments.get(r.getCanalSegmentId())))
                .collect(Collectors.toList());
    }

    public WaterUser getUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BizException("用水户不存在：" + id));
    }

    public WaterRequest getRequest(Long id) {
        return requestRepository.findById(id).orElseThrow(() -> new BizException("用水申请不存在：" + id));
    }

    // ---------------- 用水户 ----------------

    @Transactional
    public WaterUser createUser(WaterUserRequest request) {
        if (!StringUtils.hasText(request.getUserCode())) {
            throw new BizException("农户编号不能为空");
        }
        if (userRepository.existsByUserCode(request.getUserCode())) {
            throw new BizException("农户编号已存在：" + request.getUserCode());
        }
        // 规则：灌溉面积必须 > 0
        if (request.getIrrigatedAreaMu() == null || request.getIrrigatedAreaMu().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("灌溉面积必须大于 0（亩）");
        }
        if (request.getIrrigatedAreaMu().compareTo(Dict.MAX_IRRIGATED_AREA_MU) > 0) {
            throw new BizException("单个用水户灌溉面积不得超过 " + Dict.MAX_IRRIGATED_AREA_MU + " 亩");
        }
        if (request.getCropType() != null && !Dict.isValidCropType(request.getCropType())) {
            throw new BizException("作物类型只能为 水稻 / 冬小麦 / 玉米 / 棉花 / 蔬菜");
        }
        if (request.getCanalSegmentId() != null) {
            canalService.getSegment(request.getCanalSegmentId());
        }
        WaterUser user = new WaterUser();
        applyUser(user, request);
        // 默认值写在 create 里
        if (user.getStatus() == null) {
            user.setStatus("ACTIVE");
        }
        return userRepository.save(user);
    }

    @Transactional
    public WaterUser updateUser(Long id, WaterUserRequest request) {
        WaterUser user = getUser(id);
        if (StringUtils.hasText(request.getUserCode())
                && !request.getUserCode().equals(user.getUserCode())
                && userRepository.existsByUserCode(request.getUserCode())) {
            throw new BizException("农户编号已存在：" + request.getUserCode());
        }
        if (request.getIrrigatedAreaMu() != null && request.getIrrigatedAreaMu().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("灌溉面积必须大于 0（亩）");
        }
        if (request.getIrrigatedAreaMu() != null
                && request.getIrrigatedAreaMu().compareTo(Dict.MAX_IRRIGATED_AREA_MU) > 0) {
            throw new BizException("单个用水户灌溉面积不得超过 " + Dict.MAX_IRRIGATED_AREA_MU + " 亩");
        }
        if (request.getCropType() != null && !Dict.isValidCropType(request.getCropType())) {
            throw new BizException("作物类型只能为 水稻 / 冬小麦 / 玉米 / 棉花 / 蔬菜");
        }
        if (request.getStatus() != null && !Dict.isValidUserStatus(request.getStatus())) {
            throw new BizException("用水户状态只能为 在册 / 欠费停供 / 注销");
        }
        CanalService.ifNotNull(request.getUserCode(), user::setUserCode);
        CanalService.ifNotNull(request.getUserName(), user::setUserName);
        CanalService.ifNotNull(request.getVillage(), user::setVillage);
        CanalService.ifNotNull(request.getPhone(), user::setPhone);
        CanalService.ifNotNull(request.getIrrigatedAreaMu(), user::setIrrigatedAreaMu);
        CanalService.ifNotNull(request.getCropType(), user::setCropType);
        CanalService.ifNotNull(request.getCanalSegmentId(), user::setCanalSegmentId);
        CanalService.ifNotNull(request.getStatus(), user::setStatus);
        CanalService.ifNotNull(request.getNote(), user::setNote);
        return userRepository.save(user);
    }

    /** 新增路径的整体赋值；修改路径走上面的逐字段局部更新（为 null 的字段不改） */
    private void applyUser(WaterUser user, WaterUserRequest request) {
        user.setUserCode(request.getUserCode());
        user.setUserName(request.getUserName());
        user.setVillage(request.getVillage());
        user.setPhone(request.getPhone());
        user.setIrrigatedAreaMu(request.getIrrigatedAreaMu());
        user.setCropType(request.getCropType());
        user.setCanalSegmentId(request.getCanalSegmentId());
        user.setStatus(request.getStatus());
        user.setNote(request.getNote());
    }

    @Transactional
    public void removeUser(Long id) {
        WaterUser user = getUser(id);
        if (!requestRepository.findByWaterUserIdOrderByIdDesc(id).isEmpty()) {
            throw new BizException("该用水户已有用水申请记录，不允许删除");
        }
        userRepository.delete(user);
    }

    // ---------------- 用水申请 ----------------

    @Transactional
    public WaterRequest createRequest(WaterRequestRequest request) {
        if (!StringUtils.hasText(request.getRequestNo())) {
            throw new BizException("申请单号不能为空");
        }
        if (requestRepository.existsByRequestNo(request.getRequestNo())) {
            throw new BizException("申请单号已存在：" + request.getRequestNo());
        }
        if (request.getWaterUserId() == null) {
            throw new BizException("请选择申请人");
        }
        WaterUser user = getUser(request.getWaterUserId());
        // 规则：欠费停供 / 注销用户不允许提交申请
        if (user.isArrear()) {
            throw new BizException("用水户 " + user.getUserCode() + " 欠费停供，不允许提交用水申请");
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new BizException("用水户 " + user.getUserCode() + " 已注销，不允许提交用水申请");
        }
        Long segmentId = request.getCanalSegmentId() != null
                ? request.getCanalSegmentId() : user.getCanalSegmentId();
        if (segmentId == null) {
            throw new BizException("请选择取水渠段");
        }
        canalService.assertCanAllot(segmentId);
        if (request.getAllotDate() == null) {
            throw new BizException("请选择申请用水日期");
        }
        // 规则：申请水量必须 > 0
        if (request.getRequestVolume() == null || request.getRequestVolume().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("申请水量必须大于 0（万m³）");
        }
        if (request.getCropType() != null && !Dict.isValidCropType(request.getCropType())) {
            throw new BizException("作物类型只能为 水稻 / 冬小麦 / 玉米 / 棉花 / 蔬菜");
        }
        if (request.getUrgency() != null && !Dict.isValidUrgency(request.getUrgency())) {
            throw new BizException("紧急度只能为 常规 / 抗旱应急");
        }
        // 规则：不得超过该时段该渠段剩余可配水量额度
        BigDecimal remain = planService.remainVolume(segmentId, request.getAllotDate());
        if (request.getRequestVolume().compareTo(remain) > 0) {
            throw new BizException("申请水量 " + request.getRequestVolume() + " 万m³ 超过该渠段 "
                    + request.getAllotDate() + " 剩余可配水量 " + remain + " 万m³");
        }
        if (request.getApplyAreaMu() != null && user.getIrrigatedAreaMu() != null
                && request.getApplyAreaMu().compareTo(user.getIrrigatedAreaMu()) > 0) {
            throw new BizException("申请亩数 " + request.getApplyAreaMu() + " 亩超过该户在册灌溉面积 "
                    + user.getIrrigatedAreaMu() + " 亩");
        }

        WaterRequest req = new WaterRequest();
        req.setRequestNo(request.getRequestNo());
        req.setWaterUserId(user.getId());
        req.setCanalSegmentId(segmentId);
        req.setAllotDate(request.getAllotDate());
        req.setRequestVolume(request.getRequestVolume());
        req.setCropType(request.getCropType() == null ? user.getCropType() : request.getCropType());
        req.setApplyAreaMu(request.getApplyAreaMu() == null ? user.getIrrigatedAreaMu() : request.getApplyAreaMu());
        req.setUrgency(request.getUrgency() == null ? "NORMAL" : request.getUrgency());
        req.setNote(request.getNote());
        // 默认值写在 create 里：待审申请不占额度，批准时才形成预占
        req.setStatus("PENDING");
        req.setQuotaReserved(false);
        req.setApplyTime(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /** 推进：待审 → 已批 → 已供水 */
    @Transactional
    public WaterRequest advance(Long id, String approver) {
        // 锁申请行：两个调度员并发操作同一申请时串行，状态判断基于最新提交
        WaterRequest req = requestRepository.findForUpdate(id)
                .orElseThrow(() -> new BizException("用水申请不存在：" + id));
        if (req.isTerminal()) {
            throw new BizException("申请已" + Dict.requestStatusName(req.getStatus()) + "，为终态，不能继续推进");
        }
        if ("PENDING".equals(req.getStatus())) {
            // 锁渠段行：并发审批同一渠段同一供水日的申请在此串行；
            // 锁内重算剩余（已排配水 + 已批准预占），只有仍能容纳的申请才通过
            segmentRepository.findForUpdate(req.getCanalSegmentId())
                    .orElseThrow(() -> new BizException("渠段不存在：" + req.getCanalSegmentId()));
            BigDecimal remain = planService.remainVolume(req.getCanalSegmentId(), req.getAllotDate());
            if (req.getRequestVolume().compareTo(remain) > 0) {
                throw new BizException("该渠段 " + req.getAllotDate() + " 剩余可配水量仅剩 " + remain
                        + " 万m³，不足以批准本次 " + req.getRequestVolume() + " 万m³ 的申请");
            }
            req.setStatus("APPROVED");
            req.setApprover(approver == null ? "灌区调度" : approver);
            req.setApproveTime(LocalDateTime.now());
            // 批准即形成可追溯的额度预占
            req.setQuotaReserved(true);
            req.setReservedAt(LocalDateTime.now());
        } else if ("APPROVED".equals(req.getStatus())) {
            canalService.assertCanAllot(req.getCanalSegmentId());
            req.setStatus("SUPPLIED");
            req.setSupplyTime(LocalDateTime.now());
            // 已供水为终态：额度预占与审计信息保留不再释放；历史数据补记预占标记
            req.setQuotaReserved(true);
            if (req.getReservedAt() == null) {
                req.setReservedAt(req.getApproveTime() == null ? LocalDateTime.now() : req.getApproveTime());
            }
        }
        return requestRepository.save(req);
    }

    /** 驳回：必须填理由；驳回已批申请时一并释放其额度预占 */
    @Transactional
    public WaterRequest reject(Long id, String approver, String reason) {
        WaterRequest req = requestRepository.findForUpdate(id)
                .orElseThrow(() -> new BizException("用水申请不存在：" + id));
        if (req.isTerminal()) {
            throw new BizException("申请已" + Dict.requestStatusName(req.getStatus()) + "，为终态，不能驳回");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写理由");
        }
        releaseQuota(req);
        req.setStatus("REJECTED");
        req.setRejectReason(reason);
        req.setApprover(approver == null ? "灌区调度" : approver);
        req.setApproveTime(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /** 退回待审（仅已批可退）：释放额度预占 */
    @Transactional
    public WaterRequest backToPending(Long id) {
        WaterRequest req = requestRepository.findForUpdate(id)
                .orElseThrow(() -> new BizException("用水申请不存在：" + id));
        if (!"APPROVED".equals(req.getStatus())) {
            throw new BizException("只有已批（APPROVED）的申请可以退回待审，当前为 "
                    + Dict.requestStatusName(req.getStatus()));
        }
        releaseQuota(req);
        req.setStatus("PENDING");
        req.setApprover(null);
        req.setApproveTime(null);
        return requestRepository.save(req);
    }

    /**
     * 释放额度预占：仅当前持有预占（已批，含按状态占额度的历史数据）的申请需要释放；
     * 预占 / 释放时间保留为审计痕迹。已供水等终态不会走到这里，不会被重复释放。
     */
    private void releaseQuota(WaterRequest req) {
        if (req.isQuotaOccupying()) {
            req.setQuotaReserved(false);
            req.setReleasedAt(LocalDateTime.now());
        }
    }

    @Transactional
    public void removeRequest(Long id) {
        requestRepository.delete(getRequest(id));
    }

    // ---------------- VO ----------------

    public static UserVO toUserVO(WaterUser user, CanalSegment segment) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUserCode(user.getUserCode());
        vo.setUserName(user.getUserName());
        vo.setVillage(user.getVillage());
        vo.setPhone(user.getPhone());
        vo.setIrrigatedAreaMu(user.getIrrigatedAreaMu());
        vo.setCropType(user.getCropType());
        vo.setCropTypeName(Dict.cropTypeName(user.getCropType()));
        vo.setCropTypeColor(Dict.cropTypeColor(user.getCropType()));
        vo.setCanalSegmentId(user.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStatus(user.getStatus());
        vo.setStatusName(Dict.userStatusName(user.getStatus()));
        vo.setNote(user.getNote());
        vo.setArrear(Boolean.valueOf(user.isArrear()));
        return vo;
    }

    public RequestVO toRequestVO(WaterRequest req, WaterUser user, CanalSegment segment) {
        RequestVO vo = new RequestVO();
        vo.setId(req.getId());
        vo.setRequestNo(req.getRequestNo());
        vo.setWaterUserId(req.getWaterUserId());
        vo.setUserCode(user == null ? null : user.getUserCode());
        vo.setUserName(user == null ? null : user.getUserName());
        vo.setVillage(user == null ? null : user.getVillage());
        vo.setCanalSegmentId(req.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setAllotDate(CanalService.str(req.getAllotDate()));
        vo.setRequestVolume(req.getRequestVolume());
        vo.setCropType(req.getCropType());
        vo.setCropTypeName(Dict.cropTypeName(req.getCropType()));
        vo.setCropTypeColor(Dict.cropTypeColor(req.getCropType()));
        vo.setApplyAreaMu(req.getApplyAreaMu());
        vo.setUrgency(req.getUrgency());
        vo.setUrgencyName(Dict.urgencyName(req.getUrgency()));
        vo.setStatus(req.getStatus());
        vo.setStatusName(Dict.requestStatusName(req.getStatus()));
        vo.setStatusColor(Dict.requestStatusColor(req.getStatus()));
        vo.setRejectReason(req.getRejectReason());
        vo.setApprover(req.getApprover());
        vo.setApplyTime(CanalService.str(req.getApplyTime()));
        vo.setApproveTime(CanalService.str(req.getApproveTime()));
        vo.setSupplyTime(CanalService.str(req.getSupplyTime()));
        vo.setNote(req.getNote());
        // 额度预占痕迹：卡片上可追溯（历史数据无标记时按状态计入）
        vo.setQuotaReserved(Boolean.valueOf(req.isQuotaOccupying()));
        vo.setReservedAt(CanalService.str(req.getReservedAt()));
        vo.setReleasedAt(CanalService.str(req.getReleasedAt()));
        BigDecimal remain = BigDecimal.ZERO;
        try {
            remain = planService.remainVolume(req.getCanalSegmentId(), req.getAllotDate());
        } catch (BizException ignored) {
            remain = BigDecimal.ZERO;
        }
        vo.setRemainVolume(remain);
        vo.setQuotaRate(remain.compareTo(BigDecimal.ZERO) <= 0
                ? 0D : Math.min(1D, CanalService.nvl(req.getRequestVolume()).doubleValue() / remain.doubleValue()));
        return vo;
    }

    /** 该渠段该日剩余可配水量（前端申请卡额度条用） */
    public BigDecimal remainVolume(Long segmentId, LocalDate day) {
        return planService.remainVolume(segmentId, day);
    }

    /** 三列审批流的列定义 */
    public List<String> columns() {
        return new ArrayList<>(Dict.REQUEST_COLUMNS);
    }
}
