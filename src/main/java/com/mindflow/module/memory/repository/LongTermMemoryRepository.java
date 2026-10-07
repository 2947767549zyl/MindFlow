package com.mindflow.module.memory.repository;

import com.mindflow.module.memory.entity.LongTermMemoryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LongTermMemoryRepository extends JpaRepository<LongTermMemoryEntry, Long> {

    boolean existsByUserIdAndContent(String userId, String content);

    List<LongTermMemoryEntry> findByUserIdOrderByCreatedAtDesc(String userId);
}
