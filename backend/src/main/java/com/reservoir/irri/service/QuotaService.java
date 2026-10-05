package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.domain.Allotment;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.QuotaBucket;
import com.reservoir.irri.domain.WaterQuotaHold;
import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.QuotaBucketRepository;
import com.reservoir.irri.repository.WaterQuotaHoldRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠段日额度与用水申请预占。
 *
 * <p>所有会改变「已排配水 / 已批申请」的事务必须先锁定 water_quota_bucket 中
 * 对应的「渠段 + 日期」行，再按最新数据核算，避免两个调度员同时通过时双花额度。
 */
@Service
public class QuotaService {

    private final CanalSegmentRepository segmentRepository;
    private final AllotmentRepository allotmentRepository;
    private final WaterRequestRepository requestRepository;
    private final WaterQuotaHoldRepository holdRepository;
    private final QuotaBucketRepository bucketRepository;
    private final EntityManager entityManager;

    public QuotaService(CanalSegmentRepository segmentRepository,
                        AllotmentRepository allotmentRepository,
                        WaterRequestRepository requestRepository,
                        WaterQuotaHoldRepository holdRepository,
                        QuotaBucketRepository bucketRepository,
                        EntityManager entityManager) {
        this.segmentRepository = segmentRepository;
        this.allotmentRepository = allotmentRepository;
        this.requestRepository = requestRepository;
        this.holdRepository = holdRepository;
        this.bucketRepository = bucketRepository;
        this.entityManager = entityManager;
    }

    // ---------------- 查询 ----------------

    public CanalSegment getSegment(Long segmentId) {
        return segmentRepository.findById(segmentId)
                .orElseThrow(() -> new BizException("渠段不存在：" + segmentId));
    }

