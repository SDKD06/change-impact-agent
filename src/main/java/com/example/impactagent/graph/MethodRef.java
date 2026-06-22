package com.example.impactagent.graph;

/**
 * A node in the call graph: one method, uniquely identified by its qualified signature
 * (e.g. "com.example.AuthService.validateToken(java.lang.String)").
 */
public record MethodRef(
        String id,          // qualified signature - the unique key
        String simpleName,  // just the method name, for fuzzy lookup
        String filePath,
        int startLine,
        int endLine
) {}
