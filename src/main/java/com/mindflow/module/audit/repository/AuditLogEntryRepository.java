package com.mindflow.module.audit.repository;

import com.mindflow.module.audit.entity.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findTop50ByOrderByCreatedAtDesc();
}
