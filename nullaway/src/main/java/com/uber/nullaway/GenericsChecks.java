package com.uber.nullaway;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeMetadata;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Methods for performing checks related to generic types and nullability. */
public final class GenericsChecks {

  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";

  private static final Supplier<Type> NULLABLE_TYPE_SUPPLIER =
      Suppliers.typeFromString(NULLABLE_NAME);
  VisitorState state;
  Config config;
  NullAway analysis;

  public GenericsChecks(VisitorState state, Config config, NullAway analysis) {
    this.state = state;
    this.config = config;
    this.analysis = analysis;
  }

  /**
   * Checks that for an instantiated generic type, {@code @Nullable} types are only used for type
   * variables that have a {@code @Nullable} upper bound.
   *
   * @param tree the tree representing the instantiated type
   * @param state visitor state
   * @param analysis the analysis object
   * @param config the analysis config
   */
  public static void checkInstantiationForParameterizedTypedTree(
      ParameterizedTypeTree tree, VisitorState state, NullAway analysis, Config config) {
    if (!config.isJSpecifyMode()) {
      return;
    }
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    if (typeArguments.size() == 0) {
      return;
    }
    Map<Integer, Tree> nullableTypeArguments = new HashMap<>();
    for (int i = 0; i < typeArguments.size(); i++) {
      Tree curTypeArg = typeArguments.get(i);
      if (curTypeArg instanceof AnnotatedTypeTree) {
        AnnotatedTypeTree annotatedType = (AnnotatedTypeTree) curTypeArg;
        for (AnnotationTree annotation : annotatedType.getAnnotations()) {
          Type annotationType = ASTHelpers.getType(annotation);
          if (annotationType != null
              && Nullness.isNullableAnnotation(annotationType.toString(), config)) {
            nullableTypeArguments.put(i, curTypeArg);
            break;
          }
        }
      }
    }
    // base type that is being instantiated
    Type baseType = ASTHelpers.getType(tree);
    if (baseType == null) {
      return;
    }
    com.sun.tools.javac.util.List<Type> baseTypeArgs = baseType.tsym.type.getTypeArguments();
    for (int i = 0; i < baseTypeArgs.size(); i++) {
      if (nullableTypeArguments.containsKey(i)) {

        Type typeVariable = baseTypeArgs.get(i);
        Type upperBound = typeVariable.getUpperBound();
        com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrors =
            upperBound.getAnnotationMirrors();
        boolean hasNullableAnnotation =
            Nullness.hasNullableAnnotation(annotationMirrors.stream(), config);
        // if base type argument does not have @Nullable annotation then the instantiation is
        // invalid
        if (!hasNullableAnnotation) {
          reportInvalidInstantiationError(
              nullableTypeArguments.get(i), baseType, typeVariable, state, analysis);
        }
      }
    }
  }

