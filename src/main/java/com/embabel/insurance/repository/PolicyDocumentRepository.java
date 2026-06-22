package com.embabel.insurance.repository;

import com.embabel.insurance.entity.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
    List<PolicyDocument> findByCategory(String category);
    List<PolicyDocument> findByLanguage(String language);
}