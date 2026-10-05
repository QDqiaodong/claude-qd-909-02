package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.WaterQuotaHold;
import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.domain.WaterUser;
import com.reservoir.irri.dto.Views.RequestVO;
import com.reservoir.irri.dto.Views.UserVO;
import com.reservoir.irri.dto.WaterRequestRequest;
import com.reservoir.irri.dto.WaterUserRequest;
import com.reservoir.irri.service.WaterUserService;
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

/** 模块五 · 用水户与用水申请 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final WaterUserService service;

    public UserController(WaterUserService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<WaterUser>> listUsers(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listUsers(segmentId));
    }

    @GetMapping("/vo")
    public ApiResponse<List<UserVO>> listUserVO(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listUserVO(segmentId));
    }

    /** 申请卡三列审批流：待审 → 已批 → 已供水（+ 已驳回） */
    @GetMapping("/requests")
    public ApiResponse<List<RequestVO>> requests(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listRequestVO(segmentId));
    }

    @GetMapping("/requests/columns")
    public ApiResponse<List<String>> columns() {
        return ApiResponse.success(service.columns());
    }

    /** 该渠段该日剩余可配水量（万m³） */
    @GetMapping("/remain")
    public ApiResponse<BigDecimal> remain(@RequestParam Long segmentId, @RequestParam String date) {
        return ApiResponse.success(service.remainVolume(segmentId, LocalDate.parse(date)));
    }

    @GetMapping("/{id}")
    public ApiResponse<WaterUser> user(@PathVariable Long id) {
        return ApiResponse.success(service.getUser(id));
    }

    @PostMapping
    public ApiResponse<WaterUser> createUser(@RequestBody WaterUserRequest request) {
        return ApiResponse.success("用水户已登记", service.createUser(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<WaterUser> updateUser(@PathVariable Long id, @RequestBody WaterUserRequest request) {
        return ApiResponse.success("用水户已更新", service.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> removeUser(@PathVariable Long id) {
        service.removeUser(id);
        return ApiResponse.success("用水户已删除", null);
    }

    // ---------------- 用水申请 ----------------

    @PostMapping("/request")
    public ApiResponse<WaterRequest> createRequest(@RequestBody WaterRequestRequest request) {
        return ApiResponse.success("用水申请已提交", service.createRequest(request));
    }

    /** 点卡片推进：待审 → 已批 → 已供水 */
    @PostMapping("/request/{id}/advance")
    public ApiResponse<WaterRequest> advance(@PathVariable Long id,
                                             @RequestParam(required = false) String approver) {
        return ApiResponse.success("申请已推进", service.advance(id, approver));
    }

    /** 驳回：必须填理由 */
    @PostMapping("/request/{id}/reject")
    public ApiResponse<WaterRequest> reject(@PathVariable Long id,
                                            @RequestParam(required = false) String approver,
                                            @RequestParam(required = false) String reason) {
        return ApiResponse.success("申请已驳回", service.reject(id, approver, reason));
    }

    /** 已批退回待审 */
    @PostMapping("/request/{id}/pending")
    public ApiResponse<WaterRequest> backToPending(@PathVariable Long id) {
        return ApiResponse.success("申请已退回待审", service.backToPending(id));
    }

    @GetMapping("/request/{id}")
    public ApiResponse<WaterRequest> request(@PathVariable Long id) {
        return ApiResponse.success(service.getRequest(id));
    }

    /** 额度预占 / 释放 / 入账审计流水 */
    @GetMapping("/request/{id}/holds")
    public ApiResponse<List<WaterQuotaHold>> requestHolds(@PathVariable Long id) {
        return ApiResponse.success(service.requestQuotaHolds(id));
    }

    @DeleteMapping("/request/{id}")
    public ApiResponse<Void> removeRequest(@PathVariable Long id) {
        service.removeRequest(id);
        return ApiResponse.success("用水申请已删除", null);
    }
}
