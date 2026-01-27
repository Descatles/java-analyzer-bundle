package io.konveyor.tackle.core.internal.symbol;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Cache for ICompilationUnit and parsed AST CompilationUnit to avoid repeated 
 * expensive operations when processing multiple matches from the same file.
 */
public class CompilationUnitCache {
    
    private static final int MAX_CACHE_SIZE = 50;
    
    // LRU cache for ICompilationUnit (working copies from ClassFiles)
    private final Map<String, ICompilationUnit> unitCache;
    
    // LRU cache for parsed AST
    private final Map<String, CompilationUnit> astCache;
    
    public CompilationUnitCache() {
        // Use LinkedHashMap with access order for LRU behavior
        this.unitCache = new LinkedHashMap<String, ICompilationUnit>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ICompilationUnit> eldest) {
                if (size() > MAX_CACHE_SIZE) {
                    // Clean up the evicted working copy
                    try {
                        eldest.getValue().discardWorkingCopy();
                        eldest.getValue().close();
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                    return true;
                }
                return false;
            }
        };
        
        this.astCache = new LinkedHashMap<String, CompilationUnit>(MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompilationUnit> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
    }
    
    /**
     * Get or create ICompilationUnit for the given element.
     * For ClassFiles, creates and caches a working copy.
     */
    public ICompilationUnit getCompilationUnit(IJavaElement element) {
        // First try to get compilation unit directly
        ICompilationUnit unit = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (unit != null) {
            return unit;
        }
        
        // For ClassFile, check cache or create working copy
        IClassFile cls = (IClassFile) element.getAncestor(IJavaElement.CLASS_FILE);
        if (cls != null) {
            String key = cls.getHandleIdentifier();
            ICompilationUnit cached = unitCache.get(key);
            if (cached != null) {
                return cached;
            }
            
            try {
                unit = cls.getWorkingCopy(new WorkingCopyOwnerImpl(), null);
                unitCache.put(key, unit);
                return unit;
            } catch (Exception e) {
                logInfo("Failed to create working copy: " + e);
            }
        }
        
        return null;
    }
    
    /**
     * Get or create parsed AST for the given compilation unit.
     */
    public CompilationUnit getAST(ICompilationUnit unit) {
        if (unit == null) {
            return null;
        }
        
        String key = unit.getHandleIdentifier();
        CompilationUnit cached = astCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        try {
            ASTParser astParser = ASTParser.newParser(AST.getJLSLatest());
            astParser.setSource(unit);
            astParser.setResolveBindings(true);
            CompilationUnit cu = (CompilationUnit) astParser.createAST(null);
            astCache.put(key, cu);
            return cu;
        } catch (Exception e) {
            logInfo("Failed to parse AST: " + e);
        }
        
        return null;
    }
    
    /**
     * Clean up all cached resources.
     */
    public void dispose() {
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
    }
}
