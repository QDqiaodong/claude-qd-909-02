package com.reservoir.irri.repository;

import com.reservoir.irri.domain.OutageAvoidItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutageAvoidItemRepository extends JpaRepository<OutageAvoidItem, Long> {

    List<OutageAvoidItem> findByWindowIdOrderByIdAsc(Long windowId);

    List<OutageAvoidItem> findByWindowIdAndStatus(Long windowId, String status);

    List<OutageAvoidItem> findByStatusOrderByIdAsc(String status);

    /** 某计划名下指定状态的避让项（计划项 + 其配水条项都记了 plan_id） */
    List<OutageAvoidItem> findByPlanIdAndStatus(Long planId, String status);

    List<OutageAvoidItem> findByAllotmentIdAndStatus(Long allotmentId, String status);

    List<OutageAvoidItem> findByTargetTypeAndPlanIdAndStatus(String targetType, Long planId, String status);

    List<OutageAvoidItem> findByTargetTypeAndAllotmentIdAndStatus(String targetType, Long allotmentId, String status);
}
