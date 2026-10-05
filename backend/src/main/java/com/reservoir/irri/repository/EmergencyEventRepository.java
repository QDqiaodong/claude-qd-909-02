package com.reservoir.irri.repository;

import com.reservoir.irri.domain.EmergencyEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long> {

    List<EmergencyEvent> findByEmergencyOrderIdOrderByEventTimeAscIdAsc(Long emergencyOrderId);
}
