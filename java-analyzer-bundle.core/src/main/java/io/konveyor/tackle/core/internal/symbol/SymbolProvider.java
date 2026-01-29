package io.konveyor.tackle.core.internal.symbol;

import static org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin.logInfo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.ls.core.internal.JDTUtils;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.handlers.JsonRpcHelpers;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;

public interface SymbolProvider {
    // Pre-compiled regex patterns for performance optimization
    // Pattern to match wildcards not preceded by a dot (negative lookbehind)
    static final Pattern WILDCARD_PATTERN = Pattern.compile("(?<!\\.)\\*");
    // Pattern to match literal dots for path conversion
    static final Pattern DOT_PATTERN = Pattern.compile("\\.");
    // Pattern to match ".*" for removal in import wildcard handling
    static final Pattern DOT_STAR_PATTERN = Pattern.compile("\\.\\*");

    // Cache for dynamically compiled patterns (query patterns that vary at runtime)
    static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    /**
     * Gets a compiled Pattern for the given regex, using cache to avoid recompilation.
     * @param regex the regex string to compile
     * @return compiled Pattern
     */
    static Pattern getCompiledPattern(String regex) {
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    List<SymbolInformation> get(SearchMatch match) throws CoreException;

    default SymbolKind convertSymbolKind(IJavaElement element) {
        switch (element.getElementType()) {
            case IJavaElement.TYPE:
                try {
                    IType type = (IType) element;
                    if (type.isInterface()) {
                        return SymbolKind.Interface;
                    } else if (type.isEnum()) {
                        return SymbolKind.Enum;
                    }
                } catch (JavaModelException ignore) {
                }
                return SymbolKind.Class;
            case IJavaElement.ANNOTATION:
                return SymbolKind.Property; // TODO: find a better mapping
            case IJavaElement.CLASS_FILE:
            case IJavaElement.COMPILATION_UNIT:
                return SymbolKind.File;
            case IJavaElement.FIELD:
                IField field = (IField) element;
                try {
                    if (field.isEnumConstant()) {
                        return SymbolKind.EnumMember;
                    }
                    int flags = field.getFlags();
                    if (Flags.isStatic(flags) && Flags.isFinal(flags)) {
                        return SymbolKind.Constant;
                    }
                } catch (JavaModelException ignore) {
                }
                return SymbolKind.Field;
            case IJavaElement.IMPORT_CONTAINER:
            case IJavaElement.IMPORT_DECLARATION:
                //should we return SymbolKind.Namespace?
            case IJavaElement.JAVA_MODULE:
                return SymbolKind.Module;
            case IJavaElement.INITIALIZER:
                return SymbolKind.Constructor;
            case IJavaElement.LOCAL_VARIABLE:
                return SymbolKind.Variable;
            case IJavaElement.TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case IJavaElement.METHOD:
                try {
                    // TODO handle `IInitializer`. What should be the `SymbolKind`?
                    if (element instanceof IMethod) {
                        if (((IMethod) element).isConstructor()) {
                            return SymbolKind.Constructor;
                        }
                    }
                    return SymbolKind.Method;
                } catch (JavaModelException e) {
                    return SymbolKind.Method;
                }
            case IJavaElement.PACKAGE_DECLARATION:
                return SymbolKind.Package;
        }
        return SymbolKind.String;
    }

    default Location getLocation(IJavaElement element, SearchMatch match) throws JavaModelException {
        ICompilationUnit compilationUnit = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
		if (compilationUnit != null) {
            logInfo("found compliation unit for match: " + match);
		    return JDTUtils.toLocation(compilationUnit, match.getOffset(), match.getLength());
		} 
		IClassFile cf = (IClassFile) element.getAncestor(IJavaElement.CLASS_FILE);
		if (cf != null) {
		    String packageName = cf.getParent().getElementName();
		    //String jarName = cf.getParent().getParent().getElementName();
		    String uriString = null;
		    try {
                //TODO: Define a URI schema to differentiate between source range and not
                String query = "packageName=" + packageName + "." + cf.getElementName(); 
                if (SourceRange.isAvailable(cf.getSourceRange())) {
                    query = query + "&source-range=true";
                } else {
                    query = query + "&source-range=false";
                }
                String cfPath = cf.getPath().toString();
                String os = System.getProperty("os.name").toLowerCase();
                // windows home path will start with C: so need to add beginning '/'' for uri
                if (os.indexOf("win") >= 0){
                    cfPath = '/' + cfPath;
                }
                uriString = new URI("konveyor-jdt", "contents", cfPath, query, null).toASCIIString();

		    } catch (URISyntaxException e) {
			    JavaLanguageServerPlugin.logException("Error generating URI for class ", e);
                return null;
		    }
            if (uriString == null) { 
                logInfo("Unable to determine location for the element " + element);
                return null;
            }
            Range range = null;
		    try {
                range = toRange(cf, match.getOffset(), match.getLength());
             } catch (Exception e) {
			    JavaLanguageServerPlugin.logException("Error generating range for class ", e);
                return null;
		    }

            logInfo("Found CF info: " + uriString + " range: " + range);
			return new Location(uriString, range);
        }
        try {
            // This casting is safe or is assumed to be safer because the ToString on SearchMatch does it
            logInfo("defaulting to regular toLocation for match: " + match);
            return JDTUtils.toLocation(element);
        } catch (Exception e) {
            JavaLanguageServerPlugin.logException("Unable to determine location for the element " + element, e);
            return null;
        }
    }
    
    private Range toRange(IOpenable openable, int offset, int length) throws Exception{
		Range range = JDTUtils.newRange();
		if (offset > 0 || length > 0) {
			int[] loc = null;
			int[] endLoc = null;
			IBuffer buffer = openable.getBuffer();
			if (buffer != null) {
				loc = JsonRpcHelpers.toLine(buffer, offset);
				endLoc = JsonRpcHelpers.toLine(buffer, offset + length);
			}
			if (loc == null) {
				loc = new int[2];
			}
			if (endLoc == null) {
				endLoc = new int[2];
			}
            setPosition(range.getStart(), loc);
            setPosition(range.getEnd(), endLoc);
		}
		return range;
    }
	
    private static void setPosition(Position position, int[] coords) {
		assert coords.length == 2;
		position.setLine(coords[0]);
		position.setCharacter(coords[1]);
	}

    /*
     * Given a query, class and location of a Match, tells whether CompilationUnit of the match
     * matches the qualification part of the query. For example, if the query is `konveyor.io.Util.get*`,
     * qualification means `konveyor.io.Util`. This is so that we can improve accuracy of a match of 
     * queries that are looking for FQNs. For query `konveyor.io.Util.get*`, returns true if either one is true:
     *  1. match is found in the package `konveyor.io` or class `konveyor.io.Util` 
     *  2. the compilation unit imports package `konveyor.io.Util` or `konveyor.io.*`
     *  3. the compilation unit has a package declaration as `konveyor.io.Util`
     * we do this so that we can rule out a lot of matches before going the AST route
     */
    default boolean queryQualificationMatches(String query, ICompilationUnit unit, Location location) {
        // Make sure that the ICompilationUnit is conistant
        try {
            unit.makeConsistent(null);
        } catch(Exception e) {
            logInfo("unable to make unit consistant, will still try as could be class file in a jar" + e);
        }
        // Use pre-compiled pattern instead of String.replaceAll() which compiles on each call
        query = WILDCARD_PATTERN.matcher(query).replaceAll(".*");
        String queryQualification = "";
        int dotIndex = query.lastIndexOf('.');
        if (dotIndex > 0) {
            // for a query, java.io.paths.File*, queryQualification is java.io.paths
            queryQualification = query.substring(0, dotIndex);
        }
        String packageQueryQualification = "";
        int packageDotIndex = queryQualification.lastIndexOf('.');
        if (packageDotIndex > 0) {
            // for a query, java.io.paths.File*, queryQualification is java.io.paths
            packageQueryQualification = queryQualification.substring(0, packageDotIndex);
        }
        // check if the match was found in the same package as the query was looking for
        // Use pre-compiled DOT_PATTERN instead of String.replaceAll()
        if (queryQualification != "" && location.getUri().contains(DOT_PATTERN.matcher(queryQualification).replaceAll("/"))) {
            return true;
        }
        if (unit != null) {
            try {
                // check if the package declaration on the unit matches query
                // Pre-compile the packageQueryQualification pattern once for all iterations
                Pattern packagePattern = packageQueryQualification.isEmpty() ? null : getCompiledPattern(packageQueryQualification);
                for (IPackageDeclaration packageDecl : unit.getPackageDeclarations()) {
                    if (packagePattern != null && packagePattern.matcher(packageDecl.getElementName()).matches()) {
                        return true;
                    }
                }
                // Pre-compile the query pattern once for all import iterations
                Pattern queryPattern = getCompiledPattern(query);
                for (IImportDeclaration importDecl : unit.getImports()) {
                    String importElement = importDecl.getElementName();
                    String importQualification = "";
                    int importDotIndex = query.lastIndexOf('.');
                    if (importDotIndex > 0) {
                        importQualification = query.substring(0, dotIndex);
                    }
                    // import can be absolute like java.io.paths.FileReader
                    // Use pre-compiled pattern instead of String.matches()
                    if (getCompiledPattern(importElement).matcher(query).matches()) {
                        return true;
                    }
                    if (queryPattern.matcher(importElement).matches()) {
                        return true;
                    }
                    // an import can be java.io.paths.* or java.io.*
                    if (importElement.contains("*")) {
                        if (queryQualification != "") {
                            // query is java.io.paths.File*, import is java.io.paths.*
                            if (queryQualification.startsWith(importQualification)) {
                                return true;
                            }
                            // Use pre-compiled pattern for queryQualification matching
                            String importWithoutWildcard = DOT_STAR_PATTERN.matcher(importElement).replaceAll("");
                            if (getCompiledPattern(queryQualification).matcher(importWithoutWildcard).matches()) {
                                return true;
                            }
                        }
                    }
                    // query can be java.io.paths.Path.checkPath()
                    if (query.startsWith(importElement)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                logInfo("unable to determine accuracy of the match");
            }
        }
        return false;
    }
}
