package com.embabel.insurance.repository;

import com.embabel.insurance.entity.Quote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuoteRepository extends JpaRepository<Quote, Long> {
    List<Quote> findByCustomerId(Long customerId);
    List<Quote> findByVehicleId(Long vehicleId);
    List<Quote> findByStatus(Quote.QuoteStatus status);

    @Query("SELECT q FROM Quote q JOIN FETCH q.customer JOIN FETCH q.vehicle WHERE q.id = :id")
    Optional<Quote> findByIdWithDetails(Long id);
}