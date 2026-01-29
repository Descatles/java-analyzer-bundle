package io.konveyor.tackle.core.internal.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

/**
 * Cache for normalized path mappings to avoid repeated path segment operations.
 *
 * This cache stores the mapping from original path strings to their normalized
 * IPath representations after removing common source directory prefixes
 * (src/main/java, etc.).
 *
 * Performance optimization: Path normalization involves string operations and
 * segment removal which can add up when processing many paths in nested loops.
 */
public class PathMappingCache {

    // Cache for normalized paths: original path string -> normalized IPath
    private static final Map<String, IPath> normalizedPathCache = new ConcurrentHashMap<>();

    // Cache for relative paths: (absolutePath + workspacePath) -> relative IPath
    private static final Map<String, IPath> relativePathCache = new ConcurrentHashMap<>();

    // Statistics for monitoring
    private static volatile long cacheHits = 0;
    private static volatile long cacheMisses = 0;

    /**
     * Normalizes a path by removing common source directory prefixes (src/main/java).
     * Results are cached for subsequent calls with the same path.
     *
     * @param path the IPath to normalize
     * @return normalized IPath with source prefixes removed
     */
    public static IPath normalizePath(IPath path) {
        if (path == null) {
            return null;
        }

        String pathKey = path.toString();
        IPath cached = normalizedPathCache.get(pathKey);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        cacheMisses++;
        IPath normalized = path;

        // Remove common source directory segments
        if (normalized.segmentCount() > 0 && "src".equals(normalized.segment(0))) {
            normalized = normalized.removeFirstSegments(1);
        }
        if (normalized.segmentCount() > 0 && "main".equals(normalized.segment(0))) {
            normalized = normalized.removeFirstSegments(1);
        }
        if (normalized.segmentCount() > 0 && "java".equals(normalized.segment(0))) {
            normalized = normalized.removeFirstSegments(1);
        }

        normalizedPathCache.put(pathKey, normalized);
        return normalized;
    }

    /**
     * Gets a relative path from an absolute path, caching the result.
     *
     * @param absolutePath the absolute path string
     * @param workspacePath the workspace root path to make relative to
     * @return the relative IPath
     */
    public static IPath getRelativePath(String absolutePath, IPath workspacePath) {
        if (absolutePath == null || workspacePath == null) {
            return Path.fromOSString(absolutePath);
        }

        String cacheKey = absolutePath + "|" + workspacePath.toString();
        IPath cached = relativePathCache.get(cacheKey);
        if (cached != null) {
            cacheHits++;
            return cached;
        }

        cacheMisses++;
        IPath path = Path.fromOSString(absolutePath);
        IPath relativePath = path.makeRelativeTo(workspacePath);
        relativePathCache.put(cacheKey, relativePath);
        return relativePath;
    }

    /**
     * Gets a normalized relative path in one step (combines getRelativePath and normalizePath).
     *
     * @param absolutePath the absolute path string
     * @param workspacePath the workspace root path
     * @return normalized relative IPath
     */
    public static IPath getNormalizedRelativePath(String absolutePath, IPath workspacePath) {
        IPath relativePath = getRelativePath(absolutePath, workspacePath);
        return normalizePath(relativePath);
    }

    /**
     * Clears all caches.
     */
    public static void clear() {
        normalizedPathCache.clear();
        relativePathCache.clear();
    }

    /**
     * Returns the current cache sizes.
     *
     * @return string with cache size information
     */
    public static String getStats() {
        long hits = cacheHits;
        long misses = cacheMisses;
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        return String.format("PathMappingCache stats: hits=%d, misses=%d, hitRate=%.1f%%, normalizedSize=%d, relativeSize=%d",
                hits, misses, hitRate, normalizedPathCache.size(), relativePathCache.size());
    }

    /**
     * Resets cache statistics.
     */
    public static void resetStats() {
        cacheHits = 0;
        cacheMisses = 0;
    }
}
