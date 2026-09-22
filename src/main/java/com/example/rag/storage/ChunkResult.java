package com.example.rag.storage;

import java.util.UUID;

/** A chunk returned from search, with its relevance score (lower is closer for vector search). */
public record ChunkResult(UUID id, UUID documentId, String content, double score) {}


//The Java Record (public record ...): A record in Java is a modern, 
// concise way to create a simple, immutable class whose main job is
//  to hold data (automatically generating getters, constructors, 
// and toString methods for you).