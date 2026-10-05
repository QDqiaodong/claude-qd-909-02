package com.reservoir.irri.common;

import com.reservoir.irri.dto.Views.EmergencyDetailVO;

/**
 * 险情处置快照已过期：HTTP 409，data 里带最新详情与变化清单。
 * 前端保留值班员已填内容，由其选择刷新后继续、撤回调整或转人工，不能静默覆盖。
 */
public class EmergencyStaleException extends BizException {

    private final transient EmergencyDetailVO detail;

    public EmergencyStaleException(EmergencyDetailVO detail) {
        super("处置依据已变化（水位、渠段或闸门状态有更新），请刷新后继续、撤回调整或转人工");
        this.detail = detail;
    }

    public EmergencyDetailVO getDetail() {
        return detail;
    }
}
