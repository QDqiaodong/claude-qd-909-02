package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.WaterQuotaHold;
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
import java.util.Set;
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
 *   <li>申请水量 &gt; 0；待审不占额度，批准时在「渠段 + 供水日」原子预占，且不得超过当日剩余容量</li>
 *   <li>状态流转：待审 → 已批 → 已供水 / 已驳回，已供水与已驳回为终态</li>
 *   <li>驳回必须填理由</li>
 *   <li>欠费停供（ARREAR）用户不允许提交申请；停用渠段不允许申请</li>
 * </ol>
 */
@Service
public class WaterUserService {

    private final WaterUserRepository userRepository;
    private final WaterRequestRepository requestRepository;
    private final CanalSegmentRepository segmentRepository;
    private final CanalService canalService;
    private final QuotaService quotaService;

    public WaterUserService(WaterUserRepository userRepository, WaterRequestRepository requestRepository,
                            CanalSegmentRepository segmentRepository, CanalService canalService,
                            QuotaService quotaService) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.segmentRepository = segmentRepository;
        this.canalService = canalService;
        this.quotaService = quotaService;
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
        Map<Long, WaterQuotaHold> holds = quotaService.latestHoldMap(
                list.stream().map(WaterRequest::getId).collect(Collectors.toList()));
        return list.stream()
                .map(r -> toRequestVO(r, users.get(r.getWaterUserId()), segments.get(r.getCanalSegmentId()),
                        holds.get(r.getId())))
                .collect(Collectors.toList());
    }

    public WaterUser getUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BizException("用水户不存在：" + id));
    }

    public WaterRequest getRequest(Long id) {
        return requestRepository.findById(id).orElseThrow(() -> new BizException("用水申请不存在：" + id));
    }

    /** 额度预占 / 释放 / 入账审计流水。历史申请没有流水时返回空列表，不影响查看。 */
    public List<WaterQuotaHold> requestQuotaHolds(Long id) {
        getRequest(id);
        return quotaService.holdsFor(id);
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

    /** 按全局桶顺序锁定当前桶，再锁申请行；等待后重新锁刷新得到的新桶。 */
    private void lockRequestForMutation(WaterRequest req) {
        if (req.getAllotDate() == null || req.getCanalSegmentId() == null) {
            throw new BizException("用水申请缺少渠段或供水日期，无法核算额度");
        }
        quotaService.lockBuckets(Set.of(QuotaService.BucketKey.of(req.getCanalSegmentId(), req.getAllotDate())));
        quotaService.lockRequest(req);
        if (req.getAllotDate() == null || req.getCanalSegmentId() == null) {
            throw new BizException("用水申请缺少渠段或供水日期，无法核算额度");
        }
        quotaService.lockBuckets(Set.of(QuotaService.BucketKey.of(req.getCanalSegmentId(), req.getAllotDate())));
    }

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
        // 规则：不得超过该时段该渠段剩余可配水量额度（待审不预占，额度在批准时原子预占）
        // 提交时仅做信息提示，不预占；加桶锁保证读取的是当前已排配水 / 已批申请的一致快照
        quotaService.lockBucket(segmentId, request.getAllotDate());
        BigDecimal remain = quotaService.remainVolume(segmentId, request.getAllotDate());
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
        // 默认值写在 create 里
        req.setStatus("PENDING");
        req.setApplyTime(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /** 推进：待审 → 已批（原子预占）→ 已供水（预占转消耗审计） */
    @Transactional
    public WaterRequest advance(Long id, String approver) {
        WaterRequest req = getRequest(id);
        String operator = approver == null ? "灌区调度" : approver;
        // 「渠段 + 供水日」桶锁 + 申请行锁；并发批准在此串行复核
        lockRequestForMutation(req);
        if (req.isTerminal()) {
            throw new BizException("申请已" + Dict.requestStatusName(req.getStatus()) + "，为终态，不能继续推进");
        }
        if ("PENDING".equals(req.getStatus())) {
            quotaService.assertRequestFitsLocked(req.getCanalSegmentId(), req.getAllotDate(),
                    req.getRequestVolume(),
                    "该渠段当日剩余可配水量仅剩 %s 万m³，不足以批准本次 %s 万m³ 的申请");
            req.setStatus("APPROVED");
            req.setApprover(operator);
            req.setApproveTime(LocalDateTime.now());
            WaterRequest saved = requestRepository.save(req);
            quotaService.hold(saved, operator);
            return saved;
        }
        if ("APPROVED".equals(req.getStatus())) {
            canalService.assertCanAllot(req.getCanalSegmentId());
            req.setStatus("SUPPLIED");
            req.setSupplyTime(LocalDateTime.now());
            WaterRequest saved = requestRepository.save(req);
            quotaService.consume(saved);
            return saved;
        }
        throw new BizException("不支持的用水申请状态：" + req.getStatus());
    }

    /** 驳回：必须填理由；待审不占额度，已批驳回需先释放预占 */
    @Transactional
    public WaterRequest reject(Long id, String approver, String reason) {
        WaterRequest req = getRequest(id);
        String operator = approver == null ? "灌区调度" : approver;
        lockRequestForMutation(req);
        if (req.isTerminal()) {
            throw new BizException("申请已" + Dict.requestStatusName(req.getStatus()) + "，为终态，不能驳回");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("驳回必须填写理由");
        }
        boolean approved = "APPROVED".equals(req.getStatus());
        req.setStatus("REJECTED");
        req.setRejectReason(reason);
        req.setApprover(operator);
        req.setApproveTime(LocalDateTime.now());
        WaterRequest saved = requestRepository.save(req);
        if (approved) {
            quotaService.release(saved, operator, "已批申请驳回：" + reason);
        }
        return saved;
    }

    /** 退回待审（仅已批可退）：在桶锁内把 HELD 预占转为 RELEASED 审计 */
    @Transactional
    public WaterRequest backToPending(Long id) {
        WaterRequest req = getRequest(id);
        lockRequestForMutation(req);
        if (!"APPROVED".equals(req.getStatus())) {
            throw new BizException("只有已批（APPROVED）的申请可以退回待审，当前为 "
                    + Dict.requestStatusName(req.getStatus()));
        }
        req.setStatus("PENDING");
        req.setApprover(null);
        req.setApproveTime(null);
        WaterRequest saved = requestRepository.save(req);
        quotaService.release(saved, "灌区调度", "已批申请退回待审");
        return saved;
    }

    @Transactional
    public void removeRequest(Long id) {
        WaterRequest req = getRequest(id);
        if (req.isTerminal()) {
            throw new BizException("已供水 / 已驳回为终态审计记录，不能删除");
        }
        boolean wasApproved = "APPROVED".equals(req.getStatus());
        if (wasApproved) {
            lockRequestForMutation(req);
            if (!"APPROVED".equals(req.getStatus())) {
                throw new BizException("申请状态已变化，请刷新后重试");
            }
            quotaService.release(req, "灌区调度", "删除已批申请，释放预占");
        }
        requestRepository.delete(req);
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
        return toRequestVO(req, user, segment, null);
    }

    /**
     * @param hold 该申请最新一条额度流水；历史申请可为 null，剩余额度仍按申请状态实时计算。
     */
    public RequestVO toRequestVO(WaterRequest req, WaterUser user, CanalSegment segment, WaterQuotaHold hold) {
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
        BigDecimal dayCapacity = BigDecimal.ZERO;
        BigDecimal remain = BigDecimal.ZERO;
        try {
            dayCapacity = quotaService.dayCapacity(req.getCanalSegmentId());
            remain = quotaService.remainVolume(req.getCanalSegmentId(), req.getAllotDate());
        } catch (BizException ignored) {
            remain = BigDecimal.ZERO;
        }
        vo.setDayCapacity(dayCapacity);
        vo.setRemainVolume(remain);
        vo.setQuotaRate(dayCapacity.compareTo(BigDecimal.ZERO) <= 0
                ? 0D : Math.min(1D, CanalService.nvl(req.getRequestVolume()).doubleValue() / dayCapacity.doubleValue()));
        if (hold != null) {
            vo.setQuotaHoldId(hold.getId());
            vo.setQuotaHoldStatus(hold.getStatus());
            vo.setQuotaHeldBy(hold.getHeldBy());
            vo.setQuotaHeldAt(CanalService.str(hold.getHeldAt()));
            vo.setQuotaReleasedBy(hold.getReleasedBy());
            vo.setQuotaReleasedAt(CanalService.str(hold.getReleasedAt()));
            vo.setQuotaReleaseReason(hold.getReleaseReason());
            vo.setQuotaConsumedAt(CanalService.str(hold.getConsumedAt()));
        }
        return vo;
    }

    /** 该渠段该日剩余可配水量（前端申请卡额度条用） */
    public BigDecimal remainVolume(Long segmentId, LocalDate day) {
        return quotaService.remainVolume(segmentId, day);
    }

    /** 三列审批流的列定义 */
    public List<String> columns() {
        return new ArrayList<>(Dict.REQUEST_COLUMNS);
    }
}
