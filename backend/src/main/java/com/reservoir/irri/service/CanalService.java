package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.Canal;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.dto.CanalRequest;
import com.reservoir.irri.dto.CanalSegmentRequest;
import com.reservoir.irri.dto.Views.CanalVO;
import com.reservoir.irri.dto.Views.SegmentDetailVO;
import com.reservoir.irri.dto.Views.SegmentVO;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.GateRepository;
import com.reservoir.irri.repository.WaterLevelRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块一 · 渠系与渠段。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>渠道编号 / 渠段编号全灌区唯一；渠道等级只能是 干渠 / 支渠 / 斗渠</li>
 *   <li>设计流量 &gt; 0、渠道（渠段）长度 &gt; 0；渠段终点里程不得小于起点里程</li>
 *   <li>上下游渠段衔接校验：下游渠段起点里程不得小于上游渠段终点里程</li>
 *   <li>停用（DISABLED）渠道 / 渠段不允许配水</li>
 * </ol>
 */
@Service
public class CanalService {

    private final CanalRepository canalRepository;
    private final CanalSegmentRepository segmentRepository;
    private final GateRepository gateRepository;
    private final WaterLevelRepository waterLevelRepository;
    private final AllotmentRepository allotmentRepository;

    public CanalService(CanalRepository canalRepository, CanalSegmentRepository segmentRepository,
                        GateRepository gateRepository, WaterLevelRepository waterLevelRepository,
                        AllotmentRepository allotmentRepository) {
        this.canalRepository = canalRepository;
        this.segmentRepository = segmentRepository;
        this.gateRepository = gateRepository;
        this.waterLevelRepository = waterLevelRepository;
        this.allotmentRepository = allotmentRepository;
    }

    // ---------------- 查询 ----------------

    public List<Canal> listCanals() {
        return canalRepository.findAllByOrderByCanalCodeAsc();
    }

    public List<CanalSegment> listSegments(Long canalId) {
        if (canalId == null) {
            return segmentRepository.findAllByOrderByCanalIdAscSortNoAsc();
        }
        return segmentRepository.findByCanalIdOrderBySortNoAsc(canalId);
    }

    /** 纵向水系拓扑图：渠道（按等级分层）→ 其下渠段节点（按里程顺序） */
    public List<CanalVO> topology() {
        List<Canal> canals = canalRepository.findAllByOrderByCanalCodeAsc();
        List<CanalSegment> segments = segmentRepository.findAllByOrderByCanalIdAscSortNoAsc();
        Map<Long, List<CanalSegment>> byCanal = segments.stream()
                .collect(Collectors.groupingBy(CanalSegment::getCanalId));
        Map<Long, String> codeOf = segments.stream()
                .collect(Collectors.toMap(CanalSegment::getId, CanalSegment::getSegmentCode, (a, b) -> a));

        List<CanalVO> result = new ArrayList<>();
        for (Canal canal : canals) {
            CanalVO vo = toCanalVO(canal);
            List<CanalSegment> own = byCanal.getOrDefault(canal.getId(), new ArrayList<>());
            for (CanalSegment segment : own) {
                SegmentVO svo = toSegmentVO(segment, canal);
                svo.setUpstreamCode(segment.getUpstreamId() == null ? null : codeOf.get(segment.getUpstreamId()));
                svo.setGateCount((long) gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(segment.getId()).size());
                vo.getSegments().add(svo);
            }
            vo.setSegmentCount(own.size());
            result.add(vo);
        }
        return result;
    }

