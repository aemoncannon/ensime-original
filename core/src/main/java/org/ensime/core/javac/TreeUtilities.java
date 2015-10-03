/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Sun Microsystems, Inc. Portions Copyright 1997-2010 Sun
 * Microsystems, Inc. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */
package org.ensime.core.javac;

import com.sun.source.tree.*;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.util.Context;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.UnionType;
import javax.lang.model.util.Types;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.JavaSource.Phase;
import org.netbeans.api.lexer.TokenSequence;

/**
 *
 * @author Jan Lahoda, Dusan Balek, Tomas Zezula
 */
public final class TreeUtilities {
    
    /**{@link Kind}s that are represented by {@link ClassTree}.
     * 
     * @since 0.67
     */
    public static final Set<Kind> CLASS_TREE_KINDS = EnumSet.of(Kind.ANNOTATION_TYPE, Kind.CLASS, Kind.ENUM, Kind.INTERFACE);
    
    private final CompilationInfo info;
    
    /** Creates a new instance of CommentUtilities */
    public TreeUtilities(final CompilationInfo info) {
        assert info != null;
        this.info = info;
    }
    
    /**Checks whether the given tree represents a class.
     * @deprecated since 0.67, <code>Tree.getKind() == Kind.CLASS</code> should be used instead.
     */
    @Deprecated
    public boolean isClass(ClassTree tree) {
        return (((JCTree.JCModifiers)tree.getModifiers()).flags & (Flags.INTERFACE | Flags.ENUM | Flags.ANNOTATION)) == 0;
    }
    
    /**Checks whether the given tree represents an interface.
     * @deprecated since 0.67, <code>Tree.getKind() == Kind.INTERFACE</code> should be used instead.
     */
    @Deprecated
    public boolean isInterface(ClassTree tree) {
        final long flags = ((JCTree.JCModifiers) tree.getModifiers()).flags;
        return (flags & Flags.INTERFACE) != 0 && (flags & Flags.ANNOTATION) == 0;
    }
    
    /**Checks whether the given tree represents an enum.
     * @deprecated since 0.67, <code>Tree.getKind() == Kind.ENUM</code> should be used instead.
     */
    @Deprecated
    public boolean isEnum(ClassTree tree) {
        return (((JCTree.JCModifiers)tree.getModifiers()).flags & Flags.ENUM) != 0;
    }

    /**
     * Checks wheteher given variable tree represents an enum constant.
     */
    public boolean isEnumConstant(VariableTree tree) {
        return (((JCTree.JCModifiers) tree.getModifiers()).flags & Flags.ENUM) != 0;
    }
    
    /**Checks whether the given tree represents an annotation.
     * @deprecated since 0.67, <code>Tree.getKind() == Kind.ANNOTATION_TYPE</code> should be used instead.
     */
    @Deprecated
    public boolean isAnnotation(ClassTree tree) {
        return (((JCTree.JCModifiers)tree.getModifiers()).flags & Flags.ANNOTATION) != 0;
    }
    
    /**Returns whether or not the given tree is synthetic - generated by the parser.
     * Please note that this method does not check trees transitively - a child of a syntetic tree
     * may be considered non-syntetic.
     * 
     * @return true if the given tree is synthetic, false otherwise
     * @throws NullPointerException if the given tree is null
     */
    public boolean isSynthetic(TreePath path) throws NullPointerException {
        if (path == null)
            throw new NullPointerException();
        
        while (path != null) {
            if (isSynthetic(path.getCompilationUnit(), path.getLeaf()))
                return true;

            path = path.getParentPath();
        }
        
        return false;
    }
    
