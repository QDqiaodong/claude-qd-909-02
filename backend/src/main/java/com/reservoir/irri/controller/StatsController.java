package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.dto.CanalGradeStatDTO;
import com.reservoir.irri.dto.GateTypeStatDTO;
import com.reservoir.irri.dto.PlanStatusStatDTO;
import com.reservoir.irri.dto.RequestStatusStatDTO;
import com.reservoir.irri.dto.Views.OverviewVO;
import com.reservoir.irri.dto.Views.StatItemVO;
import com.reservoir.irri.service.StatsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 统计：概览数字 + 四张分布 + 字典 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<OverviewVO> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/canal-grade")
    public ApiResponse<List<CanalGradeStatDTO>> canalGrade() {
        return ApiResponse.success(service.canalGrade());
    }

    @GetMapping("/gate-type")
    public ApiResponse<List<GateTypeStatDTO>> gateType() {
        return ApiResponse.success(service.gateType());
    }

    @GetMapping("/plan-status")
    public ApiResponse<List<PlanStatusStatDTO>> planStatus() {
        return ApiResponse.success(service.planStatus());
    }

    @GetMapping("/request-status")
    public ApiResponse<List<RequestStatusStatDTO>> requestStatus() {
        return ApiResponse.success(service.requestStatus());
    }

    @GetMapping("/segment-status")
    public ApiResponse<List<StatItemVO>> segmentStatus() {
        return ApiResponse.success(service.segmentStatus());
    }

    @GetMapping("/dict")
    public ApiResponse<Map<String, Object>> dict() {
        return ApiResponse.success(service.dict());
    }
}