    /** 已排配水折算的全日占用流量（m³/s），与配水设计流量校验保持同一口径。 */
    public BigDecimal usedFlow(Long segmentId, LocalDate day) {
        return CanalService.nvl(allotmentRepository.sumFlowBySegmentAndDate(segmentId, day))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 已占用水申请额度。以申请状态为准：APPROVED / SUPPLIED 计入，PENDING / REJECTED 不计入。
     * 这样历史申请即使没有新预占流水，也仍按现有状态正确计入。
     */
    public BigDecimal reservedVolume(Long segmentId, LocalDate day) {
        return CanalService.nvl(requestRepository.sumApprovedVolume(segmentId, day))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal reservedVolumeExcluding(Long segmentId, LocalDate day, List<Long> excludeIds) {
        List<Long> ids = excludeIds == null || excludeIds.isEmpty() ? List.of(-1L) : excludeIds;
        return CanalService.nvl(requestRepository.sumApprovedVolumeExcluding(segmentId, day, ids))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 该渠段该日按设计流量可供给的总水量（万m³）。 */
    public BigDecimal dayCapacity(Long segmentId) {
        return CanalService.nvl(getSegment(segmentId).getDesignFlow())
                .multiply(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 剩余可配水量 = 日容量 − 已排配水占用 − 已批 / 已供水申请预占。 */
    @Transactional(readOnly = true)
    public BigDecimal remainVolume(Long segmentId, LocalDate day) {
        BigDecimal capacity = dayCapacity(segmentId);
        BigDecimal allotted = usedFlow(segmentId, day)
                .multiply(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME);
        BigDecimal remain = capacity.subtract(allotted).subtract(reservedVolume(segmentId, day));
        return remain.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------- 行级锁 ----------------

    /** 锁定单个「渠段 + 日期」额度桶；调用方必须处于事务中。 */
    public QuotaBucket lockBucket(Long segmentId, LocalDate day) {
        if (segmentId == null || day == null) {
            throw new BizException("缺少渠段或供水日，无法核算额度");
        }
        bucketRepository.ensureBucket(segmentId, day);
        bucketRepository.flush();
        QuotaBucket bucket = bucketRepository.findForUpdate(segmentId, day);
        if (bucket == null) {
            throw new BizException("渠段日额度初始化失败：" + segmentId + " " + day);
        }
        return bucket;
    }

    /** 按固定顺序锁定多个额度桶，避免批量避让 / 改期时交叉等待形成死锁。 */
    public void lockBuckets(Set<BucketKey> keys) {
        List<BucketKey> sorted = keys.stream()
                .filter(k -> k.segmentId() != null && k.day() != null)
                .sorted(Comparator.comparing(BucketKey::segmentId).thenComparing(BucketKey::day))
                .toList();
        for (BucketKey key : sorted) {
            lockBucket(key.segmentId(), key.day());
        }
    }

    /** 桶锁之后再锁申请行，并刷新成等待期间的最新状态。 */
    public void lockRequest(WaterRequest request) {
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
    }

    /** 批量避让改期时锁申请，防止与审批 / 驳回 / 退回互相覆盖。 */
    public void lockRequests(List<WaterRequest> requests) {
        requests.stream()
                .sorted(Comparator.comparing(WaterRequest::getId))
                .forEach(this::lockRequest);
    }

    /** 桶锁之后再锁配水行，并刷新成等待期间的最新状态。 */
    public void lockAllotment(Allotment allotment) {
        entityManager.refresh(allotment, LockModeType.PESSIMISTIC_WRITE);
    }

    public void lockAllotments(List<Allotment> allotments) {
        allotments.stream()
                .sorted(Comparator.comparing(Allotment::getId))
                .forEach(this::lockAllotment);
    }

    // ---------------- 已持锁后的容量校验 ----------------

    /** 批准申请前校验。调用前必须已锁定该渠段该日桶。 */
    public void assertRequestFitsLocked(Long segmentId, LocalDate day, BigDecimal required, String message) {
        BigDecimal capacity = dayCapacity(segmentId);
        BigDecimal allotted = usedFlow(segmentId, day)
                .multiply(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME);
        BigDecimal reserved = reservedVolume(segmentId, day);
        if (allotted.add(reserved).add(CanalService.nvl(required)).compareTo(capacity) > 0) {
            BigDecimal remain = capacity.subtract(allotted).subtract(reserved)
                    .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            throw new BizException(message.formatted(remain, CanalService.nvl(required)));
        }
    }

    /**
     * 新增 / 修改 / 避让单条配水时校验：瞬时流量合计 + 已批申请预占折算流量不超设计流量。
     */
    public void assertAllotmentFitsLocked(CanalSegment segment, LocalDate day, BigDecimal newFlow,
                                          BigDecimal shiftedRequestVolume, Long selfId,
                                          List<Long> movingRequestIds) {
        if (segment == null || day == null || newFlow == null) {
            return;
        }
        BigDecimal design = CanalService.nvl(segment.getDesignFlow());
        if (design.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，不能排配水");
        }
        BigDecimal usedFlow = BigDecimal.ZERO;
        for (Allotment a : allotmentRepository.findActiveBySegmentAndDate(segment.getId(), day)) {
            if (selfId != null && selfId.equals(a.getId())) {
                continue;
            }
            usedFlow = usedFlow.add(CanalService.nvl(a.getFlow()));
        }
        BigDecimal reservedFlow = reservedVolumeExcluding(segment.getId(), day, movingRequestIds)
                .divide(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME, 4, RoundingMode.HALF_UP);
        BigDecimal shiftedFlow = CanalService.nvl(shiftedRequestVolume)
                .divide(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME, 4, RoundingMode.HALF_UP);
        BigDecimal total = usedFlow.add(CanalService.nvl(newFlow)).add(reservedFlow).add(shiftedFlow);
        if (total.compareTo(design) > 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " " + day + " 配水流量合计 "
                    + usedFlow.add(newFlow).setScale(2, RoundingMode.HALF_UP)
                    + " m³/s，另有已批申请预占折算 " + reservedFlow.add(shiftedFlow).setScale(2, RoundingMode.HALF_UP)
                    + " m³/s，超过设计流量 " + design + " m³/s");
        }
    }

    /** 批量改期预检：movingFlow 汇总本批次流量，movingRequestVolume 汇总随配水迁移的已批申请。 */
    public void assertBatchAllotmentFitsLocked(java.util.Map<BucketKey, BigDecimal> movingFlow,
                                               java.util.Map<BucketKey, BigDecimal> movingRequestVolume,
                                               List<Long> movingRequestIds,
                                               Set<Long> movingAllotmentIds) {
        movingFlow.forEach((key, batchFlow) -> {
            CanalSegment segment = getSegment(key.segmentId());
            BigDecimal design = CanalService.nvl(segment.getDesignFlow());
            if (design.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，不能避让到 " + key.day());
            }
            BigDecimal usedFlow = BigDecimal.ZERO;
            for (Allotment other : allotmentRepository.findActiveBySegmentAndDate(key.segmentId(), key.day())) {
                if (movingAllotmentIds.contains(other.getId())) {
                    continue;
                }
                usedFlow = usedFlow.add(CanalService.nvl(other.getFlow()));
            }
            BigDecimal reservedFlow = reservedVolumeExcluding(key.segmentId(), key.day(), movingRequestIds)
                    .divide(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME, 4, RoundingMode.HALF_UP);
            BigDecimal shiftedFlow = CanalService.nvl(movingRequestVolume.get(key))
                    .divide(com.reservoir.irri.common.Dict.DAY_FLOW_TO_VOLUME, 4, RoundingMode.HALF_UP);
            BigDecimal total = usedFlow.add(CanalService.nvl(batchFlow)).add(reservedFlow).add(shiftedFlow);
            if (total.compareTo(design) > 0) {
                throw new BizException("避让失败：渠段 " + segment.getSegmentCode() + " " + key.day()
                        + " 平移后配水流量 " + usedFlow.add(batchFlow).setScale(2, RoundingMode.HALF_UP)
                        + " m³/s，另有已批申请预占折算 " + reservedFlow.add(shiftedFlow).setScale(2, RoundingMode.HALF_UP)
                        + " m³/s，将超过设计流量 " + design + " m³/s，请改用拆分改期");
            }
        });
    }

    // ---------------- 预占流水 ----------------

    /** 待审批准通过：生成一条可追溯的 HELD 预占。 */
    @Transactional
    public WaterQuotaHold hold(WaterRequest request, String operator) {
        LocalDateTime now = LocalDateTime.now();
        WaterQuotaHold hold = new WaterQuotaHold();
        applyRequest(hold, request);
        hold.setStatus("HELD");
        hold.setHeldBy(operator);
        hold.setHeldAt(now);
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        return holdRepository.save(hold);
    }

    /** 已批退回待审 / 已批驳回：释放当前 HELD；历史已批申请无流水时补一条释放审计。 */
    @Transactional
    public WaterQuotaHold release(WaterRequest request, String operator, String reason) {
        WaterQuotaHold hold = holdRepository
                .findFirstByWaterRequestIdAndStatusOrderByIdDesc(request.getId(), "HELD").orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (hold == null) {
            hold = new WaterQuotaHold();
            applyRequest(hold, request);
            hold.setCreatedAt(now);
        }
        if ("RELEASED".equals(hold.getStatus())) {
            return hold;
        }
        hold.setStatus("RELEASED");
        hold.setReleasedBy(operator);
        hold.setReleasedAt(now);
        hold.setReleaseReason(reason);
        hold.setUpdatedAt(now);
        return holdRepository.save(hold);
    }

    /** 已批推进为已供水：预占转消耗并永久保留；历史已批无 HELD 时补 CONSUMED 审计。 */
    @Transactional
    public WaterQuotaHold consume(WaterRequest request) {
        WaterQuotaHold hold = holdRepository
                .findFirstByWaterRequestIdAndStatusOrderByIdDesc(request.getId(), "HELD").orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (hold == null) {
            hold = new WaterQuotaHold();
            applyRequest(hold, request);
            hold.setHeldBy(request.getApprover());
            hold.setHeldAt(request.getApproveTime() == null ? now : request.getApproveTime());
            hold.setCreatedAt(now);
        }
        hold.setStatus("CONSUMED");
        hold.setConsumedAt(now);
        hold.setUpdatedAt(now);
        return holdRepository.save(hold);
    }

    /** 停水避让改期时，把仍生效的预占同步到新的渠段 / 日期。 */
    @Transactional
    public void rebaseActiveHolds(List<WaterRequest> movedRequests, Long segmentId, LocalDate day) {
        if (movedRequests == null || movedRequests.isEmpty()) {
            return;
        }
        List<Long> ids = movedRequests.stream().map(WaterRequest::getId).toList();
        List<WaterQuotaHold> holds = holdRepository.findByWaterRequestIdInAndStatus(ids, "HELD");
        LocalDateTime now = LocalDateTime.now();
        for (WaterQuotaHold hold : holds) {
            hold.setCanalSegmentId(segmentId);
            hold.setAllotDate(day);
            hold.setUpdatedAt(now);
        }
        holdRepository.saveAll(holds);
    }

    public List<WaterQuotaHold> holdsFor(Long requestId) {
        return holdRepository.findByWaterRequestIdOrderByIdDesc(requestId);
    }

    public java.util.Map<Long, WaterQuotaHold> latestHoldMap(List<Long> requestIds) {
        java.util.Map<Long, WaterQuotaHold> result = new java.util.HashMap<>();
        if (requestIds.isEmpty()) {
            return result;
        }
        for (List<Long> partition : partition(requestIds, 900)) {
            for (WaterQuotaHold hold : holdRepository.findByWaterRequestIdInOrderByIdDesc(partition)) {
                result.putIfAbsent(hold.getWaterRequestId(), hold);
            }
        }
        return result;
    }

    private void applyRequest(WaterQuotaHold hold, WaterRequest request) {
        hold.setWaterRequestId(request.getId());
        hold.setRequestNo(request.getRequestNo());
        hold.setCanalSegmentId(request.getCanalSegmentId());
        hold.setAllotDate(request.getAllotDate());
        hold.setHoldVolume(CanalService.nvl(request.getRequestVolume()));
    }

    private List<List<Long>> partition(List<Long> source, int size) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            result.add(source.subList(i, Math.min(source.size(), i + size)));
        }
        return result;
    }

    public record BucketKey(Long segmentId, LocalDate day) {
        public static BucketKey of(Long segmentId, LocalDate day) {
            return new BucketKey(segmentId, day);
        }

        public static Set<BucketKey> of(CanalSegment segment, LocalDate day) {
            Set<BucketKey> keys = new LinkedHashSet<>();
            keys.add(new BucketKey(segment.getId(), day));
            return keys;
        }
    }
}
