package io.konveyor.tackle.core.internal.symbol;

/**
 * Mixin interface for SymbolProviders that can use CompilationUnit caching.
 */
public interface WithCache {
    void setCache(CompilationUnitCache cache);
}
