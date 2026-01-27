package io.konveyor.tackle.core.internal.symbol;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;

import io.konveyor.tackle.core.internal.symbol.CustomASTVisitor.QueryLocation;

public class MethodCallSymbolProvider implements SymbolProvider, WithQuery {
    private String query;
    
    @Override
    public List<SymbolInformation> get(SearchMatch match) {
        SymbolKind k = convertSymbolKind((IJavaElement) match.getElement());
        List<SymbolInformation> symbols = new ArrayList<>();
        // For Method Calls we will need to do the local variable trick
        try {
            MethodReferenceMatch m = (MethodReferenceMatch) match;
            IMethod e = (IMethod) m.getElement();
            SymbolInformation symbol = new SymbolInformation();
            Location location = getLocation((IJavaElement) match.getElement(), match);
            symbol.setName(e.getElementName());
            symbol.setKind(convertSymbolKind(e));
            symbol.setContainerName(e.getParent().getElementName());
            symbol.setLocation(location); 
            if (this.query.contains(".")) { 
                // Use cached compilation unit instead of creating new working copy each time
                CompilationUnitCache cache = CompilationUnitCache.getInstance();
                ICompilationUnit unit = cache.getCompilationUnit(e);
                
                if (unit != null && this.queryQualificationMatches(this.query, unit, location)) {
                    // Use cached AST instead of parsing each time
                    CompilationUnit cu = cache.getAST(unit);
                    if (cu != null) {
                        CustomASTVisitor visitor = new CustomASTVisitor(query, match, QueryLocation.METHOD_CALL);
                        cu.accept(visitor);
                        if (visitor.symbolMatches()) {
                            symbols.add(symbol);
                        }
                    }
                }
                // NOTE: Do NOT call unit.discardWorkingCopy() or unit.close() 
                // as the unit is managed by the cache
            } else {
                symbols.add(symbol);
            }
        } catch (Exception e) {
            logInfo("unable to convert for variable: " + e);
        }

        return symbols;
    }
    @Override
    public void setQuery(String query) {
        this.query = query;
    }
}
