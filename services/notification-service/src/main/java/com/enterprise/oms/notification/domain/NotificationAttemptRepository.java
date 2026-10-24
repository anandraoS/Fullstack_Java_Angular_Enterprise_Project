package com.enterprise.oms.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByNotificationIdOrderByAttemptedAtAsc(UUID notificationId);
}
