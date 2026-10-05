package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.dto.EmergencyRequest;
import com.reservoir.irri.dto.Views.EmergencyDetailVO;
import com.reservoir.irri.dto.Views.EmergencyOrderVO;
import com.reservoir.irri.service.EmergencyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 异常水位险情处置台 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<EmergencyOrderVO>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.list(segmentId));
    }

    @GetMapping("/{id}")
    public ApiResponse<EmergencyDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<EmergencyDetailVO> create(@RequestBody EmergencyRequest request) {
        return ApiResponse.success("险情处置单已发起，等待接管", service.create(request));
    }

    @PostMapping("/{id}/take")
    public ApiResponse<EmergencyDetailVO> take(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("已接管处置单", service.take(id, request));
    }

    @PostMapping("/{id}/refresh")
    public ApiResponse<EmergencyDetailVO> refresh(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("已刷新最新水位与闸门快照", service.refresh(id, request));
    }

    @PostMapping("/{id}/observe")
    public ApiResponse<EmergencyDetailVO> observe(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("继续观测意见已提交", service.observe(id, request));
    }

    @PostMapping("/{id}/gate")
    public ApiResponse<EmergencyDetailVO> gate(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("闸门调整已按当前状态与审批规则提交", service.proposeGate(id, request));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<EmergencyDetailVO> withdraw(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("闸门调整已撤回", service.withdrawGate(id, request));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<EmergencyDetailVO> review(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("已提交待复核", service.review(id, request));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<EmergencyDetailVO> resolve(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("险情已解除", service.resolve(id, request));
    }

    @PostMapping("/{id}/manual")
    public ApiResponse<EmergencyDetailVO> manual(@PathVariable Long id, @RequestBody EmergencyRequest request) {
        return ApiResponse.success("已转人工推进", service.manual(id, request));
    }
}