    /** 点拓扑图节点看渠段详情：闸门 + 近 10 条水位量测 + 未完成的配水安排 */
    public SegmentDetailVO segmentDetail(Long segmentId) {
        CanalSegment segment = getSegment(segmentId);
        Canal canal = canalRepository.findById(segment.getCanalId()).orElse(null);
        SegmentDetailVO vo = new SegmentDetailVO();
        vo.setSegment(toSegmentVO(segment, canal));
        vo.getGates().addAll(gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(segmentId).stream()
                .map(g -> GateService.toGateVO(g, segment)).collect(Collectors.toList()));
        vo.getLevels().addAll(waterLevelRepository.findBySegmentDesc(segmentId).stream()
                .limit(10).map(l -> LevelService.toLevelVO(l, segment)).collect(Collectors.toList()));
        vo.getAllotments().addAll(allotmentRepository.findByCanalSegmentIdOrderByAllotDateAsc(segmentId).stream()
                .filter(a -> !a.isCanceled())
                .map(a -> PlanService.toAllotmentVO(a, segment, null)).collect(Collectors.toList()));
        return vo;
    }

    public Canal getCanal(Long id) {
        return canalRepository.findById(id).orElseThrow(() -> new BizException("渠道不存在：" + id));
    }

    public CanalSegment getSegment(Long id) {
        return segmentRepository.findById(id).orElseThrow(() -> new BizException("渠段不存在：" + id));
    }

    // ---------------- 渠道 ----------------

    @Transactional
    public Canal createCanal(CanalRequest request) {
        if (!StringUtils.hasText(request.getCanalCode())) {
            throw new BizException("渠道编号不能为空");
        }
        if (!Dict.isValidCanalGrade(request.getCanalGrade())) {
            throw new BizException("渠道等级只能为 干渠 / 支渠 / 斗渠");
        }
        if (canalRepository.existsByCanalCode(request.getCanalCode())) {
            throw new BizException("渠道编号已存在：" + request.getCanalCode());
        }
        assertPositive(request.getDesignFlow(), "设计流量");
        assertPositive(request.getLengthKm(), "渠道长度");
        Canal canal = new Canal();
        applyCanal(canal, request);
        // 默认值写在 create 里，实体字段一律不带默认值
        if (canal.getStatus() == null) {
            canal.setStatus("ACTIVE");
        }
        if (canal.getSegmentCount() == null) {
            canal.setSegmentCount(0);
        }
        if (canal.getBuiltYear() == null) {
            canal.setBuiltYear(LocalDate.now().getYear());
        }
        return canalRepository.save(canal);
    }

    @Transactional
    public Canal updateCanal(Long id, CanalRequest request) {
        Canal canal = getCanal(id);
        if (StringUtils.hasText(request.getCanalCode())
                && !request.getCanalCode().equals(canal.getCanalCode())
                && canalRepository.existsByCanalCode(request.getCanalCode())) {
            throw new BizException("渠道编号已存在：" + request.getCanalCode());
        }
        if (request.getCanalGrade() != null && !Dict.isValidCanalGrade(request.getCanalGrade())) {
            throw new BizException("渠道等级只能为 干渠 / 支渠 / 斗渠");
        }
        assertPositive(request.getDesignFlow(), "设计流量");
        assertPositive(request.getLengthKm(), "渠道长度");
        if (request.getStatus() != null && !Dict.isValidCanalStatus(request.getStatus())) {
            throw new BizException("渠道状态只能为 通水 / 岁修 / 停用");
        }
        ifNotNull(request.getCanalCode(), canal::setCanalCode);
        ifNotNull(request.getCanalName(), canal::setCanalName);
        ifNotNull(request.getCanalGrade(), canal::setCanalGrade);
        ifNotNull(request.getSourceName(), canal::setSourceName);
        ifNotNull(request.getLengthKm(), canal::setLengthKm);
        ifNotNull(request.getDesignFlow(), canal::setDesignFlow);
        ifNotNull(request.getManager(), canal::setManager);
        ifNotNull(request.getBuiltYear(), canal::setBuiltYear);
        ifNotNull(request.getStatus(), canal::setStatus);
        ifNotNull(request.getNote(), canal::setNote);
        return canalRepository.save(canal);
    }

    @Transactional
    public Canal changeCanalStatus(Long id, String status) {
        if (!Dict.isValidCanalStatus(status)) {
            throw new BizException("渠道状态只能为 通水 / 岁修 / 停用：" + status);
        }
        Canal canal = getCanal(id);
        canal.setStatus(status);
        return canalRepository.save(canal);
    }

