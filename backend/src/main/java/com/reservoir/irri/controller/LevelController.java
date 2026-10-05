package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.WaterLevel;
import com.reservoir.irri.dto.Views.DerivedColumnVO;
import com.reservoir.irri.dto.Views.LevelSeriesVO;
import com.reservoir.irri.dto.Views.LevelVO;
import com.reservoir.irri.dto.WaterLevelRequest;
import com.reservoir.irri.service.LevelService;
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

/**
 * 模块四 · 水位量测。
 *
 * <p>列表与折线里带出来的 durationMinutes / bandSpan / safetyGrade 三列不是表里的业务字段，
 * 而是 {@code @EmbeddableInstantiator} 在读库时按列值构造值对象后派生出来的，
 * 具体是哪几列见 /api/level/derived。
 */
@RestController
@RequestMapping("/api/level")
public class LevelController {

    private final LevelService service;

    public LevelController(LevelService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public ApiResponse<List<WaterLevel>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.list(segmentId));
    }

    /** 量测列表（带值对象派生列） */
    @GetMapping("/vo")
    public ApiResponse<List<LevelVO>> listVO(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listVO(segmentId));
    }

    /** 水位折线：按测站分组，每组一串点位 */
    @GetMapping("/series")
    public ApiResponse<List<LevelSeriesVO>> series(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.series(segmentId));
    }

    /**
     * 派生列清单：说明哪几列来自 @EmbeddableInstantiator 派生、由哪个值对象与实例化器产出。
     * 前端据此把这几列显眼标注为「派生」。
     */
    @GetMapping("/derived")
    public ApiResponse<List<DerivedColumnVO>> derived() {
        return ApiResponse.success(service.derivedColumns());
    }

    @GetMapping("/{id}")
    public ApiResponse<WaterLevel> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<WaterLevel> create(@RequestBody WaterLevelRequest request) {
        return ApiResponse.success("水位量测已登记", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<WaterLevel> update(@PathVariable Long id, @RequestBody WaterLevelRequest request) {
        return ApiResponse.success("水位量测已更新", service.update(id, request));
    }

    /** 重新派生（值对象 refresh 后重算异常标记） */
    @PostMapping("/{id}/refresh")
    public ApiResponse<WaterLevel> refresh(@PathVariable Long id) {
        return ApiResponse.success("派生值已重算", service.refresh(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        service.remove(id);
        return ApiResponse.success("水位量测已删除", null);
    }
}
