package io.konveyor.tackle.core.internal.symbol;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Thread-safe singleton cache for ICompilationUnit and parsed AST CompilationUnit 
 * to avoid repeated expensive operations when processing multiple matches from the same file.
 * 
 * Thread Safety:
 * - Uses ConcurrentHashMap for lock-free reads and fine-grained locking on writes
 * - Uses AtomicLong for thread-safe statistics
 * - Uses computeIfAbsent for atomic check-and-create operations
 * - AST objects are read-only after creation, safe for concurrent traversal
 * 
 * Key optimizations:
 * 1. Caches WorkingCopy to avoid repeated cls.getWorkingCopy() calls
 * 2. Caches parsed AST with resolved bindings to avoid repeated astParser.createAST() calls
 * 3. Uses size-based eviction to limit memory usage
 */
public class CompilationUnitCache {
    
    private static final int MAX_UNIT_CACHE_SIZE = 100;
    private static final int MAX_AST_CACHE_SIZE = 50;
    
    // Singleton instance with volatile for double-checked locking
    private static volatile CompilationUnitCache instance;
    
    // Thread-safe cache using ConcurrentHashMap
    private final ConcurrentHashMap<String, ICompilationUnit> unitCache;
    private final ConcurrentHashMap<String, CompilationUnit> astCache;
    
    // Thread-safe statistics using AtomicLong
    private final AtomicLong unitCacheHits = new AtomicLong(0);
    private final AtomicLong unitCacheMisses = new AtomicLong(0);
    private final AtomicLong astCacheHits = new AtomicLong(0);
    private final AtomicLong astCacheMisses = new AtomicLong(0);
    
    /**
     * Get the singleton instance of the cache.
     */
    public static CompilationUnitCache getInstance() {
        if (instance == null) {
            synchronized (CompilationUnitCache.class) {
                if (instance == null) {
                    instance = new CompilationUnitCache();
                }
            }
        }
        return instance;
    }
    
    /**
     * Reset the cache (useful for testing or when starting a new search session).
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.dispose();
            instance = null;
        }
    }
    
    private CompilationUnitCache() {
        this.unitCache = new ConcurrentHashMap<>(MAX_UNIT_CACHE_SIZE);
        this.astCache = new ConcurrentHashMap<>(MAX_AST_CACHE_SIZE);
    }
    
    /**
     * Get or create ICompilationUnit for the given element.
     * For ClassFiles, creates and caches a working copy.
     * 
     * Thread-safe: uses ConcurrentHashMap.computeIfAbsent for atomic check-and-create.
     * 
     * IMPORTANT: Do NOT call discardWorkingCopy() or close() on the returned unit
     * as it is managed by the cache.
     * 
     * @param element The Java element to get the compilation unit for
     * @return The ICompilationUnit, or null if not available
     */
    public ICompilationUnit getCompilationUnit(IJavaElement element) {
        // First try to get compilation unit directly (source files - not cached)
        ICompilationUnit unit = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (unit != null) {
            return unit;
        }
        
        // For ClassFile, use cache
        IClassFile cls = (IClassFile) element.getAncestor(IJavaElement.CLASS_FILE);
        if (cls == null) {
            return null;
        }
        
        String key = cls.getHandleIdentifier();
        
        // Check if already cached (fast path, no locking)
        ICompilationUnit cached = unitCache.get(key);
        if (cached != null) {
            unitCacheHits.incrementAndGet();
            return cached;
        }
        
        // Evict if cache is too large (simple size-based eviction)
        if (unitCache.size() >= MAX_UNIT_CACHE_SIZE) {
            evictOldestUnit();
        }
        
        // Use computeIfAbsent for atomic creation
        // Note: The lambda should be quick; if getWorkingCopy is slow, 
        // other threads will block on this key only (not entire map)
        try {
            ICompilationUnit result = unitCache.computeIfAbsent(key, k -> {
                try {
                    return cls.getWorkingCopy(new WorkingCopyOwnerImpl(), null);
                } catch (Exception e) {
                    logInfo("Failed to create working copy: " + e);
                    return null;
                }
            });
            
            if (result != null && result != cached) {
                unitCacheMisses.incrementAndGet();
            }
            return result;
        } catch (Exception e) {
            logInfo("Failed to get compilation unit: " + e);
            return null;
        }
    }
    