    @Transactional
    public void removeCanal(Long id) {
        Canal canal = getCanal(id);
        List<CanalSegment> segments = segmentRepository.findByCanalIdOrderBySortNoAsc(id);
        if (!segments.isEmpty()) {
            throw new BizException("渠道下仍有 " + segments.size() + " 个渠段，请先删除渠段");
        }
        canalRepository.delete(canal);
    }

    // ---------------- 渠段 ----------------

    @Transactional
    public CanalSegment createSegment(CanalSegmentRequest request) {
        if (request.getCanalId() == null) {
            throw new BizException("请选择渠段所属渠道");
        }
        Canal canal = getCanal(request.getCanalId());
        if (canal.isDisabled()) {
            throw new BizException("渠道 " + canal.getCanalCode() + " 已停用，不能在其下新增渠段");
        }
        if (!StringUtils.hasText(request.getSegmentCode())) {
            throw new BizException("渠段编号不能为空");
        }
        if (segmentRepository.existsBySegmentCode(request.getSegmentCode())) {
            throw new BizException("渠段编号已存在：" + request.getSegmentCode());
        }
        assertPositive(request.getDesignFlow(), "设计流量");
        assertPositive(request.getLengthKm(), "渠段长度");
        if (request.getLiningType() != null && !Dict.isValidLiningType(request.getLiningType())) {
            throw new BizException("衬砌型式只能为 混凝土衬砌 / 浆砌石 / 土渠 / 膜料防渗");
        }

        CanalSegment segment = new CanalSegment();
        applySegment(segment, request);
        // 默认值写在 create 里
        if (segment.getStatus() == null) {
            segment.setStatus("ACTIVE");
        }
        if (segment.getSortNo() == null) {
            segment.setSortNo(segmentRepository.findByCanalIdOrderBySortNoAsc(canal.getId()).size() + 1);
        }
        if (segment.getLengthKm() == null && segment.getStartMileage() != null && segment.getEndMileage() != null) {
            segment.setLengthKm(segment.getEndMileage().subtract(segment.getStartMileage())
                    .setScale(3, RoundingMode.HALF_UP));
        }
        assertMileage(segment);
        assertDesignLevel(segment);
        assertJoint(segment, false);

        CanalSegment saved = segmentRepository.save(segment);
        canal.setSegmentCount(nvl(canal.getSegmentCount()) + 1);
        canalRepository.save(canal);
        return saved;
    }

    @Transactional
    public CanalSegment updateSegment(Long id, CanalSegmentRequest request) {
        CanalSegment segment = getSegment(id);
        if (StringUtils.hasText(request.getSegmentCode())
                && !request.getSegmentCode().equals(segment.getSegmentCode())
                && segmentRepository.existsBySegmentCode(request.getSegmentCode())) {
            throw new BizException("渠段编号已存在：" + request.getSegmentCode());
        }
        assertPositive(request.getDesignFlow(), "设计流量");
        assertPositive(request.getLengthKm(), "渠段长度");
        if (request.getLiningType() != null && !Dict.isValidLiningType(request.getLiningType())) {
            throw new BizException("衬砌型式只能为 混凝土衬砌 / 浆砌石 / 土渠 / 膜料防渗");
        }
        if (request.getStatus() != null && !Dict.isValidCanalStatus(request.getStatus())) {
            throw new BizException("渠段状态只能为 通水 / 岁修 / 停用");
        }
        Long oldCanalId = segment.getCanalId();
        ifNotNull(request.getCanalId(), segment::setCanalId);
        ifNotNull(request.getSegmentCode(), segment::setSegmentCode);
        ifNotNull(request.getSegmentName(), segment::setSegmentName);
        ifNotNull(request.getSortNo(), segment::setSortNo);
        ifNotNull(request.getStartMileage(), segment::setStartMileage);
        ifNotNull(request.getEndMileage(), segment::setEndMileage);
        ifNotNull(request.getLengthKm(), segment::setLengthKm);
        ifNotNull(request.getDesignFlow(), segment::setDesignFlow);
        ifNotNull(request.getDesignLowLevel(), segment::setDesignLowLevel);
        ifNotNull(request.getDesignHighLevel(), segment::setDesignHighLevel);
        ifNotNull(request.getLiningType(), segment::setLiningType);
        ifNotNull(request.getUpstreamId(), segment::setUpstreamId);
        ifNotNull(request.getStatus(), segment::setStatus);
        ifNotNull(request.getNote(), segment::setNote);

        assertMileage(segment);
        assertDesignLevel(segment);
        assertJoint(segment, true);

        CanalSegment saved = segmentRepository.save(segment);
        if (request.getCanalId() != null && !request.getCanalId().equals(oldCanalId)) {
            Canal old = canalRepository.findById(oldCanalId).orElse(null);
            if (old != null) {
                old.setSegmentCount(Math.max(0, nvl(old.getSegmentCount()) - 1));
                canalRepository.save(old);
            }
            Canal now = canalRepository.findById(saved.getCanalId()).orElse(null);
            if (now != null) {
                now.setSegmentCount(nvl(now.getSegmentCount()) + 1);
                canalRepository.save(now);
            }
        }
        return saved;
    }

