package com.mindflow.module.wiki.repository;

import com.mindflow.module.wiki.entity.WikiPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    Optional<WikiPage> findByTitle(String title);

    boolean existsByTitle(String title);
}