    boolean isSynthetic(CompilationUnitTree cut, Tree leaf) throws NullPointerException {
        JCTree tree = (JCTree) leaf;
        
        if (tree.pos == (-1))
            return true;

        if (leaf.getKind() == Kind.METHOD) {
            //check for synthetic constructor:
            return (((JCMethodDecl)leaf).mods.flags & Flags.GENERATEDCONSTR) != 0L;
        }

        //check for synthetic superconstructor call:
        if (leaf.getKind() == Kind.EXPRESSION_STATEMENT) {
            ExpressionStatementTree est = (ExpressionStatementTree) leaf;
            
            if (est.getExpression().getKind() == Kind.METHOD_INVOCATION) {
                MethodInvocationTree mit = (MethodInvocationTree) est.getExpression();
                
                if (mit.getMethodSelect().getKind() == Kind.IDENTIFIER) {
                    IdentifierTree it = (IdentifierTree) mit.getMethodSelect();
                    
                    if ("super".equals(it.getName().toString())) {
                        SourcePositions sp = info.getTrees().getSourcePositions();
                        return sp.getEndPosition(cut, leaf) == (-1);
                    }
                }
            }
        }
        
        return false;
    }
    
    public TreePath pathFor(int pos) {
        return pathFor(new TreePath(info.getCompilationUnit()), pos);
    }

    /*XXX: dbalek
     */
    public TreePath pathFor(TreePath path, int pos) {
        return pathFor(path, pos, info.getTrees().getSourcePositions());
    }

