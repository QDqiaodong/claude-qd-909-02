package com.reservoir.irri.repository;

import com.reservoir.irri.domain.EmergencyLevelContext;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyLevelContextRepository extends JpaRepository<EmergencyLevelContext, Long> {

    List<EmergencyLevelContext> findByEmergencyOrderIdOrderByVersionNoAscIdAsc(Long emergencyOrderId);

    Optional<EmergencyLevelContext> findFirstByEmergencyOrderIdOrderByVersionNoDescIdDesc(Long emergencyOrderId);
}
