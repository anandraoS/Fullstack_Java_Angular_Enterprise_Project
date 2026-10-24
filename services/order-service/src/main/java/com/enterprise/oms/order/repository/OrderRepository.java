package com.enterprise.oms.order.repository;

import com.enterprise.oms.order.entity.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Listing never combines a collection fetch with pagination (Hibernate would page IN MEMORY): the page of orders is
 * loaded first (2 statements: select + count), then the items of exactly those ids in ONE more statement.
 * Total = 3 statements per page regardless of page size (no N+1).
 */
public interface OrderRepository extends JpaRepository<CustomerOrder, UUID>, JpaSpecificationExecutor<CustomerOrder> {

    @Query("select distinct o from CustomerOrder o left join fetch o.items where o.id in :ids")
    List<CustomerOrder> findAllWithItemsByIdIn(@Param("ids") Collection<UUID> ids);

    @Query("select o from CustomerOrder o left join fetch o.items where o.id = :id")
    Optional<CustomerOrder> findWithItemsById(@Param("id") UUID id);
}
