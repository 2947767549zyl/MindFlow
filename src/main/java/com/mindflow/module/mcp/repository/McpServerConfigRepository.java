package com.mindflow.module.mcp.repository;

import com.mindflow.module.mcp.entity.McpServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface McpServerConfigRepository extends JpaRepository<McpServerConfigEntity, Long> {

    Optional<McpServerConfigEntity> findByName(String name);

    boolean existsByName(String name);

    List<McpServerConfigEntity> findAllByOrderByNameAsc();

    List<McpServerConfigEntity> findByEnabledTrue();
}
