package fr.adrienbrault.idea.symfony2plugin.templating;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.Field;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.TwigTokenTypes;
import com.jetbrains.twig.elements.TwigBlockTag;
import com.jetbrains.twig.elements.TwigElementTypes;
import com.jetbrains.twig.elements.TwigTagWithFileReference;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.TwigHelper;
import fr.adrienbrault.idea.symfony2plugin.templating.dict.TwigExtension;
import fr.adrienbrault.idea.symfony2plugin.templating.dict.TwigMacro;
import fr.adrienbrault.idea.symfony2plugin.templating.dict.TwigSet;
import fr.adrienbrault.idea.symfony2plugin.templating.util.TwigExtensionParser;
import fr.adrienbrault.idea.symfony2plugin.templating.util.TwigTypeResolveUtil;
import fr.adrienbrault.idea.symfony2plugin.templating.util.TwigUtil;
import fr.adrienbrault.idea.symfony2plugin.templating.variable.TwigTypeContainer;
import fr.adrienbrault.idea.symfony2plugin.templating.variable.collector.ControllerDocVariableCollector;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.RegexPsiElementFilter;
import fr.adrienbrault.idea.symfony2plugin.util.controller.ControllerIndex;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TwigTemplateGoToLocalDeclarationHandler implements GotoDeclarationHandler {

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor) {

        ArrayList<PsiElement> psiElements = new ArrayList<PsiElement>();

        if(!Symfony2ProjectComponent.isEnabled(psiElement)) {
            return null;
        }

        // {{ goto_me() }}
        if (TwigHelper.getPrintBlockFunctionPattern().accepts(psiElement)) {
            psiElements.addAll(Arrays.asList(this.getMacros(psiElement)));
        }

        // {% from 'boo.html.twig' import goto_me %}
        if (TwigHelper.getTemplateImportFileReferenceTagPattern().accepts(psiElement)) {
            psiElements.addAll(Arrays.asList(this.getMacros(psiElement)));
        }

        // {% set foo  %}
        // {% set foo = bar %}
        if (PlatformPatterns
            .psiElement(TwigTokenTypes.IDENTIFIER)
            .withParent(
                PlatformPatterns.psiElement(TwigElementTypes.PRINT_BLOCK)
            ).withLanguage(TwigLanguage.INSTANCE).accepts(psiElement)) {

            psiElements.addAll(Arrays.asList(this.getSets(psiElement)));
        }

        // {{ function( }}
        if (PlatformPatterns
            .psiElement(TwigTokenTypes.IDENTIFIER)
            .beforeLeaf(PlatformPatterns.psiElement(TwigTokenTypes.LBRACE))
            .withParent(
                PlatformPatterns.psiElement(TwigElementTypes.PRINT_BLOCK)
            ).withLanguage(TwigLanguage.INSTANCE).accepts(psiElement)) {

            psiElements.addAll(Arrays.asList(this.getFunctions(psiElement)));
        }

        if(TwigHelper.getTypeCompletionPattern().accepts(psiElement)
            || TwigHelper.getPrintBlockFunctionPattern().accepts(psiElement)
            || TwigHelper.getVariableTypePattern().accepts(psiElement))
        {
            psiElements.addAll(Arrays.asList(this.getTypeGoto(psiElement)));
        }

        if(TwigHelper.getTwigDocBlockMatchPattern(ControllerDocVariableCollector.DOC_PATTERN).accepts(psiElement)) {
            psiElements.addAll(Arrays.asList(this.getControllerNameGoto(psiElement)));
        }

        // {{ parent() }}
        if(TwigHelper.getParentFunctionPattern().accepts(psiElement)) {
            psiElements.addAll(Arrays.asList(this.getParentGoto(psiElement)));
        }

        // constant('Post::PUBLISHED')
        if(TwigHelper.getPrintBlockFunctionPattern("constant").accepts(psiElement)) {
            psiElements.addAll(this.getConstantGoto(psiElement));
        }

        return psiElements.toArray(new PsiElement[psiElements.size()]);
    }

    private Collection<PsiElement> getConstantGoto(PsiElement psiElement) {

        Collection<PsiElement> targetPsiElements = new ArrayList<PsiElement>();

        String contents = psiElement.getText();
        if(StringUtils.isBlank(contents)) {
            return targetPsiElements;
        }

        // global constant
        if(!contents.contains(":")) {
            targetPsiElements.addAll(PhpIndex.getInstance(psiElement.getProject()).getConstantsByName(contents));
            return targetPsiElements;
        }

        // resolve class constants
        String[] parts = contents.split("::");
        if(parts.length != 2) {
            return targetPsiElements;
        }

        PhpClass phpClass = PhpElementsUtil.getClassInterface(psiElement.getProject(), parts[0]);
        if(phpClass == null) {
            return targetPsiElements;
        }

        Field field = phpClass.findFieldByName(parts[1], true);
        if(field != null) {
            targetPsiElements.add(field);
        }

        return targetPsiElements;
    }

    private PsiElement[] getTypeGoto(PsiElement psiElement) {

        ArrayList<PsiElement> targetPsiElements = new ArrayList<PsiElement>();

        // class, class.method, class.method.method
        // click on first item is our class name
        String[] beforeLeaf = TwigTypeResolveUtil.formatPsiTypeName(psiElement);
        if(beforeLeaf.length == 0) {
            Collection<TwigTypeContainer> twigTypeContainers = TwigTypeResolveUtil.resolveTwigMethodName(psiElement, TwigTypeResolveUtil.formatPsiTypeName(psiElement, true));
            for(TwigTypeContainer twigTypeContainer: twigTypeContainers) {
                if(twigTypeContainer.getPhpNamedElement() != null) {
                    targetPsiElements.add(twigTypeContainer.getPhpNamedElement());
                }
            }

        } else {
            Collection<TwigTypeContainer> types = TwigTypeResolveUtil.resolveTwigMethodName(psiElement, beforeLeaf);
            // provide method / field goto
            for(TwigTypeContainer twigTypeContainer: types) {
                if(twigTypeContainer.getPhpNamedElement() != null) {
                    targetPsiElements.addAll(TwigTypeResolveUtil.getTwigPhpNameTargets(twigTypeContainer.getPhpNamedElement(), psiElement.getText()));
                }
            }
        }

        return targetPsiElements.toArray(new PsiElement[targetPsiElements.size()]);
    }

    private PsiElement[] getFunctions(PsiElement psiElement) {
        HashMap<String, TwigExtension> functions = new TwigExtensionParser(psiElement.getProject()).getFunctions();

        String funcName = psiElement.getText();
        if(!functions.containsKey(funcName)) {
            return new PsiElement[0];
        }

        return PhpElementsUtil.getPsiElementsBySignature(psiElement.getProject(), functions.get(funcName).getSignature());
     }

    private PsiElement[] getSets(PsiElement psiElement) {
        String funcName = psiElement.getText();
        for(TwigSet twigSet: TwigUtil.getSetDeclaration(psiElement.getContainingFile())) {
           if(twigSet.getName().equals(funcName)) {
               return PsiTreeUtil.collectElements(psiElement.getContainingFile(), new RegexPsiElementFilter(
                   TwigTagWithFileReference.class,
                   "\\{%\\s?set\\s?" + Pattern.quote(funcName) + "\\s?.*")
               );
           }
        }

        return new PsiElement[0];
    }

    private PsiElement[] getMacros(PsiElement psiElement) {
        String funcName = psiElement.getText();
        String funcNameSearch = funcName;

        ArrayList<TwigMacro> twigMacros;

        // check for complete file as namespace import {% import "file" as foo %}
        if(psiElement.getPrevSibling() != null && PlatformPatterns.psiElement(TwigTokenTypes.DOT).accepts(psiElement.getPrevSibling())) {
            PsiElement psiElement1 = psiElement.getPrevSibling().getPrevSibling();
            if(psiElement1 == null) {
                return null;
            }

            funcNameSearch = psiElement1.getText() + "." + funcName;
            twigMacros = TwigUtil.getImportedMacrosNamespaces(psiElement.getContainingFile());
        } else {
            twigMacros = TwigUtil.getImportedMacros(psiElement.getContainingFile());
        }

        for(TwigMacro twigMacro : twigMacros) {
            if(twigMacro.getName().equals(funcNameSearch)) {

                // switch to alias mode
                final String macroName = twigMacro.getOriginalName() == null ? funcName : twigMacro.getOriginalName();

                PsiFile psiFile = TwigHelper.getTemplateFileByName(psiElement.getProject(), twigMacro.getTemplate());

                if(psiFile != null) {
                    return PsiTreeUtil.collectElements(psiFile, new RegexPsiElementFilter(
                        TwigElementTypes.MACRO_TAG,
                        "\\{%\\s?macro\\s?" + Pattern.quote(macroName) + "\\s?\\(.*%}")
                    );
                }

            }
        }

        return new PsiElement[0];
    }


    private PsiElement[] getControllerNameGoto(PsiElement psiElement) {
        Pattern pattern = Pattern.compile(ControllerDocVariableCollector.DOC_PATTERN);
        Matcher matcher = pattern.matcher(psiElement.getText());
        if (!matcher.find()) {
            return new PsiElement[0];
        }

        String controllerName = matcher.group(1);

        Method method = ControllerIndex.getControllerMethod(psiElement.getProject(), controllerName);
        if(method == null) {
            return new PsiElement[0];
        }

        return new PsiElement[] { method };
    }

    private PsiElement[] getParentGoto(PsiElement psiElement) {

        // find printblock
        PsiElement printBlock = psiElement.getParent();
        if(printBlock == null || !PlatformPatterns.psiElement(TwigElementTypes.PRINT_BLOCK).accepts(printBlock)) {
            return new PsiElement[0];
        }

        // printblock need to be child block statement
        PsiElement blockStatement = printBlock.getParent();
        if(blockStatement == null || !PlatformPatterns.psiElement(TwigElementTypes.BLOCK_STATEMENT).accepts(blockStatement)) {
            return new PsiElement[0];
        }

        // BlockTag is first child of block statement
        PsiElement blockTag = blockStatement.getFirstChild();
        if(!(blockTag instanceof TwigBlockTag)) {
            return new PsiElement[0];
        }

        String blockName = ((TwigBlockTag) blockTag).getName();
        return TwigTemplateGoToDeclarationHandler.getBlockNameGoTo(psiElement.getContainingFile(), blockName);
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext) {
        return null;
    }
}