    @Transactional
    public CanalSegment changeSegmentStatus(Long id, String status) {
        if (!Dict.isValidCanalStatus(status)) {
            throw new BizException("渠段状态只能为 通水 / 岁修 / 停用：" + status);
        }
        CanalSegment segment = getSegment(id);
        segment.setStatus(status);
        return segmentRepository.save(segment);
    }

    @Transactional
    public void removeSegment(Long id) {
        CanalSegment segment = getSegment(id);
        for (CanalSegment brother : segmentRepository.findByCanalIdOrderBySortNoAsc(segment.getCanalId())) {
            if (id.equals(brother.getUpstreamId())) {
                throw new BizException("仍有下游渠段 " + brother.getSegmentCode() + " 以本渠段为上游，请先解除衔接");
            }
        }
        if (!gateRepository.findByCanalSegmentIdOrderByGateCodeAsc(id).isEmpty()) {
            throw new BizException("渠段下仍有闸门，请先删除闸门");
        }
        segmentRepository.delete(segment);
        Canal canal = canalRepository.findById(segment.getCanalId()).orElse(null);
        if (canal != null) {
            canal.setSegmentCount(Math.max(0, nvl(canal.getSegmentCount()) - 1));
            canalRepository.save(canal);
        }
    }

    // ---------------- 供其它模块复用的校验 ----------------

