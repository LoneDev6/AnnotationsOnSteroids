// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package devs.beer.asteroids

import devs.beer.asteroids.AnnotatedApiUsageUtil.findAnnotatedTypeUsedInDeclarationSignature
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.util.SpecialAnnotationsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiField
import com.intellij.psi.util.PropertyUtil
import com.intellij.uast.UastVisitorAdapter
import com.intellij.util.ui.FormBuilder
import com.siyeh.ig.ui.ExternalizableStringSet
import devs.beer.asteroids.ExpensiveApiUsageInspection.Companion.DEFAULT_EXPENSIVE_API_ANNOTATIONS
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Reports declarations of classes, method and fields that in their signatures refer to a type marked with "expensive API" annotation.
 *
 * For example, if an expensive class is used as the return type of a method, the method must also be expensive because
 * incompatible changes of the class (remove or move to another package) lead to incompatible signature changes.
 */
class ExpensiveTypeUsedInSignatureInspection : LocalInspectionTool() {

    @JvmField
    val expensiveApiAnnotations: MutableList<String> = ExternalizableStringSet(*DEFAULT_EXPENSIVE_API_ANNOTATIONS.toTypedArray())

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (expensiveApiAnnotations.none { AnnotatedApiUsageUtil.canAnnotationBeUsedInFile(it, holder.file) }) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return UastVisitorAdapter(ExpensiveTypeUsedInSignatureVisitor(holder, expensiveApiAnnotations.toList()), true)
    }

    override fun createOptionsPanel(): JPanel {
        val annotationsListControl = SpecialAnnotationsUtil.createSpecialAnnotationsListControl(
            expensiveApiAnnotations,
            AnnotationsOnSteroidsBundle.message("jvm.inspections.expensive.api.usage.annotations.list")
        )

        val formBuilder = FormBuilder.createFormBuilder()
        formBuilder.addComponent(annotationsListControl)

        val container = JPanel(BorderLayout())
        container.add(formBuilder.panel, BorderLayout.NORTH)
        return container
    }
}

private class ExpensiveTypeUsedInSignatureVisitor(
    private val problemsHolder: ProblemsHolder,
    private val expensiveApiAnnotations: List<String>
) : AbstractUastNonRecursiveVisitor() {

    override fun visitDeclaration(node: UDeclaration): Boolean {
        if (node !is UClass && node !is UMethod && node !is UField) {
            return false
        }
        if (!isAccessibleDeclaration(node) || isInsideExpensiveDeclaration(node)) {
            return true
        }
        val annotatedTypeUsedInSignature =
            findAnnotatedTypeUsedInDeclarationSignature(node, expensiveApiAnnotations) ?: return true

        val elementToHighlight = node.uastAnchor.sourcePsiElement ?: return true
        val typeName = (annotatedTypeUsedInSignature.target as? PsiClass)?.qualifiedName ?: return true
        val annotationName = annotatedTypeUsedInSignature.psiAnnotation.qualifiedName ?: return true

        val message = when (node) {
            is UMethod -> AnnotationsOnSteroidsBundle.message(
                "jvm.inspections.expensive.type.used.in.method.signature.description", annotationName, typeName
            )
            is UField -> AnnotationsOnSteroidsBundle.message(
                "jvm.inspections.expensive.type.used.in.field.signature.description", annotationName, typeName
            )
            else -> AnnotationsOnSteroidsBundle.message(
                "jvm.inspections.expensive.type.used.in.class.signature.description", annotationName, typeName
            )
        }
        problemsHolder.registerProblem(elementToHighlight, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        return true
    }

    private fun isAccessibleDeclaration(node: UDeclaration): Boolean {
        if (node.visibility == UastVisibility.PRIVATE || node.visibility == UastVisibility.PACKAGE_LOCAL) {
            if (node is UField) {
                //Kotlin properties are private UFields with accompanying getters\setters.
                val psiField = node.javaPsi
                if (psiField is PsiField) {
                    val getter = PropertyUtil.findGetterForField(psiField)?.toUElement(UMethod::class.java)
                    val setter = PropertyUtil.findSetterForField(psiField)?.toUElement(UMethod::class.java)
                    return getter != null && isAccessibleDeclaration(getter) || setter != null && isAccessibleDeclaration(
                        setter
                    )
                }
            }
            return false
        }
        val containingDeclaration = node.getParentOfType<UDeclaration>()
        if (containingDeclaration != null) {
            return isAccessibleDeclaration(containingDeclaration)
        }
        return true
    }

    private fun isInsideExpensiveDeclaration(node: UDeclaration): Boolean {
        if (node.uAnnotations.any { it.qualifiedName in expensiveApiAnnotations }) {
            return true
        }
        val containingClass = node.getContainingUClass()
        if (containingClass != null) {
            return isInsideExpensiveDeclaration(containingClass)
        }
        val containingUFile = node.getContainingUFile()
        if (containingUFile != null) {
            val packageName = containingUFile.packageName
            val psiPackage = JavaPsiFacade.getInstance(problemsHolder.project).findPackage(packageName)
            if (psiPackage != null) {
                return expensiveApiAnnotations.any { psiPackage.hasAnnotation(it) }
            }
        }
        return false
    }
}
