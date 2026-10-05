package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.Canal;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.dto.CanalRequest;
import com.reservoir.irri.dto.CanalSegmentRequest;
import com.reservoir.irri.dto.Views.CanalVO;
import com.reservoir.irri.dto.Views.SegmentDetailVO;
import com.reservoir.irri.service.CanalService;
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

/** 模块一 · 渠系与渠段 */
@RestController
@RequestMapping("/api/canal")
public class CanalController {

    private final CanalService service;

    public CanalController(CanalService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<Canal>> list() {
        return ApiResponse.success(service.listCanals());
    }

    /** 纵向水系拓扑图：渠道（三级）→ 渠段节点，点节点看详情 */
    @GetMapping("/topology")
    public ApiResponse<List<CanalVO>> topology() {
        return ApiResponse.success(service.topology());
    }

    @GetMapping("/segments")
    public ApiResponse<List<CanalSegment>> segments(@RequestParam(required = false) Long canalId) {
        return ApiResponse.success(service.listSegments(canalId));
    }

    /** 点拓扑图节点看渠段详情：闸门 + 水位量测 + 配水安排 */
    @GetMapping("/segment/{id}/detail")
    public ApiResponse<SegmentDetailVO> segmentDetail(@PathVariable Long id) {
        return ApiResponse.success(service.segmentDetail(id));
    }

    @GetMapping("/segment/{id}")
    public ApiResponse<CanalSegment> segment(@PathVariable Long id) {
        return ApiResponse.success(service.getSegment(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<Canal> get(@PathVariable Long id) {
        return ApiResponse.success(service.getCanal(id));
    }

    @PostMapping
    public ApiResponse<Canal> create(@RequestBody CanalRequest request) {
        return ApiResponse.success("渠道已创建", service.createCanal(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Canal> update(@PathVariable Long id, @RequestBody CanalRequest request) {
        return ApiResponse.success("渠道已更新", service.updateCanal(id, request));
    }

    /** 通水 / 岁修 / 停用 */
    @PostMapping("/{id}/status")
    public ApiResponse<Canal> changeStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("渠道状态已更新", service.changeCanalStatus(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.removeCanal(id);
        return ApiResponse.success("渠道已删除", null);
    }

    // ---------------- 渠段 ----------------

    @PostMapping("/segment")
    public ApiResponse<CanalSegment> createSegment(@RequestBody CanalSegmentRequest request) {
        return ApiResponse.success("渠段已创建", service.createSegment(request));
    }

    @PutMapping("/segment/{id}")
    public ApiResponse<CanalSegment> updateSegment(@PathVariable Long id,
                                                   @RequestBody CanalSegmentRequest request) {
        return ApiResponse.success("渠段已更新", service.updateSegment(id, request));
    }

    @PostMapping("/segment/{id}/status")
    public ApiResponse<CanalSegment> changeSegmentStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("渠段状态已更新", service.changeSegmentStatus(id, status));
    }

    @DeleteMapping("/segment/{id}")
    public ApiResponse<Void> removeSegment(@PathVariable Long id) {
        service.removeSegment(id);
        return ApiResponse.success("渠段已删除", null);
    }
}