    /**
     * 配水前校验：渠段存在 → 渠段未停用 → 所属渠道未停用 / 未岁修。
     * 配水、闸门操作、用水申请创建前都走这里，保证「停用渠段不允许配水」在服务端生效。
     */
    public CanalSegment assertCanAllot(Long segmentId) {
        CanalSegment segment = getSegment(segmentId);
        if (segment.isDisabled()) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " 已停用，不允许配水");
        }
        Canal canal = canalRepository.findById(segment.getCanalId()).orElse(null);
        if (canal != null && canal.isDisabled()) {
            throw new BizException("渠道 " + canal.getCanalCode() + " 已停用，其下渠段不允许配水");
        }
        if (canal != null && "MAINTAIN".equals(canal.getStatus())) {
            throw new BizException("渠道 " + canal.getCanalCode() + " 岁修中，暂时不允许配水");
        }
        return segment;
    }

    /** 规则：终点里程不得小于起点里程 */
    private void assertMileage(CanalSegment segment) {
        if (segment.getStartMileage() != null && segment.getEndMileage() != null
                && segment.getEndMileage().compareTo(segment.getStartMileage()) < 0) {
            throw new BizException("渠段终点里程 " + segment.getEndMileage()
                    + " km 不得小于起点里程 " + segment.getStartMileage() + " km");
        }
    }

    /** 规则：设计最低水位不得高于设计最高水位 */
    private void assertDesignLevel(CanalSegment segment) {
        if (segment.getDesignLowLevel() != null && segment.getDesignHighLevel() != null
                && segment.getDesignLowLevel().compareTo(segment.getDesignHighLevel()) > 0) {
            throw new BizException("设计最低水位不得高于设计最高水位");
        }
    }

    /**
     * 规则：上下游渠段衔接校验——下游渠段起点里程不得小于上游渠段终点里程。
     * 指定了 upstreamId 时按它校验；未指定时按同一渠道内 sortNo 紧邻的前一段校验。
     */
    private void assertJoint(CanalSegment segment, boolean isUpdate) {
        if (segment.getStartMileage() == null || segment.getCanalId() == null) {
            return;
        }
        CanalSegment upstream = null;
        if (segment.getUpstreamId() != null) {
            upstream = segmentRepository.findById(segment.getUpstreamId())
                    .orElseThrow(() -> new BizException("指定的上游渠段不存在：" + segment.getUpstreamId()));
            if (!upstream.getCanalId().equals(segment.getCanalId())) {
                throw new BizException("上游渠段 " + upstream.getSegmentCode() + " 与本渠段不属于同一条渠道，不能衔接");
            }
        } else {
            CanalSegment prev = null;
            for (CanalSegment brother : segmentRepository.findByCanalIdOrderBySortNoAsc(segment.getCanalId())) {
                if (isUpdate && brother.getId().equals(segment.getId())) {
                    continue;
                }
                if (segment.getSortNo() != null && brother.getSortNo() != null
                        && brother.getSortNo() < segment.getSortNo()) {
                    prev = brother;
                }
            }
            upstream = prev;
        }
        if (upstream != null && upstream.getEndMileage() != null
                && segment.getStartMileage().compareTo(upstream.getEndMileage()) < 0) {
            throw new BizException("上下游衔接冲突：渠段 " + segment.getSegmentCode() + " 起点里程 "
                    + segment.getStartMileage() + " km 小于上游渠段 " + upstream.getSegmentCode()
                    + " 终点里程 " + upstream.getEndMileage() + " km");
        }
        // 反向：自己的终点不能盖过紧邻下游渠段的起点
        if (segment.getEndMileage() != null) {
            for (CanalSegment brother : segmentRepository.findByCanalIdOrderBySortNoAsc(segment.getCanalId())) {
                if (isUpdate && brother.getId().equals(segment.getId())) {
                    continue;
                }
                if (segment.getId() != null && segment.getId().equals(brother.getUpstreamId())
                        && brother.getStartMileage() != null
                        && brother.getStartMileage().compareTo(segment.getEndMileage()) < 0) {
                    throw new BizException("上下游衔接冲突：下游渠段 " + brother.getSegmentCode() + " 起点里程 "
                            + brother.getStartMileage() + " km 小于本渠段终点里程 " + segment.getEndMileage() + " km");
                }
            }
        }
    }

    private void applyCanal(Canal canal, CanalRequest request) {
        canal.setCanalCode(request.getCanalCode());
        canal.setCanalName(request.getCanalName());
        canal.setCanalGrade(request.getCanalGrade());
        canal.setSourceName(request.getSourceName());
        canal.setLengthKm(request.getLengthKm());
        canal.setDesignFlow(request.getDesignFlow());
        canal.setManager(request.getManager());
        canal.setBuiltYear(request.getBuiltYear());
        canal.setStatus(request.getStatus());
        canal.setNote(request.getNote());
    }

    private void applySegment(CanalSegment segment, CanalSegmentRequest request) {
        segment.setCanalId(request.getCanalId());
        segment.setSegmentCode(request.getSegmentCode());
        segment.setSegmentName(request.getSegmentName());
        segment.setSortNo(request.getSortNo());
        segment.setStartMileage(request.getStartMileage());
        segment.setEndMileage(request.getEndMileage());
        segment.setLengthKm(request.getLengthKm());
        segment.setDesignFlow(request.getDesignFlow());
        segment.setDesignLowLevel(request.getDesignLowLevel());
        segment.setDesignHighLevel(request.getDesignHighLevel());
        segment.setLiningType(request.getLiningType());
        segment.setUpstreamId(request.getUpstreamId());
        segment.setStatus(request.getStatus());
        segment.setNote(request.getNote());
    }

    // ---------------- VO ----------------

    public static CanalVO toCanalVO(Canal canal) {
        CanalVO vo = new CanalVO();
        vo.setId(canal.getId());
        vo.setCanalCode(canal.getCanalCode());
        vo.setCanalName(canal.getCanalName());
        vo.setCanalGrade(canal.getCanalGrade());
        vo.setCanalGradeName(Dict.canalGradeName(canal.getCanalGrade()));
        vo.setCanalGradeColor(Dict.canalGradeColor(canal.getCanalGrade()));
        vo.setCanalGradeStroke(Dict.canalGradeStroke(canal.getCanalGrade()));
        vo.setSourceName(canal.getSourceName());
        vo.setLengthKm(canal.getLengthKm());
        vo.setDesignFlow(canal.getDesignFlow());
        vo.setSegmentCount(nvl(canal.getSegmentCount()));
        vo.setManager(canal.getManager());
        vo.setBuiltYear(canal.getBuiltYear());
        vo.setStatus(canal.getStatus());
        vo.setStatusName(Dict.canalStatusName(canal.getStatus()));
        vo.setStatusColor(Dict.canalStatusColor(canal.getStatus()));
        vo.setNote(canal.getNote());
        vo.setDisabled(Boolean.valueOf(canal.isDisabled()));
        return vo;
    }

    public static SegmentVO toSegmentVO(CanalSegment segment, Canal canal) {
        SegmentVO vo = new SegmentVO();
        vo.setId(segment.getId());
        vo.setCanalId(segment.getCanalId());
        vo.setCanalCode(canal == null ? null : canal.getCanalCode());
        vo.setCanalName(canal == null ? null : canal.getCanalName());
        vo.setCanalGrade(canal == null ? null : canal.getCanalGrade());
        vo.setCanalGradeName(canal == null ? null : Dict.canalGradeName(canal.getCanalGrade()));
        vo.setCanalGradeColor(canal == null ? "#90a4ae" : Dict.canalGradeColor(canal.getCanalGrade()));
        vo.setCanalGradeStroke(canal == null ? 3 : Dict.canalGradeStroke(canal.getCanalGrade()));
        vo.setSegmentCode(segment.getSegmentCode());
        vo.setSegmentName(segment.getSegmentName());
        vo.setSortNo(segment.getSortNo());
        vo.setStartMileage(segment.getStartMileage());
        vo.setEndMileage(segment.getEndMileage());
        vo.setLengthKm(segment.getLengthKm());
        vo.setDesignFlow(segment.getDesignFlow());
        vo.setDesignLowLevel(segment.getDesignLowLevel());
        vo.setDesignHighLevel(segment.getDesignHighLevel());
        vo.setLiningType(segment.getLiningType());
        vo.setLiningTypeName(Dict.liningTypeName(segment.getLiningType()));
        vo.setUpstreamId(segment.getUpstreamId());
        vo.setStatus(segment.getStatus());
        vo.setStatusName(Dict.canalStatusName(segment.getStatus()));
        vo.setStatusColor(Dict.canalStatusColor(segment.getStatus()));
        vo.setNote(segment.getNote());
        vo.setDisabled(Boolean.valueOf(segment.isDisabled()));
        return vo;
    }

    // ---------------- 小工具 ----------------

    public static void assertPositive(BigDecimal value, String label) {
        if (value != null && value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(label + "必须大于 0，当前为 " + value);
        }
    }

    public static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    public static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    public static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static String nvl(String value) {
        return value == null ? "" : value;
    }

    public static String str(java.time.LocalDateTime time) {
        return time == null ? null : time.toString().replace('T', ' ');
    }

    public static String str(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
