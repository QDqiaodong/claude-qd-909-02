package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.dto.AvoidResolveRequest;
import com.reservoir.irri.dto.OutageWindowRequest;
import com.reservoir.irri.dto.Views.AvoidItemVO;
import com.reservoir.irri.dto.Views.OutageWindowVO;
import com.reservoir.irri.service.OutageService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块三补 · 渠段检修停水窗口与避让 */
@RestController
@RequestMapping("/api/outage")
public class OutageController {

    private final OutageService service;

    public OutageController(OutageService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<OutageWindowVO>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.list(segmentId));
    }

    /** 全部待避让项（跨窗口） */
    @GetMapping("/pending")
    public ApiResponse<List<AvoidItemVO>> pending() {
        return ApiResponse.success(service.pending());
    }

    /** 某窗口的避让项（含历史去向） */
    @GetMapping("/{id}/items")
    public ApiResponse<List<AvoidItemVO>> items(@PathVariable Long id) {
        return ApiResponse.success(service.items(id));
    }

    /** 登记停水窗口：生效即扫描，重叠的计划与配水条标为待避让 */
    @PostMapping
    public ApiResponse<OutageWindowVO> create(@RequestBody OutageWindowRequest request) {
        OutageWindowVO vo = service.create(request);
        return ApiResponse.success("停水窗口已登记，命中 " + vo.getPendingCount() + " 项已标为待避让", vo);
    }

    /** 变更窗口：重新比对，新增重叠标待避让，不再重叠的待避让自动解除 */
    @PutMapping("/{id}")
    public ApiResponse<OutageWindowVO> update(@PathVariable Long id, @RequestBody OutageWindowRequest request) {
        OutageWindowVO vo = service.update(id, request);
        return ApiResponse.success("停水窗口已变更，当前待避让 " + vo.getPendingCount() + " 项", vo);
    }

    /** 撤销窗口：只恢复尚未人工确认的待避让项，已处理结果保留 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<OutageWindowVO> cancel(@PathVariable Long id,
                                              @RequestParam(required = false) String note) {
        OutageWindowVO vo = service.cancel(id, note);
        return ApiResponse.success("停水窗口已撤销，恢复待避让 " + vo.getRestoredCount() + " 项，已处理结果保留", vo);
    }

    /** 处理待避让项：整体顺延 / 拆分改期 / 例外保留 */
    @PostMapping("/item/{itemId}/resolve")
    public ApiResponse<AvoidItemVO> resolve(@PathVariable Long itemId, @RequestBody AvoidResolveRequest request) {
        AvoidItemVO vo = service.resolve(itemId, request);
        return ApiResponse.success("避让处理完成：" + vo.getStatusName(), vo);
    }
}
