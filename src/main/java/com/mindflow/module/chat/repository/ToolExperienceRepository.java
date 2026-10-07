package com.mindflow.module.chat.repository;

import com.mindflow.module.chat.entity.ToolExperience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ToolExperienceRepository extends JpaRepository<ToolExperience, Long> {

    Optional<ToolExperience> findBySignature(String signature);

    List<ToolExperience> findTop30ByOrderByOccurrenceCountDesc();

    List<ToolExperience> findByToolNameInOrderByOccurrenceCountDesc(Collection<String> toolNames);
}
