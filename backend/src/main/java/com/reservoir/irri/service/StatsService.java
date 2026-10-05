package com.reservoir.irri.service;

import com.reservoir.irri.common.Dict;
import com.reservoir.irri.dto.CanalGradeStatDTO;
import com.reservoir.irri.dto.GateTypeStatDTO;
import com.reservoir.irri.dto.PlanStatusStatDTO;
import com.reservoir.irri.dto.RequestStatusStatDTO;
import com.reservoir.irri.dto.Views.OverviewVO;
import com.reservoir.irri.dto.Views.StatItemVO;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.WaterUser;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.GateOperationRepository;
import com.reservoir.irri.repository.GateRepository;
import com.reservoir.irri.repository.IrrigationPlanRepository;
import com.reservoir.irri.repository.WaterLevelRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import com.reservoir.irri.repository.WaterUserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 统计：顶部概览数字 + 四张分布（渠道等级 / 闸门型式 / 计划状态 / 申请状态） + 字典 */
@Service
public class StatsService {

    private final CanalRepository canalRepository;
    private final CanalSegmentRepository segmentRepository;
    private final GateRepository gateRepository;
    private final GateOperationRepository operationRepository;
    private final IrrigationPlanRepository planRepository;
    private final AllotmentRepository allotmentRepository;
    private final WaterLevelRepository levelRepository;
    private final WaterUserRepository userRepository;
    private final WaterRequestRepository requestRepository;

    public StatsService(CanalRepository canalRepository, CanalSegmentRepository segmentRepository,
                        GateRepository gateRepository, GateOperationRepository operationRepository,
                        IrrigationPlanRepository planRepository, AllotmentRepository allotmentRepository,
                        WaterLevelRepository levelRepository, WaterUserRepository userRepository,
                        WaterRequestRepository requestRepository) {
        this.canalRepository = canalRepository;
        this.segmentRepository = segmentRepository;
        this.gateRepository = gateRepository;
        this.operationRepository = operationRepository;
        this.planRepository = planRepository;
        this.allotmentRepository = allotmentRepository;
        this.levelRepository = levelRepository;
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
    }

    public OverviewVO overview() {
        BigDecimal designFlow = BigDecimal.ZERO;
        for (CanalSegment segment : segmentRepository.findAll()) {
            designFlow = designFlow.add(CanalService.nvl(segment.getDesignFlow()));
        }
        BigDecimal areaMu = BigDecimal.ZERO;
        for (WaterUser user : userRepository.findAll()) {
            areaMu = areaMu.add(CanalService.nvl(user.getIrrigatedAreaMu()));
        }
        OverviewVO vo = new OverviewVO();
        vo.setCanalCount(canalRepository.count());
        vo.setSegmentCount(segmentRepository.count());
        vo.setGateCount(gateRepository.count());
        vo.setOperationCount(operationRepository.count());
        vo.setPendingOperationCount(operationRepository.countByStatus("PENDING"));
        vo.setPlanCount(planRepository.count());
        vo.setAllotmentCount(allotmentRepository.count());
        vo.setLevelCount(levelRepository.count());
        vo.setAbnormalLevelCount(levelRepository.countAbnormal());
        vo.setUserCount(userRepository.count());
        vo.setRequestCount(requestRepository.count());
        vo.setPendingRequestCount(requestRepository.countByStatus("PENDING"));
        vo.setTotalDesignFlow(designFlow.setScale(2, RoundingMode.HALF_UP));
        vo.setTotalAreaMu(areaMu.setScale(2, RoundingMode.HALF_UP));
        return vo;
    }

    public List<CanalGradeStatDTO> canalGrade() {
        return canalRepository.statByCanalGrade();
    }

    public List<GateTypeStatDTO> gateType() {
        return gateRepository.statByGateType();
    }

    public List<PlanStatusStatDTO> planStatus() {
        return planRepository.statByStatus();
    }

    public List<RequestStatusStatDTO> requestStatus() {
        return requestRepository.statByStatus();
    }

    /** 渠段状态分布 */
    public List<StatItemVO> segmentStatus() {
        List<StatItemVO> result = new ArrayList<>();
        for (String status : List.of("ACTIVE", "MAINTAIN", "DISABLED")) {
            result.add(new StatItemVO(status, Dict.canalStatusName(status),
                    segmentRepository.countByStatus(status), Dict.canalStatusColor(status)));
        }
        return result;
    }

    /** 字典：一次性把所有下拉选项给前端 */
    public Map<String, Object> dict() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("canalGrade", options(List.of("TRUNK", "BRANCH", "LATERAL"), Dict::canalGradeName));
        map.put("canalStatus", options(List.of("ACTIVE", "MAINTAIN", "DISABLED"), Dict::canalStatusName));
        map.put("liningType", options(List.of("CONCRETE", "MASONRY", "EARTH", "FILM"), Dict::liningTypeName));
        map.put("gateType", options(List.of("INTAKE", "CONTROL", "DIVERSION", "DRAIN"), Dict::gateTypeName));
        map.put("gateStatus", options(List.of("ACTIVE", "MAINTAIN", "DISABLED"), Dict::gateStatusName));
        map.put("operation", options(List.of("PENDING", "APPROVED", "REJECTED", "EXECUTED"), Dict::operationStatusName));
        map.put("planStatus", options(List.of("DRAFT", "PENDING", "APPROVED", "RUNNING", "FINISHED"), Dict::planStatusName));
        map.put("allotStatus", options(List.of("PLANNED", "SUPPLYING", "DONE", "CANCELED"), Dict::allotStatusName));
        map.put("safetyGrade", options(List.of("STEADY", "NORMAL", "SURGE"), Dict::safetyGradeName));
        map.put("cropType", options(List.of("RICE", "WHEAT", "MAIZE", "COTTON", "VEGETABLE"), Dict::cropTypeName));
        map.put("userStatus", options(List.of("ACTIVE", "ARREAR", "DISABLED"), Dict::userStatusName));
        map.put("requestStatus", options(List.of("PENDING", "APPROVED", "SUPPLIED", "REJECTED"), Dict::requestStatusName));
        map.put("urgency", options(List.of("NORMAL", "URGENT"), Dict::urgencyName));
        return map;
    }

    private List<StatItemVO> options(List<String> keys, java.util.function.Function<String, String> nameOf) {
        List<StatItemVO> list = new ArrayList<>();
        for (String key : keys) {
            list.add(new StatItemVO(key, nameOf.apply(key), null, null));
        }
        return list;
    }
}
