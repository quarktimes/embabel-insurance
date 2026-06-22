package com.embabel.insurance.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "policy_documents")
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String documentName;

    @Column(nullable = false, length = 5000)
    private String content;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String language;

    public PolicyDocument() {}

    public PolicyDocument(String documentName, String content, String category, String language) {
        this.documentName = documentName;
        this.content = content;
        this.category = category;
        this.language = language;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}