    /**
     * Get or create parsed AST with resolved bindings for the given compilation unit.
     * 
     * Thread-safe: uses ConcurrentHashMap.computeIfAbsent for atomic check-and-create.
     * The returned AST is immutable/read-only after creation, safe for concurrent traversal.
     * 
     * @param unit The compilation unit to parse
     * @return The parsed CompilationUnit with resolved bindings, or null if parsing failed
     */
    public CompilationUnit getAST(ICompilationUnit unit) {
        if (unit == null) {
            return null;
        }
        
        String key = unit.getHandleIdentifier();
        
        // Check if already cached (fast path)
        CompilationUnit cached = astCache.get(key);
        if (cached != null) {
            astCacheHits.incrementAndGet();
            return cached;
        }
        
        // Evict if cache is too large
        if (astCache.size() >= MAX_AST_CACHE_SIZE) {
            evictOldestAST();
        }
        
        // Use computeIfAbsent for atomic creation
        try {
            CompilationUnit result = astCache.computeIfAbsent(key, k -> {
                try {
                    ASTParser astParser = ASTParser.newParser(AST.getJLSLatest());
                    astParser.setSource(unit);
                    astParser.setResolveBindings(true);
                    astParser.setBindingsRecovery(true);
                    astParser.setStatementsRecovery(true);
                    return (CompilationUnit) astParser.createAST(null);
                } catch (Exception e) {
                    logInfo("Failed to parse AST: " + e);
                    return null;
                }
            });
            
            if (result != null && result != cached) {
                astCacheMisses.incrementAndGet();
            }
            return result;
        } catch (Exception e) {
            logInfo("Failed to get AST: " + e);
            return null;
        }
    }
    
    /**
     * Simple eviction: remove first entry found.
     * ConcurrentHashMap doesn't maintain insertion order, so this is approximate.
     */
    private void evictOldestUnit() {
        Map.Entry<String, ICompilationUnit> oldest = unitCache.entrySet().iterator().next();
        if (oldest != null) {
            ICompilationUnit removed = unitCache.remove(oldest.getKey());
            if (removed != null) {
                try {
                    removed.discardWorkingCopy();
                    removed.close();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
        }
    }
    
    private void evictOldestAST() {
        Map.Entry<String, CompilationUnit> oldest = astCache.entrySet().iterator().next();
        if (oldest != null) {
            astCache.remove(oldest.getKey());
        }
    }
    
    /**
     * Convenience method to get AST for a Java element.
     */
    public CompilationUnit getASTForElement(IJavaElement element) {
        ICompilationUnit unit = getCompilationUnit(element);
        return getAST(unit);
    }
    
    /**
     * Get cache statistics for monitoring (thread-safe).
     */
    public String getCacheStats() {
        long unitHits = unitCacheHits.get();
        long unitMisses = unitCacheMisses.get();
        long astHits = astCacheHits.get();
        long astMisses = astCacheMisses.get();
        
        double unitHitRate = unitHits + unitMisses > 0 
            ? (double) unitHits / (unitHits + unitMisses) * 100 : 0;
        double astHitRate = astHits + astMisses > 0 
            ? (double) astHits / (astHits + astMisses) * 100 : 0;
        return String.format(
            "CompilationUnitCache Stats: Unit[hits=%d, misses=%d, hitRate=%.1f%%, size=%d], " +
            "AST[hits=%d, misses=%d, hitRate=%.1f%%, size=%d]",
            unitHits, unitMisses, unitHitRate, unitCache.size(),
            astHits, astMisses, astHitRate, astCache.size()
        );
    }
    
    /**
     * Clean up all cached resources.
     */
    public void dispose() {
        // Clean up unit cache
        for (ICompilationUnit unit : unitCache.values()) {
            try {
                unit.discardWorkingCopy();
                unit.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        unitCache.clear();
        astCache.clear();
        
        logInfo(getCacheStats());
        
        // Reset statistics
        unitCacheHits.set(0);
        unitCacheMisses.set(0);
        astCacheHits.set(0);
        astCacheMisses.set(0);
    }
    
    public int getUnitCacheSize() {
        return unitCache.size();
    }
    
    public int getASTCacheSize() {
        return astCache.size();
    }
}
