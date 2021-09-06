// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package devs.beer.asteroids

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.apiUsage.ApiUsageProcessor
import com.intellij.codeInspection.apiUsage.ApiUsageUastVisitor
import com.intellij.codeInspection.deprecation.DeprecationInspection
import com.intellij.codeInspection.ui.MultipleCheckboxOptionsPanel
import com.intellij.codeInspection.util.SpecialAnnotationsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiLiteralExpressionImpl
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.util.parents
import com.intellij.util.ArrayUtilRt
import com.siyeh.ig.ui.ExternalizableStringSet
import devs.beer.asteroids.AnnotatedApiUsageUtil.findAnnotatedContainingDeclaration
import devs.beer.asteroids.AnnotatedApiUsageUtil.findAnnotatedTypeUsedInDeclarationSignature
import org.jetbrains.uast.*
import javax.swing.JPanel

enum class MsgType {
    SINGLE_CALL,
    LOOP,
    LAMBDA
}

class ExpensiveApiUsageInspection : LocalInspectionTool() {

    companion object {
        val DEFAULT_EXPENSIVE_API_ANNOTATIONS: List<String> = listOf(
            "asteroids.Expensive"
        )
    }

    @JvmField
    val expensiveApiAnnotations: List<String> = ExternalizableStringSet(
        *ArrayUtilRt.toStringArray(DEFAULT_EXPENSIVE_API_ANNOTATIONS)
    )

    @JvmField
    var myIgnoreInsideImports: Boolean = false

    @JvmField
    var myIgnoreApiDeclaredInThisProject: Boolean = false

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val annotations = expensiveApiAnnotations.toList()
        return if (annotations.any { AnnotatedApiUsageUtil.canAnnotationBeUsedInFile(it, holder.file) }) {
            ApiUsageUastVisitor.createPsiElementVisitor(
                ExpensiveApiUsageProcessor(
                    holder,
                    myIgnoreInsideImports,
                    myIgnoreApiDeclaredInThisProject,
                    annotations
                )
            )
        } else {
            PsiElementVisitor.EMPTY_VISITOR
        }
    }

    override fun createOptionsPanel(): JPanel {
        val panel = MultipleCheckboxOptionsPanel(this)
        panel.addCheckbox(AnnotationsOnSteroidsBundle.message("jvm.inspections.expensive.api.usage.ignore.inside.imports"), "myIgnoreInsideImports")
        panel.addCheckbox(AnnotationsOnSteroidsBundle.message("jvm.inspections.expensive.api.usage.ignore.declared.inside.this.project"), "myIgnoreApiDeclaredInThisProject")

        //TODO in add annotation window "Include non-project items" should be enabled by default
        val annotationsListControl = SpecialAnnotationsUtil.createSpecialAnnotationsListControl(
            expensiveApiAnnotations,
            AnnotationsOnSteroidsBundle.message("jvm.inspections.expensive.api.usage.annotations.list")
        )

        panel.add(annotationsListControl/*, "growx, wrap"*/)
        return panel
    }
}

