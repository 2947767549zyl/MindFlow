package com.mindflow.module.skill.repository;

import com.mindflow.module.skill.entity.SkillPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillPackageRepository extends JpaRepository<SkillPackage, Long> {

    Optional<SkillPackage> findByName(String name);

    boolean existsByName(String name);

    void deleteByName(String name);
}
