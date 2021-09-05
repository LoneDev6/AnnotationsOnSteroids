// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package devs.beer.asteroids

import com.intellij.analysis.JvmAnalysisBundle
import com.intellij.codeInsight.AnnotationUtil
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
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ArrayUtilRt
import com.siyeh.ig.ui.ExternalizableStringSet
import devs.beer.asteroids.AnnotatedApiUsageUtil.findAnnotatedContainingDeclaration
import devs.beer.asteroids.AnnotatedApiUsageUtil.findAnnotatedTypeUsedInDeclarationSignature
import org.jetbrains.uast.*
import javax.swing.JPanel


class ExpensiveApiUsageInspection : LocalInspectionTool() {

    companion object {

        //TODO: refactor that crap
        private val SCHEDULED_FOR_REMOVAL_ANNOTATION_NAME: String = ""

        val DEFAULT_EXPENSIVE_API_ANNOTATIONS: List<String> = listOf(
            //
        )

        private val knownAnnotationMessageProviders = mapOf(SCHEDULED_FOR_REMOVAL_ANNOTATION_NAME to ScheduledForRemovalMessageProvider())
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
                    annotations,
                    knownAnnotationMessageProviders
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
    private val expensiveApiAnnotations: List<String>,
    private val knownAnnotationMessageProviders: Map<String, ExpensiveApiUsageMessageProvider>
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
        val annotationName = psiAnnotation.qualifiedName ?: return null
        return knownAnnotationMessageProviders[annotationName] ?: DefaultExpensiveApiUsageMessageProvider
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
            val message = if (isMethodOverriding) {
                messageProvider.buildMessageExpensiveMethodOverridden(annotatedContainingDeclaration)
            }
            else {
                messageProvider.buildMessage(annotatedContainingDeclaration)
            }
            val elementToHighlight = getElementToHighlight(sourceNode) ?: return false
            problemsHolder.registerProblem(elementToHighlight, message, messageProvider.problemHighlightType)

            return true
        }
        return false
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
                problemsHolder.registerProblem(elementToHighlight, message, messageProvider.problemHighlightType)

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

    val problemHighlightType: ProblemHighlightType

//    @InspectionMessage
    fun buildMessage(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String

//    @InspectionMessage
    fun buildMessageExpensiveMethodOverridden(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String

//    @InspectionMessage
    fun buildMessageExpensiveTypeIsUsedInSignatureOfReferencedApi(
        referencedApi: PsiModifierListOwner,
        annotatedTypeUsedInSignature: AnnotatedContainingDeclaration
    ): String
}

private object DefaultExpensiveApiUsageMessageProvider : ExpensiveApiUsageMessageProvider {

    override val problemHighlightType
        get() = ProblemHighlightType.WEAK_WARNING

    override fun buildMessageExpensiveMethodOverridden(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String =
        with(annotatedContainingDeclaration) {
            if (isOwnAnnotation) {
                AnnotationsOnSteroidsBundle.message(
                    "jvm.inspections.expensive.api.usage.overridden.method.is.marked.expensive.itself",
                    targetName,
                    presentableAnnotationName
                )
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

    override fun buildMessage(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String =
        with(annotatedContainingDeclaration) {
            if (isOwnAnnotation) {
                AnnotationsOnSteroidsBundle.message(
                    "jvm.inspections.expensive.api.usage.api.is.marked.expensive.itself",
                    targetName,
                    presentableAnnotationName
                )
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

//TODO: dafaq?
private class ScheduledForRemovalMessageProvider : ExpensiveApiUsageMessageProvider {

    override val problemHighlightType
        get() = ProblemHighlightType.GENERIC_ERROR

    override fun buildMessageExpensiveMethodOverridden(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String {
        val versionMessage = getVersionMessage(annotatedContainingDeclaration)
        return with(annotatedContainingDeclaration) {
            if (isOwnAnnotation) {
                JvmAnalysisBundle.message(
                    "jvm.inspections.scheduled.for.removal.method.overridden.marked.itself",
                    targetName,
                    versionMessage
                )
            }
            else {
                JvmAnalysisBundle.message(
                    "jvm.inspections.scheduled.for.removal.method.overridden.declared.in.marked.api",
                    targetName,
                    containingDeclarationType,
                    containingDeclarationName,
                    versionMessage
                )
            }
        }
    }

    override fun buildMessage(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String {
        val versionMessage = getVersionMessage(annotatedContainingDeclaration)
        return with(annotatedContainingDeclaration) {
            if (!isOwnAnnotation) {
                JvmAnalysisBundle.message(
                    "jvm.inspections.scheduled.for.removal.api.is.declared.in.marked.api",
                    targetName,
                    containingDeclarationType,
                    containingDeclarationName,
                    versionMessage
                )
            }
            else {
                JvmAnalysisBundle.message(
                    "jvm.inspections.scheduled.for.removal.api.is.marked.itself", targetName, versionMessage
                )
            }
        }
    }

    override fun buildMessageExpensiveTypeIsUsedInSignatureOfReferencedApi(
        referencedApi: PsiModifierListOwner,
        annotatedTypeUsedInSignature: AnnotatedContainingDeclaration
    ): String {
        val versionMessage = getVersionMessage(annotatedTypeUsedInSignature)
        return JvmAnalysisBundle.message(
            "jvm.inspections.scheduled.for.removal.scheduled.for.removal.type.is.used.in.signature.of.referenced.api",
            DeprecationInspection.getPresentableName(referencedApi),
            annotatedTypeUsedInSignature.targetType,
            annotatedTypeUsedInSignature.targetName,
            versionMessage
        )
    }

    private fun getVersionMessage(annotatedContainingDeclaration: AnnotatedContainingDeclaration): String {
        val versionValue = AnnotationUtil.getDeclaredStringAttributeValue(annotatedContainingDeclaration.psiAnnotation, "inVersion")
        return if (versionValue.isNullOrEmpty()) {
            JvmAnalysisBundle.message("jvm.inspections.scheduled.for.removal.future.version")
        }
        else {
            JvmAnalysisBundle.message("jvm.inspections.scheduled.for.removal.predefined.version", versionValue)
        }
    }
}