private class ExpensiveApiUsageProcessor(
    private val problemsHolder: ProblemsHolder,
    private val ignoreInsideImports: Boolean,
    private val ignoreApiDeclaredInThisProject: Boolean,
    private val expensiveApiAnnotations: List<String>
) : ApiUsageProcessor {

    private companion object {
        fun isLibraryElement(element: PsiElement): Boolean {
            if (ApplicationManager.getApplication().isUnitTestMode) {
                return true
            }
            val containingVirtualFile = PsiUtilCore.getVirtualFile(element)
            return containingVirtualFile != null && ProjectFileIndex.getInstance(element.project).isInLibraryClasses(containingVirtualFile)
        }
    }

    override fun processImportReference(sourceNode: UElement, target: PsiModifierListOwner) {
        if (!ignoreInsideImports) {
            checkExpensiveApiUsage(target, sourceNode, false)
        }
    }

    override fun processReference(sourceNode: UElement, target: PsiModifierListOwner, qualifier: UExpression?) {
        checkExpensiveApiUsage(target, sourceNode, false)
    }

    override fun processConstructorInvocation(
        sourceNode: UElement,
        instantiatedClass: PsiClass,
        constructor: PsiMethod?,
        subclassDeclaration: UClass?
    ) {
        if (constructor != null) {
            checkExpensiveApiUsage(constructor, sourceNode, false)
        }
    }

    override fun processMethodOverriding(method: UMethod, overriddenMethod: PsiMethod) {
        checkExpensiveApiUsage(overriddenMethod, method, true)
    }

    private fun getMessageProvider(psiAnnotation: PsiAnnotation): ExpensiveApiUsageMessageProvider? {
//        val annotationName = psiAnnotation.qualifiedName ?: return null
        return DefaultExpensiveApiUsageMessageProvider
    }

    private fun getElementToHighlight(sourceNode: UElement): PsiElement? =
        (sourceNode as? UDeclaration)?.uastAnchor.sourcePsiElement ?: sourceNode.sourcePsi

    private fun checkExpensiveApiUsage(target: PsiModifierListOwner, sourceNode: UElement, isMethodOverriding: Boolean) {
        if (ignoreApiDeclaredInThisProject && !isLibraryElement(target)) {
            return
        }

        if (checkTargetIsExpensiveItself(target, sourceNode, isMethodOverriding)) {
            return
        }

        checkTargetReferencesExpensiveeTypeInSignature(target, sourceNode, isMethodOverriding)
    }

    private fun checkTargetIsExpensiveItself(target: PsiModifierListOwner, sourceNode: UElement, isMethodOverriding: Boolean): Boolean {
        val annotatedContainingDeclaration = findAnnotatedContainingDeclaration(target, expensiveApiAnnotations, true)
        if (annotatedContainingDeclaration != null) {
            val messageProvider = getMessageProvider(annotatedContainingDeclaration.psiAnnotation) ?: return false
            val elementToHighlight = getElementToHighlight(sourceNode) ?: return false

            val singleCall = getAnnotationBooleanAttributeValue(annotatedContainingDeclaration, "singleCall", true);
            val calledInLoop = getAnnotationBooleanAttributeValue(annotatedContainingDeclaration, "calledInLoop", true)
            val calledInLambda = getAnnotationBooleanAttributeValue(annotatedContainingDeclaration, "calledInLambda", true)

            if(calledInLoop == true)
            {
                    for (parent in elementToHighlight.parents) {
                        if(parent is PsiForStatement
                            || parent is PsiForeachStatement
                            || parent is PsiWhileStatement
                            || parent is PsiDoWhileStatement)
                        {
                            return registerProblem(
                                elementToHighlight,
                                messageProvider.highlightTypeLoop,
                                isMethodOverriding,
                                messageProvider,
                                annotatedContainingDeclaration,
                                MsgType.LOOP
                            )
                        }
                    }
            }

            if(calledInLambda == true)
            {
                for (parent in elementToHighlight.parents) {
                    if(parent is PsiLambdaExpression)
                    {
                        return registerProblem(
                            elementToHighlight,
                            messageProvider.highlightTypeLambda,
                            isMethodOverriding,
                            messageProvider,
                            annotatedContainingDeclaration,
                            MsgType.LAMBDA
                        )
                    }
                }
            }

            if(singleCall == true)
                return registerProblem(
                    elementToHighlight,
                    messageProvider.highlightTypeSingleCall,
                    isMethodOverriding,
                    messageProvider,
                    annotatedContainingDeclaration,
                    MsgType.SINGLE_CALL
                )
        }
        return false
    }

    private fun getAnnotationBooleanAttributeValue(
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        attributeName: String,
        defaultVal: Boolean
    ): Boolean? {
        val v = annotatedContainingDeclaration.psiAnnotation.findAttributeValue(attributeName) ?: return defaultVal
        return (v as PsiLiteralExpressionImpl).value as Boolean?
    }

    private fun registerProblem(
        elementToHighlight: PsiElement,
        problemHighlightType: ProblemHighlightType,
        isMethodOverriding: Boolean,
        messageProvider: ExpensiveApiUsageMessageProvider,
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        msgType: MsgType
    ): Boolean {

        val message = if (isMethodOverriding) {
            messageProvider.buildMessageExpensiveMethodOverridden(annotatedContainingDeclaration, msgType)
        }
        else {
            messageProvider.buildMessage(annotatedContainingDeclaration, msgType)
        }

        problemsHolder.registerProblem(elementToHighlight, message, problemHighlightType)
        return true
    }

    private fun checkTargetReferencesExpensiveeTypeInSignature(target: PsiModifierListOwner, sourceNode: UElement, isMethodOverriding: Boolean) {
        if (!isMethodOverriding && !arePsiElementsFromTheSameFile(sourceNode.sourcePsi, target.containingFile)) {
            val declaration = target.toUElement(UDeclaration::class.java)
            if (declaration !is UClass && declaration !is UMethod && declaration !is UField) {
                return
            }
            val expensiveTypeUsedInSignature = findAnnotatedTypeUsedInDeclarationSignature(declaration, expensiveApiAnnotations)
            if (expensiveTypeUsedInSignature != null) {
                val messageProvider = getMessageProvider(expensiveTypeUsedInSignature.psiAnnotation) ?: return
                val message = messageProvider.buildMessageExpensiveTypeIsUsedInSignatureOfReferencedApi(target, expensiveTypeUsedInSignature)
                val elementToHighlight = getElementToHighlight(sourceNode) ?: return
                problemsHolder.registerProblem(elementToHighlight, message, messageProvider.highlightTypeSingleCall)
            }
        }
    }

    private fun arePsiElementsFromTheSameFile(one: PsiElement?, two: PsiElement?): Boolean {
        //For Kotlin: naive comparison of PSI containingFile-s does not work because one of the PSI elements might be light PSI element
        // coming from a light PSI file, and another element would be physical PSI file, and they are not "equals()".
        return one?.containingFile?.virtualFile == two?.containingFile?.virtualFile
    }
}

