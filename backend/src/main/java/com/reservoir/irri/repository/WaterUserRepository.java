package com.reservoir.irri.repository;

import com.reservoir.irri.domain.WaterUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaterUserRepository extends JpaRepository<WaterUser, Long> {

    boolean existsByUserCode(String userCode);

    Optional<WaterUser> findByUserCode(String userCode);

    List<WaterUser> findAllByOrderByUserCodeAsc();

    List<WaterUser> findByCanalSegmentIdOrderByUserCodeAsc(Long canalSegmentId);

    @Query("select count(u) from WaterUser u where u.status = :status")
    long countByStatus(@Param("status") String status);
}
