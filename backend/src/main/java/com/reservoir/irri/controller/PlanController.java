package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.Allotment;
import com.reservoir.irri.domain.IrrigationPlan;
import com.reservoir.irri.dto.AllotmentRequest;
import com.reservoir.irri.dto.PlanRequest;
import com.reservoir.irri.dto.Views.AllotmentVO;
import com.reservoir.irri.dto.Views.PlanGanttVO;
import com.reservoir.irri.dto.Views.PlanVO;
import com.reservoir.irri.service.PlanService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块三 · 灌溉计划与配水 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<IrrigationPlan>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.list(segmentId));
    }

    /** 计划 + 配水甘特条 */
    @GetMapping("/vo")
    public ApiResponse<List<PlanVO>> listVO(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listVO(segmentId));
    }

    /** 周期甘特图：日期轴 + 每日负载 */
    @GetMapping("/gantt")
    public ApiResponse<PlanGanttVO> gantt(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.gantt(segmentId));
    }

    /** 该渠段该日剩余可配水量（万m³） */
    @GetMapping("/remain")
    public ApiResponse<BigDecimal> remain(@RequestParam Long segmentId, @RequestParam String date) {
        return ApiResponse.success(service.remainVolume(segmentId, LocalDate.parse(date)));
    }

    @GetMapping("/allotments")
    public ApiResponse<List<Allotment>> allotments(@RequestParam(required = false) Long planId,
                                                   @RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.allotments(planId, segmentId));
    }

    @GetMapping("/allotment/{id}")
    public ApiResponse<AllotmentVO> allotment(@PathVariable Long id) {
        return ApiResponse.success(com.reservoir.irri.service.PlanService.toAllotmentVO(
                service.getAllotment(id), null, null));
    }

    @GetMapping("/{id}")
    public ApiResponse<IrrigationPlan> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<IrrigationPlan> create(@RequestBody PlanRequest request) {
        return ApiResponse.success("灌溉计划已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<IrrigationPlan> update(@PathVariable Long id, @RequestBody PlanRequest request) {
        return ApiResponse.success("灌溉计划已更新", service.update(id, request));
    }

    /** 推进计划状态：编制 → 待审批 → 已批准 → 配水中 → 已结束 */
    @PostMapping("/{id}/advance")
    public ApiResponse<IrrigationPlan> advance(@PathVariable Long id,
                                               @RequestParam(required = false) String approver) {
        return ApiResponse.success("计划状态已推进", service.advance(id, approver));
    }

    /** 退回编制 */
    @PostMapping("/{id}/draft")
    public ApiResponse<IrrigationPlan> backToDraft(@PathVariable Long id) {
        return ApiResponse.success("计划已退回编制", service.backToDraft(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("灌溉计划已删除", null);
    }

    // ---------------- 配水 ----------------

    @PostMapping("/allotment")
    public ApiResponse<Allotment> createAllotment(@RequestBody AllotmentRequest request) {
        return ApiResponse.success("配水已排期", service.createAllotment(request));
    }

    @PutMapping("/allotment/{id}")
    public ApiResponse<Allotment> updateAllotment(@PathVariable Long id,
                                                  @RequestBody AllotmentRequest request) {
        return ApiResponse.success("配水已更新", service.updateAllotment(id, request));
    }

    /** 拖动甘特条改天数 */
    @PostMapping("/allotment/{id}/move")
    public ApiResponse<Allotment> moveAllotment(@PathVariable Long id, @RequestParam String date) {
        return ApiResponse.success("配水日期已调整", service.moveAllotment(id, LocalDate.parse(date)));
    }

    @DeleteMapping("/allotment/{id}")
    public ApiResponse<Void> removeAllotment(@PathVariable Long id) {
        service.removeAllotment(id);
        return ApiResponse.success("配水记录已删除", null);
    }
}
