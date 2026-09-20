package io.saife.core.repository;

import io.saife.core.domain.EquipmentChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquipmentChangeRepository extends JpaRepository<EquipmentChange, Long> {
    List<EquipmentChange> findByEquipmentIdOrderByOccurredOnDesc(Long equipmentId);
}