    /*XXX: dbalek
     */
    @SuppressWarnings("fallthrough")
    public TreePath pathFor(TreePath path, int pos, SourcePositions sourcePositions) {
        if (info == null || path == null || sourcePositions == null)
            throw new IllegalArgumentException();
        
        class Result extends Error {
            private static final long serialVersionUID = 42L;
            TreePath path;
            Result(TreePath path) {
                this.path = path;
            }
        }
        
        class PathFinder extends TreePathScanner<Void,Void> {
            private int pos;
            private SourcePositions sourcePositions;
            
            private PathFinder(int pos, SourcePositions sourcePositions) {
                this.pos = pos;
                this.sourcePositions = sourcePositions;
            }
            
            public Void scan(Tree tree, Void p) {
                if (tree != null) {
                    if (sourcePositions.getStartPosition(getCurrentPath().getCompilationUnit(), tree) < pos && sourcePositions.getEndPosition(getCurrentPath().getCompilationUnit(), tree) >= pos) {
                        if (tree.getKind() == Tree.Kind.ERRONEOUS) {
                            tree.accept(this, p);
                            throw new Result(getCurrentPath());
                        }
                        super.scan(tree, p);
                        throw new Result(new TreePath(getCurrentPath(), tree));
                    }
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void p) {
                int[] span = findNameSpan(node);
                if (span != null && span[0] <= pos && pos < span[1]) {
                    throw new Result(getCurrentPath());
                }
                return super.visitVariable(node, p);
            }

            @Override
            public Void visitMethod(MethodTree node, Void p) {
                int[] span = findNameSpan(node);
                
                if (span != null && span[0] <= pos && pos < span[1]) {
                    throw new Result(getCurrentPath());
                }
                
                return super.visitMethod(node, p);
            }
        }
        
        try {
            new PathFinder(pos, sourcePositions).scan(path, null);
        } catch (Result result) {
            path = result.path;
        }
        
        if (path.getLeaf() == path.getCompilationUnit())
            return path;
        
        TokenSequence<JavaTokenId> tokenList = tokensFor(path.getLeaf(), sourcePositions);
        tokenList.moveEnd();
        if (tokenList.movePrevious() && tokenList.offset() < pos) {
            switch (tokenList.token().id()) {
                case GTGTGT:
                case GTGT:
                case GT:
                    if (path.getLeaf().getKind() == Tree.Kind.MEMBER_SELECT || TreeUtilities.CLASS_TREE_KINDS.contains(path.getLeaf().getKind()) || path.getLeaf().getKind() == Tree.Kind.GREATER_THAN)
                        break;
                case RPAREN:
                    if (path.getLeaf().getKind() == Tree.Kind.ENHANCED_FOR_LOOP || path.getLeaf().getKind() == Tree.Kind.FOR_LOOP ||
                            path.getLeaf().getKind() == Tree.Kind.IF || path.getLeaf().getKind() == Tree.Kind.WHILE_LOOP ||
                            path.getLeaf().getKind() == Tree.Kind.DO_WHILE_LOOP || path.getLeaf().getKind() == Tree.Kind.TYPE_CAST)
                        break;
                case SEMICOLON:
                    if (path.getLeaf().getKind() == Tree.Kind.FOR_LOOP &&
                            tokenList.offset() <= sourcePositions.getStartPosition(path.getCompilationUnit(), ((ForLoopTree)path.getLeaf()).getUpdate().get(0)))
                        break;
                case RBRACE:
                    path = path.getParentPath();
                    switch (path.getLeaf().getKind()) {
                        case CATCH:
                            path = path.getParentPath();
                        case METHOD:
                        case FOR_LOOP:
                        case ENHANCED_FOR_LOOP:
                        case SYNCHRONIZED:
                        case WHILE_LOOP:
                        case TRY:
                            path = path.getParentPath();
                            break;
                        case IF:
                            do {
                                path = path.getParentPath();
                            } while (path != null && path.getLeaf().getKind() == Tree.Kind.IF);
                            break;
                    }
                    break;
            }
        }
        return path;
    }

    /**Computes {@link Scope} for the given position.
     */
    @SuppressWarnings("fallthrough")
    public Scope scopeFor(int pos) {
        List<? extends StatementTree> stmts = null;
        SourcePositions sourcePositions = info.getTrees().getSourcePositions();
        TreePath path = pathFor(pos);
        CompilationUnitTree root = path.getCompilationUnit();
        switch (path.getLeaf().getKind()) {
            case BLOCK:
                stmts = ((BlockTree)path.getLeaf()).getStatements();
                break;
            case FOR_LOOP:
                stmts = ((ForLoopTree)path.getLeaf()).getInitializer();
                break;
            case ENHANCED_FOR_LOOP:
                stmts = Collections.singletonList(((EnhancedForLoopTree)path.getLeaf()).getStatement());
                break;
            case METHOD:
                stmts = ((MethodTree)path.getLeaf()).getParameters();
                break;
        }
        if (stmts != null) {
            Tree tree = null;
            for (StatementTree st : stmts) {
                if (sourcePositions.getStartPosition(root, st) < pos)
                    tree = st;
            }
            if (tree != null)
                path = new TreePath(path, tree);
        }
        Scope scope = info.getTrees().getScope(path);
        if (TreeUtilities.CLASS_TREE_KINDS.contains(path.getLeaf().getKind())) {
            TokenSequence<JavaTokenId> ts = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
            ts.move(pos);
            while(ts.movePrevious()) {
                switch (ts.token().id()) {
                    case WHITESPACE:
                    case LINE_COMMENT:
                    case BLOCK_COMMENT:
                    case JAVADOC_COMMENT:
                        break;
                    case EXTENDS:
                    case IMPLEMENTS:
                        ((JavacScope)scope).getEnv().baseClause = true;
                    default:
                        return scope;
                }
            }
        }
        return scope;
    }

    /**Returns tokens for a given tree.
     */
    public TokenSequence<JavaTokenId> tokensFor(Tree tree) {
        return tokensFor(tree, info.getTrees().getSourcePositions());
    }
    
    /**Returns tokens for a given tree. Uses specified {@link SourcePositions}.
     */
    public TokenSequence<JavaTokenId> tokensFor(Tree tree, SourcePositions sourcePositions) {
        int start = (int)sourcePositions.getStartPosition(info.getCompilationUnit(), tree);
        int end   = (int)sourcePositions.getEndPosition(info.getCompilationUnit(), tree);
        
        return info.getTokenHierarchy().tokenSequence(JavaTokenId.language()).subSequence(start, end);
    }
    
    /**Returns uncaught exceptions inside the given tree path.
     */
    public Set<TypeMirror> getUncaughtExceptions(TreePath path) {
        Set<TypeMirror> set = new UnrelatedTypeMirrorSet(info.getTypes());
        new UncaughtExceptionsVisitor(info).scan(path, set);
        return set;
    }
    
    /**Find span of the {@link ClassTree#getSimpleName()} identifier in the source.
     * Returns starting and ending offset of the name in the source code that was parsed
     * (ie. {@link CompilationInfo.getText()}, which may differ from the positions in the source
     * document if it has been already altered.
     * 
     * @param clazz class which name should be searched for
     * @return the span of the name, or null if cannot be found
     * @since 0.25
     */
    public int[] findNameSpan(ClassTree clazz) {
        return findNameSpan(clazz.getSimpleName().toString(), clazz, JavaTokenId.CLASS, JavaTokenId.INTERFACE, JavaTokenId.ENUM, JavaTokenId.AT, JavaTokenId.WHITESPACE, JavaTokenId.BLOCK_COMMENT, JavaTokenId.LINE_COMMENT, JavaTokenId.JAVADOC_COMMENT);
    }
    
    /**Find span of the {@link MethodTree#getName()} identifier in the source.
     * Returns starting and ending offset of the name in the source code that was parsed
     * (ie. {@link CompilationInfo.getText()}, which may differ from the positions in the source
     * document if it has been already altered.
     * 
     * @param method method which name should be searched for
     * @return the span of the name, or null if cannot be found
     * @since 0.25
     */
    public int[] findNameSpan(MethodTree method) {
        if (isSynthetic(info.getCompilationUnit(), method)) {
            return null;
        }
        JCMethodDecl jcm = (JCMethodDecl) method;
        String name;
        if (jcm.name == jcm.name.table.names.init) {
            TreePath path = info.getTrees().getPath(info.getCompilationUnit(), jcm);
            if (path == null) {
                return null;
            }
            Element em = info.getTrees().getElement(path);
            Element clazz;
            if (em == null || (clazz = em.getEnclosingElement()) == null || !clazz.getKind().isClass()) {
                return null;
            }
            
            name = clazz.getSimpleName().toString();
        } else {
            name = method.getName().toString();
        }
        return findNameSpan(name, method);
    }
    
    /**Find span of the {@link VariableTree#getName()} identifier in the source.
     * Returns starting and ending offset of the name in the source code that was parsed
     * (ie. {@link CompilationInfo.getText()}, which may differ from the positions in the source
     * document if it has been already altered.
     * 
     * @param var variable which name should be searched for
     * @return the span of the name, or null if cannot be found
     * @since 0.25
     */
    public int[] findNameSpan(VariableTree var) {
        return findNameSpan(var.getName().toString(), var);
    }
    
    /**Find span of the {@link MethodTree#getParameters()} parameter list in the source.
     * Returns the position of the opening and closing parentheses of the parameter list
     * in the source code that was parsed (ie. {@link CompilationInfo.getText()}, which
     * may differ from the positions in the source document if it has been already altered.
     * 
     * @param method method which parameter list should be searched for
     * @return the span of the parameter list, or null if cannot be found
     * @since 0.81
     */
    public int[] findMethodParameterSpan(MethodTree method) {
        JCTree jcTree = (JCTree) method;
        int pos = jcTree.pos;
        
        if (pos < 0)
            return null;
        
        TokenSequence<JavaTokenId> tokenSequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        tokenSequence.move(pos);
        
        int startPos = -1;
        int endPos = -1;
        while(tokenSequence.moveNext()) {
            if(tokenSequence.token().id() == JavaTokenId.LPAREN) {
                startPos = tokenSequence.offset();
            }
            if(tokenSequence.token().id() == JavaTokenId.RPAREN) {
                endPos = tokenSequence.offset();
                break;
            }
            if(tokenSequence.token().id() == JavaTokenId.LBRACE) {
                return null;
            }
        }
        
        if(startPos == -1 || endPos == -1) {
            return null;
        }
        
        return new int[] {
            startPos,
            endPos
        };
    }
    
    /**Find span of the {@link MemberSelectTree#getIdentifier()} identifier in the source.
     * Returns starting and ending offset of the name in the source code that was parsed
     * (ie. {@link CompilationInfo.getText()}, which may differ from the positions in the source
     * document if it has been already altered.
     * 
     * @param mst member select which identifier should be searched for
     * @return the span of the name, or null if cannot be found
     * @since 0.25
     */
    public int[] findNameSpan(MemberSelectTree mst) {
        return findNameSpan(mst.getIdentifier().toString(), mst, JavaTokenId.DOT, JavaTokenId.WHITESPACE, JavaTokenId.BLOCK_COMMENT, JavaTokenId.LINE_COMMENT, JavaTokenId.JAVADOC_COMMENT);
    }
    
    private int[] findNameSpan(String name, Tree t, JavaTokenId... allowedTokens) {
        if (!SourceVersion.isIdentifier(name)) {
            //names like "<error>", etc.
            return null;
        }
        
        JCTree jcTree = (JCTree) t;
        int pos = jcTree.pos;
        
        if (pos < 0)
            return null;
        
        Set<JavaTokenId> allowedTokensSet = EnumSet.noneOf(JavaTokenId.class);

        allowedTokensSet.addAll(Arrays.asList(allowedTokens));
        
        TokenSequence<JavaTokenId> tokenSequence = info.getTokenHierarchy().tokenSequence(JavaTokenId.language());
        
        tokenSequence.move(pos);
        
        boolean wasNext;
        
        while ((wasNext = tokenSequence.moveNext()) && allowedTokensSet.contains(tokenSequence.token().id()))
            ;
        
        if (wasNext) {
            if (tokenSequence.token().id() == JavaTokenId.IDENTIFIER &&
                name.contentEquals(tokenSequence.token().text())) {
                return new int[] {
                    tokenSequence.offset(),
                    tokenSequence.offset() + tokenSequence.token().length()
                };
            }
        }
        
        return null;
    }
    
    /**Find the target of <code>break</code> or <code>continue</code>. The given
     * {@link CompilationInfo} has to be at least in the {@link Phase#RESOLVED} phase.
     * 
     * @param breakOrContinue {@link TreePath} to the tree that should be inspected.
     *                        The <code>breakOrContinue.getLeaf().getKind()</code>
     *                        has to be either {@link Kind#BREAK} or {@link Kind#CONTINUE}, or
     *                        an IllegalArgumentException is thrown
     * @return the tree that is the "target" for the given break or continue statement, or null if there is none.
     * @throws IllegalArgumentException if the given tree is not a break or continue tree or if the given {@link CompilationInfo}
     *         is not in the {@link Phase#RESOLVED} phase.
     * @since 0.16
     */
    public StatementTree getBreakContinueTarget(TreePath breakOrContinue) throws IllegalArgumentException {
//        if (info.getPhase().compareTo(Phase.RESOLVED) < 0)
//            throw new IllegalArgumentException("Not in correct Phase. Required: Phase.RESOLVED, got: Phase." + info.getPhase().toString());
        
        Tree leaf = breakOrContinue.getLeaf();
        
        switch (leaf.getKind()) {
            case BREAK:
                return (StatementTree) ((JCTree.JCBreak) leaf).target;
            case CONTINUE:
                StatementTree target = (StatementTree) ((JCTree.JCContinue) leaf).target;
                if (target == null)
                    return null;
                
                if (((JCTree.JCContinue) leaf).label == null)
                    return target;
                
                TreePath tp = breakOrContinue;
                
                while (tp.getLeaf() != target) {
                    tp = tp.getParentPath();
                }
                Tree parent = tp.getParentPath().getLeaf();
                
                if (parent.getKind() == Kind.LABELED_STATEMENT) {
                    return (StatementTree) parent;
                } else {
                    return target;
                }
            default:
                throw new IllegalArgumentException("Unsupported kind: " + leaf.getKind());
        }
    }

    /**Decode escapes defined in: http://wikis.sun.com/display/mlvm/ProjectCoinProposal, 3.1-3.9.
     * Must be a full token text, including possible #".
     *
     * @param text to decode
     * @return decoded escapes from the identifier
     * @see http://wikis.sun.com/display/mlvm/ProjectCoinProposal
     * @since 0.56
     */
    public CharSequence decodeIdentifier(CharSequence text) {
        return decodeIdentifierInternal(text);
    }

    /**Encode identifier using escapes defined in: http://wikis.sun.com/display/mlvm/ProjectCoinProposal, 3.1-3.9.
     *
     * @param text to encode
     * @return encoded identifier, including #" if necessary
     * @see http://wikis.sun.com/display/mlvm/ProjectCoinProposal
     * @since 0.56
     */
    public CharSequence encodeIdentifier(CharSequence ident) {
        return encodeIdentifierInternal(ident);
    }

    static CharSequence decodeIdentifierInternal(CharSequence text) {
        if (text.charAt(0) != '#') {
            return text;
        }

        int count = text.charAt(text.length() - 1) == '"' ? text.length() - 1 : text.length();
        StringBuilder sb = new StringBuilder(text.length());

        for (int c = 2; c < count; c++) {
            if (text.charAt(c) == '\\' && ++c < count) {
                if (EXOTIC_ESCAPE.contains(text.charAt(c))) {
                    sb.append('\\');
                    sb.append(text.charAt(c));
                } else {
                    //XXX: handle \012
                    Character remaped = ESCAPE_UNENCODE.get(text.charAt(c));

                    if (remaped != null) {
                        sb.append(remaped);
                    } else {
                        //TODO: illegal?
                        sb.append(text.charAt(c));
                    }
                }
            } else {
                sb.append(text.charAt(c));
            }
        }

        return sb.toString();
    }

    static CharSequence encodeIdentifierInternal(CharSequence ident) {
        if (ident.length() == 0) {
            //???
            return ident;
        }

        StringBuilder sb = new StringBuilder(ident.length());
        boolean needsExotic = Character.isJavaIdentifierStart(ident.charAt(0));

        //XXX: code points?
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);

            if (Character.isJavaIdentifierPart(c)) {
                sb.append(c);
                continue;
            }

            needsExotic = true;

            Character target = ESCAPE_ENCODE.get(c);

            if (target != null) {
                sb.append('\\');
                sb.append(target);
            } else {
                sb.append(c);
            }
        }