private interface ExpensiveApiUsageMessageProvider {

    val highlightTypeSingleCall: ProblemHighlightType
    val highlightTypeLoop: ProblemHighlightType
    val highlightTypeLambda: ProblemHighlightType

    fun buildMessageExpensiveMethodOverridden(
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        msgType: MsgType
    ): String

    fun buildMessageExpensiveTypeIsUsedInSignatureOfReferencedApi(
        referencedApi: PsiModifierListOwner,
        annotatedTypeUsedInSignature: AnnotatedContainingDeclaration
    ): String

    fun buildMessage(
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        msgType: MsgType
    ): String
}

private object DefaultExpensiveApiUsageMessageProvider : ExpensiveApiUsageMessageProvider {

    override val highlightTypeSingleCall
        get() = ProblemHighlightType.WEAK_WARNING

    override val highlightTypeLoop
        get() = ProblemHighlightType.GENERIC_ERROR_OR_WARNING

    override val highlightTypeLambda
        get() = ProblemHighlightType.GENERIC_ERROR_OR_WARNING

    override fun buildMessageExpensiveMethodOverridden(
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        msgType: MsgType
    ): String =
        with(annotatedContainingDeclaration) {
            if (isOwnAnnotation) {
                when (msgType) {
                    MsgType.LOOP -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.overridden.method.is.marked.expensive.itself.called_in_loops",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                    MsgType.LAMBDA -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.overridden.method.is.marked.expensive.itself.called_in_lambda",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                    MsgType.SINGLE_CALL -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.overridden.method.is.marked.expensive.itself",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                }
            }
            else {
                AnnotationsOnSteroidsBundle.message(
                    "jvm.inspections.expensive.api.usage.overridden.method.is.declared.in.expensive.api",
                    targetName,
                    containingDeclarationType,
                    containingDeclarationName,
                    presentableAnnotationName
                )
            }
        }

    override fun buildMessage(
        annotatedContainingDeclaration: AnnotatedContainingDeclaration,
        msgType: MsgType
    ): String =
        with(annotatedContainingDeclaration) {
            if (isOwnAnnotation) {
                when(msgType)
                {
                    MsgType.LOOP -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.api.is.marked.expensive.itself.called_in_loops",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                    MsgType.LAMBDA -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.api.is.marked.expensive.itself.called_in_lambda",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                    MsgType.SINGLE_CALL -> {
                        AnnotationsOnSteroidsBundle.message(
                            "jvm.inspections.expensive.api.usage.api.is.marked.expensive.itself",
                            targetName,
                            presentableAnnotationName
                        )
                    }
                }

            }
            else {
                AnnotationsOnSteroidsBundle.message(
                    "jvm.inspections.expensive.api.usage.api.is.declared.in.expensive.api",
                    targetName,
                    containingDeclarationType,
                    containingDeclarationName,
                    presentableAnnotationName
                )
            }
        }

    override fun buildMessageExpensiveTypeIsUsedInSignatureOfReferencedApi(
        referencedApi: PsiModifierListOwner,
        annotatedTypeUsedInSignature: AnnotatedContainingDeclaration
    ): String = AnnotationsOnSteroidsBundle.message(
        "jvm.inspections.expensive.api.usage.expensive.type.is.used.in.signature.of.referenced.api",
        DeprecationInspection.getPresentableName(referencedApi),
        annotatedTypeUsedInSignature.targetType,
        annotatedTypeUsedInSignature.targetName,
        annotatedTypeUsedInSignature.presentableAnnotationName
    )
}