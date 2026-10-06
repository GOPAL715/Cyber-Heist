package com.cyberheist.shop;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, UUID> {
    List<Item> findByActiveTrueOrderByPriceAsc();
    Optional<Item> findByIdAndActiveTrue(UUID id);

    /** Resolves the starter item and any other item addressed by its code. */
    Optional<Item> findByCode(String code);
}
