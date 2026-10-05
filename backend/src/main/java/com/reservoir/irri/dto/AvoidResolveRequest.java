package com.reservoir.irri.dto;

import java.time.LocalDate;
import lombok.Data;

/** 待避让项处理入参：整体顺延 / 拆分改期 / 例外保留 */
@Data
public class AvoidResolveRequest {

    /** POSTPONE 整体顺延 / SPLIT 拆分改期 / KEEP 例外保留 */
    private String action;

    /** 配水条拆分改期时指定的窗口外日期 */
    private LocalDate targetDate;

    /** 例外依据（KEEP 必填）；其余方式可作补充说明 */
    private String reason;

    /** 处理人（调度员） */
    private String operator;
}
