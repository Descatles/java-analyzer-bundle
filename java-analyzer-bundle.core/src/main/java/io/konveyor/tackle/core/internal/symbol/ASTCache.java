package io.konveyor.tackle.core.internal.symbol;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

/**
 * Thread-safe cache for parsed AST CompilationUnits.
 *
 * This cache avoids re-parsing the same ICompilationUnit multiple times when
 * processing multiple search matches from the same file. Uses WeakHashMap to
 * allow garbage collection of ICompilationUnit keys when they are no longer
 * referenced elsewhere.
 *
 * Performance optimization: AST parsing with binding resolution is expensive
 * (50-500ms per parse). When processing many matches from the same file,
 * caching can provide 2-5x performance improvement.
 */
public class ASTCache {

    // WeakHashMap allows GC to collect entries when ICompilationUnit is no longer referenced
    // Synchronized map for thread safety across multiple symbol providers
    private static final Map<ICompilationUnit, CompilationUnit> cache =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    // Statistics for monitoring cache effectiveness (optional, can be disabled in production)
    private static volatile long cacheHits = 0;
    private static volatile long cacheMisses = 0;

    /**
     * Gets a parsed CompilationUnit for the given ICompilationUnit, using cache if available.
     *
     * @param unit the ICompilationUnit to parse
     * @return parsed CompilationUnit with bindings resolved, or null if parsing fails
     */
    public static CompilationUnit getAST(ICompilationUnit unit) {
        if (unit == null) {
            return null;
        }

        // Try to get from cache first
        CompilationUnit cached = cache.get(unit);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        // Cache miss - parse the AST
        cacheMisses++;
        try {
            ASTParser astParser = ASTParser.newParser(AST.getJLSLatest());
            astParser.setSource(unit);
            astParser.setResolveBindings(true);
            CompilationUnit cu = (CompilationUnit) astParser.createAST(null);

            // Store in cache for future use
            if (cu != null) {
                cache.put(unit, cu);
            }
            return cu;
        } catch (Exception e) {
            logInfo("ASTCache: Failed to parse AST for unit: " + e.getMessage());
            return null;
        }
    }

    /**
     * Removes a specific entry from the cache.
     * Call this when you're done processing a compilation unit to free memory.
     *
     * @param unit the ICompilationUnit to remove from cache
     */
    public static void invalidate(ICompilationUnit unit) {
        if (unit != null) {
            cache.remove(unit);
        }
    }

    /**
     * Clears the entire cache.
     * Call this at the end of a query processing cycle.
     */
    public static void clear() {
        cache.clear();
    }

    /**
     * Returns the current cache size.
     *
     * @return number of entries in the cache
     */
    public static int size() {
        return cache.size();
    }

    /**
     * Returns cache statistics for monitoring.
     *
     * @return string with hit/miss statistics
     */
    public static String getStats() {
        long hits = cacheHits;
        long misses = cacheMisses;
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        return String.format("ASTCache stats: hits=%d, misses=%d, hitRate=%.1f%%, size=%d",
                hits, misses, hitRate, cache.size());
    }

    /**
     * Resets cache statistics.
     */
    public static void resetStats() {
        cacheHits = 0;
        cacheMisses = 0;
    }
}
