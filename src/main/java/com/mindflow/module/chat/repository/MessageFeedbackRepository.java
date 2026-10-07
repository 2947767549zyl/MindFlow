package com.mindflow.module.chat.repository;

import com.mindflow.module.chat.entity.MessageFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, Long> {

    List<MessageFeedback> findTop8ByUserIdOrderByCreatedAtDesc(Long userId);
}
