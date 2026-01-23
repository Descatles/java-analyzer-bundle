package io.konveyor.tackle.core.internal.symbol;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.lsp4j.SymbolInformation;

public class ImportSymbolProvider implements SymbolProvider {
    @Override
    public List<SymbolInformation> get(SearchMatch match) throws CoreException {
        List<SymbolInformation> symbols = new ArrayList<>();
        IJavaElement element = (IJavaElement) match.getElement();
        // For import-location rules we are only interested in real import declarations.
        // JDT search for TYPE + ALL_OCCURRENCES may return many other element kinds
        // (methods, fields, binary types, etc.). Those should simply be ignored
        // instead of causing ClassCastException and noisy logs.
        if (!(element instanceof IImportDeclaration)) {
            return null;
        }

        IImportDeclaration mod = (IImportDeclaration) element;
        SymbolInformation symbol = new SymbolInformation();
        symbol.setName(mod.getElementName());
        symbol.setKind(convertSymbolKind(element));
        symbol.setContainerName(mod.getParent().getElementName());
        symbol.setLocation(getLocation(mod, match));
        symbols.add(symbol);
        return symbols;
    }
}