        if (needsExotic) {
            sb.append("\"");
            sb.insert(0, "#\"");

            return sb.toString();
        } else {
            return ident;
        }
    }

    static Set<Character> EXOTIC_ESCAPE = new HashSet<Character>(
            Arrays.<Character>asList('!', '#', '$', '%', '&', '(', ')', '*', '+', ',', '-',
                                     ':', '=', '?', '@', '^', '_', '`', '{', '|', '}')
    );

    private static final Map<Character, Character> ESCAPE_UNENCODE;
    private static final Map<Character, Character> ESCAPE_ENCODE;

    static {
        Map<Character, Character> unencode = new HashMap<Character, Character>();

        unencode.put('n', '\n');
        unencode.put('t', '\t');
        unencode.put('b', '\b');
        unencode.put('r', '\r');

        ESCAPE_UNENCODE = Collections.unmodifiableMap(unencode);

        Map<Character, Character> encode = new HashMap<Character, Character>();

        encode.put('\n', 'n');
        encode.put('\t', 't');
        encode.put('\b', 'b');
        encode.put('\r', 'r');

        ESCAPE_ENCODE = Collections.unmodifiableMap(encode);
    }

    /**Returns new tree based on {@code original}, such that each visited subtree
     * that occurs as a key in {@code original2Translated} is replaced by the corresponding
     * value from {@code original2Translated}. The value is then translated using the same
     * algorithm. Each key from {@code original2Translated} is used at most once.
     * Unless the provided {@code original} tree is a key in {@code original2Translated},
     * the resulting tree has the same type as {@code original}.
     *
     * Principally, the method inner workings are:
     * <pre>
     * translate(original, original2Translated) {
     *      if (original2Translated.containsKey(original))
     *          return translate(original2Translated.remove(original));
     *      newTree = copyOf(original);
     *      for (Tree child : allChildrenOf(original)) {
     *          newTree.replace(child, translate(child, original2Translated));
     *      }
     *      return newTree;
     * }
     * </pre>
     * 
     * @param original the tree that should be translated
     * @param original2Translated map containing trees that should be translated
     * @return translated tree.
     * @since 0.64
     */
    private static class UncaughtExceptionsVisitor extends TreePathScanner<Void, Set<TypeMirror>> {
        
        private final CompilationInfo info;
        
        private UncaughtExceptionsVisitor(final CompilationInfo info) {
            this.info = info;
        }
    
        public Void visitMethodInvocation(MethodInvocationTree node, Set<TypeMirror> p) {
            super.visitMethodInvocation(node, p);
            Element el = info.getTrees().getElement(getCurrentPath());
            if (el != null && el.getKind() == ElementKind.METHOD)
                p.addAll(((ExecutableElement)el).getThrownTypes());
            return null;
        }

        public Void visitNewClass(NewClassTree node, Set<TypeMirror> p) {
            super.visitNewClass(node, p);
            Element el = info.getTrees().getElement(getCurrentPath());
            if (el != null && el.getKind() == ElementKind.CONSTRUCTOR)
                p.addAll(((ExecutableElement)el).getThrownTypes());
            return null;
        }

        public Void visitThrow(ThrowTree node, Set<TypeMirror> p) {
            super.visitThrow(node, p);
            TypeMirror tm = info.getTrees().getTypeMirror(new TreePath(getCurrentPath(), node.getExpression()));
            if (tm != null) {
                if (tm.getKind() == TypeKind.DECLARED)
                    p.add(tm);
                else if (tm.getKind() == TypeKind.UNION)
                    p.addAll(((UnionType)tm).getAlternatives());
            }
            return null;
        }

        public Void visitTry(TryTree node, Set<TypeMirror> p) {
            Set<TypeMirror> s = new LinkedHashSet<TypeMirror>();
            scan(node.getBlock(), s);
            Set<TypeMirror> c = new LinkedHashSet<TypeMirror>();
            for (CatchTree ct : node.getCatches()) {
                TypeMirror t = info.getTrees().getTypeMirror(new TreePath(getCurrentPath(), ct.getParameter().getType()));
                if (t != null) {
                    if (t.getKind() == TypeKind.UNION) {
                        for (TypeMirror tm : ((UnionType)t).getAlternatives()) {
                            if (tm != null && tm.getKind() != TypeKind.ERROR)
                                c.add(tm);
                        }
                    } else if (t.getKind() != TypeKind.ERROR) {
                        c.add(t);
                    }
                }
            }
            for (TypeMirror t : c) {
                for (Iterator<TypeMirror> it = s.iterator(); it.hasNext();) {
                    if (info.getTypes().isSubtype(it.next(), t))
                        it.remove();
                }
           }
            p.addAll(s);
            scan(node.getCatches(), p);
            scan(node.getFinallyBlock(), p);
            return null;            
        }

        public Void visitMethod(MethodTree node, Set<TypeMirror> p) {
            Set<TypeMirror> s = new LinkedHashSet<TypeMirror>();
            scan(node.getBody(), s);
            for (ExpressionTree et : node.getThrows()) {
                TypeMirror t = info.getTrees().getTypeMirror(new TreePath(getCurrentPath(), et));
                if (t != null && t.getKind() != TypeKind.ERROR) {
                    for (Iterator<TypeMirror> it = s.iterator(); it.hasNext();)
                        if (info.getTypes().isSubtype(it.next(), t))
                            it.remove();
                }
            }
            p.addAll(s);
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Set<TypeMirror> p) {
            return null;
        }

    }
    
    private static class UnrelatedTypeMirrorSet extends AbstractSet<TypeMirror> {

        private Types types;
        private LinkedList<TypeMirror> list = new LinkedList<TypeMirror>();

        public UnrelatedTypeMirrorSet(Types types) {
            this.types = types;
        }

        @Override
        public boolean add(TypeMirror typeMirror) {
            for (ListIterator<TypeMirror> it = list.listIterator(); it.hasNext(); ) {
                TypeMirror tm = it.next();
                if (types.isSubtype(typeMirror, tm))
                    return false;
                if (types.isSubtype(tm, typeMirror))
                    it.remove();                    
            }
            return list.add(typeMirror);
        }
                
        @Override
        public Iterator<TypeMirror> iterator() {
            return list.iterator();
        }

        @Override
        public int size() {
            return list.size();
        }
    }
}