  private static void reportInvalidInstantiationError(
      Tree tree, Type baseType, Type baseTypeVariable, VisitorState state, NullAway analysis) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.TYPE_PARAMETER_CANNOT_BE_NULLABLE,
            String.format(
                "Generic type parameter cannot be @Nullable, as type variable %s of type %s does not have a @Nullable upper bound",
                baseTypeVariable.tsym.toString(), baseType.tsym.toString()));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  private static void reportInvalidAssignmentInstantiationError(
      Tree tree, Type lhsType, Type rhsType, VisitorState state, NullAway analysis) {
    ErrorBuilder errorBuilder = analysis.getErrorBuilder();
    ErrorMessage errorMessage =
        new ErrorMessage(
            ErrorMessage.MessageTypes.ASSIGN_GENERIC_NULLABLE,
            String.format(
                "Cannot assign from type "
                    + rhsType
                    + " to type "
                    + lhsType
                    + " due to mismatched nullability of type parameters"));
    state.reportMatch(
        errorBuilder.createErrorDescription(
            errorMessage, analysis.buildDescription(tree), state, null));
  }

  /**
   * For a tree representing an assignment, ensures that from the perspective of type parameter
   * nullability, the type of the right-hand side is assignable to (a subtype of) the type of the
   * left-hand side. This check ensures that for every parameterized type nested in each of the
   * types, the type parameters have identical nullability.
   *
   * @param tree the tree to check, which must be either an {@link AssignmentTree} or a {@link
   *     VariableTree}
   */
  public void checkTypeParameterNullnessForAssignability(Tree tree) {
    if (!config.isJSpecifyMode()) {
      return;
    }
    Tree lhsTree;
    Tree rhsTree;
    if (tree instanceof VariableTree) {
      VariableTree varTree = (VariableTree) tree;
      lhsTree = varTree.getType();
      rhsTree = varTree.getInitializer();
    } else {
      AssignmentTree assignmentTree = (AssignmentTree) tree;
      lhsTree = assignmentTree.getVariable();
      rhsTree = assignmentTree.getExpression();
    }
    // rhsTree can be null for a VariableTree.  Also, we don't need to do a check
    // if rhsTree is the null literal
    if (rhsTree == null || rhsTree.getKind().equals(Tree.Kind.NULL_LITERAL)) {
      return;
    }
    Type lhsType = ASTHelpers.getType(lhsTree);
    Type rhsType = ASTHelpers.getType(rhsTree);
    // For NewClassTrees with annotated type parameters, javac does not preserve the annotations in
    // its computed type for the expression.  As a workaround, we construct a replacement Type
    // object with the appropriate annotations.
    if (rhsTree instanceof NewClassTree
        && ((NewClassTree) rhsTree).getIdentifier() instanceof ParameterizedTypeTree) {
      ParameterizedTypeTree paramTypedTree =
          (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier();
      if (paramTypedTree.getTypeArguments().isEmpty()) {
        // no explicit type parameters
        return;
      }
      rhsType =
          typeWithPreservedAnnotations(
              (ParameterizedTypeTree) ((NewClassTree) rhsTree).getIdentifier());
    }
    if (lhsType != null
        && rhsType != null
        && lhsType instanceof Type.ClassType
        && rhsType instanceof Type.ClassType) {
      compareNullabilityAnnotations((Type.ClassType) lhsType, (Type.ClassType) rhsType, tree);
    }
  }

  public void checkTypeParameterNullnessForFunctionReturnType(MethodTree tree) {
    if (!config.isJSpecifyMode()) {
      return;
    }
    if (tree.getBody() == null) {
      return;
    }
    // find the return statements in the method body and compare the annotations with the method
    // return type
    checkForReturnStatements(tree.getBody(), ASTHelpers.getType(tree.getReturnType()));
  }

  public void checkForReturnStatements(BlockTree body, Type methodType) {
    if (body == null) {
      return;
    }
    List<? extends StatementTree> methodStatements = body.getStatements();
    if (methodStatements == null) {
      return;
    }
    for (int i = 0; i < methodStatements.size(); i++) {
      StatementTree statement = methodStatements.get(i);
      // if it is a return statement then compare the Nullability annotations for the parameters
      if (statement instanceof JCTree.JCReturn) {
        System.out.println(methodStatements.get(i));
        Tree returnStatementTree = ((JCTree.JCReturn) methodStatements.get(i)).getExpression();
        Type returnType = ASTHelpers.getType(returnStatementTree);
        // getting the type of the Parameterized type tree with the preserved annotations.
        if (returnStatementTree instanceof NewClassTree
            && ((NewClassTree) returnStatementTree).getIdentifier()
                instanceof ParameterizedTypeTree) {
          returnType =
              typeWithPreservedAnnotations(
                  (ParameterizedTypeTree) ((NewClassTree) returnStatementTree).getIdentifier());
        }
        if (returnType != null
            && methodType != null
            && returnType instanceof Type.ClassType
            && methodType instanceof Type.ClassType) {
          compareNullabilityAnnotations(
              (Type.ClassType) methodType, (Type.ClassType) returnType, returnStatementTree);
        }
      } else if (statement instanceof JCTree.JCIf) { // if the statement is an if else block
        JCTree.JCIf ifBlock = (JCTree.JCIf) statement;
        checkForReturnStatements((BlockTree) ifBlock.thenpart, methodType);
        checkForReturnStatements((BlockTree) ifBlock.elsepart, methodType);
      } else if (statement instanceof JCTree.JCForLoop) {
        JCTree.JCForLoop loop = (JCTree.JCForLoop) statement;
        checkForReturnStatements((BlockTree) loop.body, methodType);
      } else if (statement instanceof JCTree.JCWhileLoop) {
        JCTree.JCWhileLoop loop = (JCTree.JCWhileLoop) statement;
        checkForReturnStatements((BlockTree) loop.body, methodType);
      } else if (statement instanceof JCTree.JCDoWhileLoop) {
        JCTree.JCDoWhileLoop loop = (JCTree.JCDoWhileLoop) statement;
        checkForReturnStatements((BlockTree) loop.body, methodType);
      }
    }
  }
  /**
   * Compare two types from an assignment for identical type parameter nullability, recursively
   * checking nested generic types. See <a
   * href="https://jspecify.dev/docs/spec/#nullness-delegating-subtyping">the JSpecify
   * specification</a> and <a
   * href="https://docs.oracle.com/javase/specs/jls/se14/html/jls-4.html#jls-4.10.2">the JLS
   * subtyping rules for class and interface types</a>.
   *
   * @param lhsType type for the lhs of the assignment
   * @param rhsType type for the rhs of the assignment
   * @param tree tree representing the assignment
   */
  private void compareNullabilityAnnotations(
      Type.ClassType lhsType, Type.ClassType rhsType, Tree tree) {
    Types types = state.getTypes();
    // The base type of rhsType may be a subtype of lhsType's base type.  In such cases, we must
    // compare lhsType against the supertype of rhsType with a matching base type.
    rhsType = (Type.ClassType) types.asSuper(rhsType, lhsType.tsym);
    if (rhsType == null) {
      throw new RuntimeException("did not find supertype of " + rhsType + " matching " + lhsType);
    }
    List<Type> lhsTypeArguments = lhsType.getTypeArguments();
    List<Type> rhsTypeArguments = rhsType.getTypeArguments();
    if (lhsTypeArguments.size() != rhsTypeArguments.size()) {
      throw new RuntimeException(
          "number of types arguments in " + rhsType + " does not match " + lhsType);
    }
    for (int i = 0; i < lhsTypeArguments.size(); i++) {
      Type lhsTypeArgument = lhsTypeArguments.get(i);
      Type rhsTypeArgument = rhsTypeArguments.get(i);
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsLHS =
          lhsTypeArgument.getAnnotationMirrors();
      com.sun.tools.javac.util.List<Attribute.TypeCompound> annotationMirrorsRHS =
          rhsTypeArgument.getAnnotationMirrors();
      boolean isLHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsLHS.stream(), config);
      boolean isRHSNullableAnnotated =
          Nullness.hasNullableAnnotation(annotationMirrorsRHS.stream(), config);
      if (isLHSNullableAnnotated != isRHSNullableAnnotated) {
        reportInvalidAssignmentInstantiationError(tree, lhsType, rhsType, state, analysis);
        return;
      }
      // nested generics
      if (lhsTypeArgument.getTypeArguments().length() > 0) {
        compareNullabilityAnnotations(
            (Type.ClassType) lhsTypeArgument, (Type.ClassType) rhsTypeArgument, tree);
      }
    }
  }

  /**
   * @param tree A parameterized typed tree for which we need class type with preserved annotations.
   * @return A Type with preserved annotations.
   */
  private Type.ClassType typeWithPreservedAnnotations(ParameterizedTypeTree tree) {
    Type.ClassType type = (Type.ClassType) ASTHelpers.getType(tree);
    Preconditions.checkNotNull(type);
    Type nullableType = NULLABLE_TYPE_SUPPLIER.get(state);
    List<? extends Tree> typeArguments = tree.getTypeArguments();
    List<Type> newTypeArgs = new ArrayList<>();
    boolean hasNullableAnnotation = false;
    for (int i = 0; i < typeArguments.size(); i++) {
      AnnotatedTypeTree annotatedType = null;
      Tree curTypeArg = typeArguments.get(i);
      // If the type argument has an annotation, it will either be an AnnotatedTypeTree, or a
      // ParameterizedTypeTree in the case of a nested generic type
      if (curTypeArg instanceof AnnotatedTypeTree) {
        annotatedType = (AnnotatedTypeTree) curTypeArg;
      } else if (curTypeArg instanceof ParameterizedTypeTree
          && ((ParameterizedTypeTree) curTypeArg).getType() instanceof AnnotatedTypeTree) {
        annotatedType = (AnnotatedTypeTree) ((ParameterizedTypeTree) curTypeArg).getType();
      }
      List<? extends AnnotationTree> annotations =
          annotatedType != null ? annotatedType.getAnnotations() : Collections.emptyList();
      for (AnnotationTree annotation : annotations) {
        if (ASTHelpers.isSameType(
            nullableType, ASTHelpers.getType(annotation.getAnnotationType()), state)) {
          hasNullableAnnotation = true;
          break;
        }
      }
      // construct a TypeMetadata object containing a nullability annotation if needed
      com.sun.tools.javac.util.List<Attribute.TypeCompound> nullableAnnotationCompound =
          hasNullableAnnotation
              ? com.sun.tools.javac.util.List.from(
                  Collections.singletonList(
                      new Attribute.TypeCompound(
                          nullableType, com.sun.tools.javac.util.List.nil(), null)))
              : com.sun.tools.javac.util.List.nil();
      TypeMetadata typeMetadata =
          new TypeMetadata(new TypeMetadata.Annotations(nullableAnnotationCompound));
      Type currentTypeArgType = castToNonNull(ASTHelpers.getType(curTypeArg));
      if (currentTypeArgType.getTypeArguments().size() > 0) {
        // nested generic type; recursively preserve its nullability type argument annotations
        currentTypeArgType = typeWithPreservedAnnotations((ParameterizedTypeTree) curTypeArg);
      }
      Type.ClassType newTypeArgType =
          (Type.ClassType) currentTypeArgType.cloneWithMetadata(typeMetadata);
      newTypeArgs.add(newTypeArgType);
    }
    Type.ClassType finalType =
        new Type.ClassType(
            type.getEnclosingType(), com.sun.tools.javac.util.List.from(newTypeArgs), type.tsym);
    return finalType;
  }
}
