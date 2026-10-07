package com.mindflow.module.skill.repository;

import com.mindflow.module.skill.entity.SkillFileEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillFileEntryRepository extends JpaRepository<SkillFileEntry, Long> {

    List<SkillFileEntry> findBySkillNameOrderByRelPathAsc(String skillName);

    Optional<SkillFileEntry> findBySkillNameAndRelPath(String skillName, String relPath);

    void deleteBySkillName(String skillName);

    long countBySkillName(String skillName);
}
