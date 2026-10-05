package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.Gate;
import com.reservoir.irri.domain.GateOperation;
import com.reservoir.irri.dto.GateOperationRequest;
import com.reservoir.irri.dto.GateRequest;
import com.reservoir.irri.dto.Views.GateVO;
import com.reservoir.irri.dto.Views.OperationVO;
import com.reservoir.irri.service.GateService;
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

/** 模块二 · 闸门与开度操作 */
@RestController
@RequestMapping("/api/gate")
public class GateController {

    private final GateService service;

    public GateController(GateService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<Gate>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.list(segmentId));
    }

    /** 闸门面板：开度大滑杆 + 安全开度区色带 */
    @GetMapping("/panel")
    public ApiResponse<List<GateVO>> panel(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.panel(segmentId));
    }

    @GetMapping("/options")
    public ApiResponse<List<GateVO>> options() {
        return ApiResponse.success(service.options());
    }

    @GetMapping("/operations")
    public ApiResponse<List<OperationVO>> operations(@RequestParam(required = false) Long gateId) {
        return ApiResponse.success(service.operations(gateId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Gate> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Gate> create(@RequestBody GateRequest request) {
        return ApiResponse.success("闸门已创建", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Gate> update(@PathVariable Long id, @RequestBody GateRequest request) {
        return ApiResponse.success("闸门已更新", service.update(id, request));
    }

    /** 运行 / 检修 / 停用 */
    @PostMapping("/{id}/status")
    public ApiResponse<Gate> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("闸门状态已更新", service.changeStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("闸门已删除", null);
    }

    // ---------------- 开度操作 ----------------

    /**
     * 调节开度。confirmed=true 表示前端已做「超安全幅度二次确认」；
     * 幅度超过 30 个百分点时操作进入待审批。
     */
    @PostMapping("/operation")
    public ApiResponse<GateOperation> adjust(@RequestBody GateOperationRequest request,
                                             @RequestParam(required = false, defaultValue = "false") boolean confirmed) {
        return ApiResponse.success("开度调节已提交", service.adjust(request, confirmed));
    }

    @PostMapping("/operation/{id}/approve")
    public ApiResponse<GateOperation> approve(@PathVariable Long id,
                                              @RequestParam(required = false) String approver) {
        return ApiResponse.success("操作已批准并落开度", service.approve(id, approver));
    }

    @PostMapping("/operation/{id}/reject")
    public ApiResponse<GateOperation> reject(@PathVariable Long id,
                                             @RequestParam(required = false) String approver,
                                             @RequestParam(required = false) String reason) {
        return ApiResponse.success("操作已驳回", service.reject(id, approver, reason));
    }

    @GetMapping("/operation/{id}")
    public ApiResponse<GateOperation> operation(@PathVariable Long id) {
        return ApiResponse.success(service.getOperation(id));
    }
}
