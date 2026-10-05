package com.reservoir.irri.repository;

import com.reservoir.irri.domain.OutageWindow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutageWindowRepository extends JpaRepository<OutageWindow, Long> {

    boolean existsByWindowNo(String windowNo);

    List<OutageWindow> findAllByOrderByIdDesc();

    List<OutageWindow> findByCanalSegmentIdOrderByIdDesc(Long canalSegmentId);

    /** 生效中的窗口（避让扫描与甘特色带用） */
    List<OutageWindow> findByStatus(String status);
}
