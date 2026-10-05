package com.reservoir.irri.repository;

import com.reservoir.irri.domain.EmergencyGateSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyGateSnapshotRepository extends JpaRepository<EmergencyGateSnapshot, Long> {

    List<EmergencyGateSnapshot> findByEmergencyOrderIdAndVersionNoOrderByGateCodeAscIdAsc(
            Long emergencyOrderId, Integer versionNo);

    Optional<EmergencyGateSnapshot> findFirstByEmergencyOrderIdOrderByVersionNoDescIdDesc(Long emergencyOrderId);
}
