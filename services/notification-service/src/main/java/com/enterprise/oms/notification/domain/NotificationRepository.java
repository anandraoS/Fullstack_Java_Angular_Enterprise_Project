package com.enterprise.oms.notification.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    boolean existsBySourceEventIdAndTemplate(UUID sourceEventId, String template);

    long countByStatusIn(Collection<NotificationStatus> statuses);

    @Query("select n.id from Notification n where n.status in :statuses and n.nextAttemptAt <= :now "
            + "order by n.nextAttemptAt asc")
    List<UUID> findDueIds(@Param("statuses") Collection<NotificationStatus> statuses, @Param("now") Instant now,
                          Pageable pageable);
}
